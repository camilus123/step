package si.marko.step

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import si.marko.step.core.Goal
import si.marko.step.core.Period
import si.marko.step.core.PeriodKind
import si.marko.step.core.PeriodStats
import si.marko.step.core.StatsCalculator
import si.marko.step.diag.DiagSection
import si.marko.step.diag.DiagnosticsReport
import si.marko.step.diag.EventLog
import si.marko.step.service.ServiceStatus
import si.marko.step.service.StepNotification
import si.marko.step.service.StepService
import si.marko.step.widget.StepWidget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AppGraph.get(app)
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val day = MutableStateFlow(graph.repository.todayEpochDay())

    /** Today's steps, live from the database. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todaySteps: Flow<Long> = day.flatMapLatest { graph.repository.dayTotal(it) }

    /** Today's steps per hour: always 24 values. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayHours: Flow<List<Long>> =
        day.flatMapLatest { graph.repository.hoursOfDay(it) }.map(StatsCalculator::hourly)

    // ---- statistics --------------------------------------------------------------------

    private val statsPeriod = MutableStateFlow(Period.containing(PeriodKind.WEEK, LocalDate.now()))

    /** The figures for the week, month or year being looked at; follows the database live. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: Flow<PeriodStats> = statsPeriod.flatMapLatest { period ->
        graph.repository.dayTotals(period.start, period.end).map { totals ->
            StatsCalculator.compute(
                period = period,
                dayTotals = totals,
                today = LocalDate.now(),
                firstRecordedDay = graph.repository.firstDayWithSteps(),
                goals = graph.repository.goalHistory(),
            )
        }
    }

    fun showStats(kind: PeriodKind) {
        if (statsPeriod.value.kind != kind) statsPeriod.value = Period.containing(kind, LocalDate.now())
    }

    /** Earlier (negative) or later (positive) period. Never beyond the current one. */
    fun moveStats(by: Long) {
        val target = statsPeriod.value.shifted(by)
        if (target.start <= LocalDate.now()) statsPeriod.value = target
    }

    val serviceRunning = ServiceStatus.running
    val goal: Int = Goal.DEFAULT

    var report by mutableStateOf(DiagnosticsReport(emptyList(), emptyList()))
        private set

    /** Bumped whenever something outside Compose state may have changed (permissions, time). */
    var refreshTick by mutableIntStateOf(0)
        private set

    val hasSensor: Boolean get() = stepCounter != null

    // ---- permissions and background allowance ------------------------------------------

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(): Array<String> = buildList {
        if (!isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33 && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun onPermissionResult(result: Map<String, Boolean>) {
        result.forEach { (permission, granted) ->
            graph.log.add("Permission ${permission.substringAfterLast('.')}: ${if (granted) "granted" else "denied"}")
        }
        onForeground("permission granted")
    }

    fun isExemptFromBatteryOptimisation(): Boolean {
        val app = getApplication<Application>()
        return (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)
    }

    // ---- lifecycle ---------------------------------------------------------------------

    /** Called whenever the screen comes to the front. Starting the service here always works. */
    fun onForeground(reason: String = "app opened") {
        day.value = graph.repository.todayEpochDay()
        if (hasSensor) StepService.start(getApplication(), reason)
        refresh()
    }

    fun refresh() {
        refreshTick++
        viewModelScope.launch { report = buildReport() }
    }

    // ---- diagnostics -------------------------------------------------------------------

    private fun formatTime(millis: Long): String =
        if (millis <= 0) "-" else EventLog.TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun yesNo(value: Boolean) = if (value) "yes" else "NO"

    private fun grantText(permission: String) = if (isGranted(permission)) "granted" else "NOT granted"

    private fun standbyBucket(app: Application): String = runCatching {
        when (val bucket = (app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket) {
            5 -> "exempted (5)"
            10 -> "active (10)"
            20 -> "working set (20)"
            30 -> "frequent (30)"
            40 -> "rare (40)"
            45 -> "restricted (45)"
            else -> bucket.toString()
        }
    }.getOrDefault("not available")

    private fun channelText(app: Application, id: String): String {
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
            ?: return "not created yet"
        return "importance ${channel.importance}, lock screen visibility ${channel.lockscreenVisibility}, " +
            "sound ${if (channel.sound == null) "none" else "set"}, vibration ${if (channel.shouldVibrate()) "on" else "off"}"
    }

    private fun liveUpdateAllowed(app: Application): String =
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching { yesNo(app.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()) }
                .getOrDefault("unknown")
        } else "needs Android 16"

    suspend fun buildReport(): DiagnosticsReport {
        val app = getApplication<Application>()
        val reading = ServiceStatus.lastReading.value
        val state = graph.repository.counterState()
        val today = graph.repository.todayEpochDay()
        val running = ServiceStatus.running.value

        val sections = listOf(
            DiagSection(
                "App",
                listOf(
                    "Version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    "Package" to app.packageName,
                    "Now" to formatTime(System.currentTimeMillis()),
                    "Time zone" to ZoneId.systemDefault().id,
                ),
            ),
            DiagSection(
                "Device",
                listOf(
                    "Model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Incremental" to Build.VERSION.INCREMENTAL,
                ),
            ),
            DiagSection(
                "Step sensor",
                if (stepCounter == null) {
                    listOf("Step counter present" to "NO")
                } else {
                    listOf(
                        "Step counter present" to "yes",
                        "Name / vendor" to "${stepCounter.name} / ${stepCounter.vendor}",
                        "Wake-up" to if (stepCounter.isWakeUpSensor) "yes" else "no",
                        "FIFO reserved / max" to "${stepCounter.fifoReservedEventCount} / ${stepCounter.fifoMaxEventCount}",
                        "Step detector present" to if (stepDetector != null) "yes" else "no",
                    )
                },
            ),
            DiagSection(
                "Background service",
                listOf(
                    "Running" to yesNo(running),
                    "Started at" to if (running) formatTime(ServiceStatus.startedAtMillis) else "-",
                    "Start reason" to if (running) ServiceStatus.startReason else "-",
                    "Sensor listener registered" to yesNo(ServiceStatus.listenerRegistered),
                    "Readings this run" to ServiceStatus.readingsThisRun.toString(),
                    "Notification updates this run" to ServiceStatus.notificationUpdates.toString(),
                    "Home screen widgets placed" to StepWidget.countText(app),
                ),
            ),
            DiagSection(
                "Last reading (this process)",
                listOf(
                    "Raw value" to (reading?.rawSteps?.toString() ?: "none yet"),
                    "Taken at" to (reading?.let { formatTime(it.eventWallMillis) } ?: "-"),
                    "Delivered at" to (reading?.let { formatTime(it.deliveredWallMillis) } ?: "-"),
                    "Age at delivery" to (reading?.let { "${it.ageAtDeliveryMillis} ms" } ?: "-"),
                ),
            ),
            DiagSection(
                "Stored data",
                listOf(
                    "Today (${LocalDate.ofEpochDay(today)})" to "${graph.repository.dayTotalOnce(today)} steps",
                    "Yesterday" to "${graph.repository.dayTotalOnce(today - 1)} steps",
                    "All time" to "${graph.repository.allTimeTotal()} steps on ${graph.repository.daysWithSteps()} day(s)",
                    "Baseline raw value" to (state?.lastRaw?.toString() ?: "none yet"),
                    "Baseline taken at" to (state?.let { formatTime(it.lastEventWallMillis) } ?: "-"),
                    "Baseline boot count" to (state?.bootCount?.toString() ?: "-"),
                    "Goal" to goal.toString(),
                ),
            ),
            DiagSection(
                "Permissions and restrictions",
                listOf(
                    "ACTIVITY_RECOGNITION" to grantText(Manifest.permission.ACTIVITY_RECOGNITION),
                    "POST_NOTIFICATIONS" to
                        if (Build.VERSION.SDK_INT >= 33) grantText(Manifest.permission.POST_NOTIFICATIONS)
                        else "not needed on this Android version",
                    "Notifications enabled" to yesNo(NotificationManagerCompat.from(app).areNotificationsEnabled()),
                    "Notification channel" to channelText(app, StepNotification.CHANNEL_ID),
                    "Live updates allowed by system" to liveUpdateAllowed(app),
                    "Exempt from battery optimisation" to yesNo(isExemptFromBatteryOptimisation()),
                    "App standby bucket" to standbyBucket(app),
                ),
            ),
            DiagSection(
                "Boot",
                listOf(
                    "Boot count" to (graph.boot.bootCount?.toString() ?: "not available"),
                    "Booted at" to formatTime(graph.boot.bootTimeMillis),
                    "Last reboot detected at" to
                        formatTime(graph.prefs.getLong(AppGraph.KEY_LAST_REBOOT_DETECTED, 0L)).let { if (it == "-") "none since install" else it },
                ),
            ),
        )
        return DiagnosticsReport(sections, graph.log.snapshot().takeLast(80))
    }
}
