package si.marko.step.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import si.marko.step.AppGraph
import si.marko.step.core.Goal
import si.marko.step.sensor.SensorReading
import si.marko.step.sensor.StepSensorReader
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The foreground service that keeps counting all day. It listens to the step counter, hands
 * every reading to the repository and keeps the ongoing notification up to date.
 *
 * If the system kills it, nothing is lost: the sensor keeps its cumulative count, and the next
 * reading after a restart includes the steps walked in between.
 */
class StepService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val readings = Channel<SensorReading>(Channel.UNLIMITED)
    private lateinit var graph: AppGraph
    private lateinit var reader: StepSensorReader
    private lateinit var day: MutableStateFlow<Long>
    private var begun = false

    /** Which of the two notification ids is in use right now. */
    @Volatile
    private var notificationId = StepNotification.NOTIFICATION_ID_A

    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            day.value = graph.repository.todayEpochDay()
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (LockScreenOptions.load(graph.prefs).repostOnScreenOff) repost("screen off")
        }
    }

    private val optionsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in LockScreenOptions.KEYS) repost("options changed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        day = MutableStateFlow(graph.repository.todayEpochDay())
        reader = StepSensorReader(this) { readings.trySend(it) }
        StepNotification.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "restarted by the system"
        try {
            // The real count replaces this placeholder within moments.
            enterForeground(notificationId)
        } catch (e: Exception) {
            graph.log.add("Service could not enter the foreground ($reason): ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!begun) {
            begun = true
            begin(reason)
        }
        return START_STICKY
    }

    private fun buildNotification() =
        StepNotification.build(this, lastShownSteps, Goal.DEFAULT, LockScreenOptions.load(graph.prefs))

    private fun enterForeground(id: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(id, buildNotification())
        }
    }

    /**
     * Posts the notification as a brand new one (under the other id) and removes the old one.
     * To the system this is a notification that has just arrived, which matters on lock
     * screens that only list what came in since the last unlock.
     */
    private fun repost(why: String) {
        val oldId = notificationId
        val newId = if (oldId == StepNotification.NOTIFICATION_ID_A) StepNotification.NOTIFICATION_ID_B else StepNotification.NOTIFICATION_ID_A
        try {
            enterForeground(newId)
            notificationId = newId
            getSystemService(NotificationManager::class.java).cancel(oldId)
            ServiceStatus.reposts++
        } catch (e: Exception) {
            graph.log.add("Posting the notification afresh failed ($why): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun begin(reason: String) {
        ServiceStatus.startedAtMillis = System.currentTimeMillis()
        ServiceStatus.startReason = reason
        ServiceStatus.readingsThisRun = 0
        ServiceStatus.notificationUpdates = 0
        ServiceStatus.reposts = 0
        ServiceStatus.running.value = true

        val registered = reader.start(MAX_REPORT_LATENCY_MICROS)
        ServiceStatus.listenerRegistered = registered
        graph.log.add(
            "Service started ($reason)" + when {
                reader.stepCounter == null -> ": NO STEP COUNTER SENSOR"
                !registered -> ": sensor listener REFUSED"
                else -> ""
            }
        )

        // Readings are stored one after another, in arrival order.
        scope.launch {
            for (reading in readings) {
                ServiceStatus.lastReading.value = reading
                ServiceStatus.readingsThisRun++
                runCatching { graph.repository.record(reading, graph.boot, "service") }
                    .onFailure { graph.log.add("Storing a reading failed: ${it.javaClass.simpleName}: ${it.message}") }
                day.value = graph.repository.todayEpochDay()
            }
        }

        // Notification: follows today's total, at most one update per interval. "conflate"
        // keeps only the newest value while waiting, so the final count is always shown.
        scope.launch {
            day.flatMapLatest { graph.repository.dayTotal(it) }
                .distinctUntilChanged()
                .conflate()
                .collect { steps ->
                    lastShownSteps = steps
                    getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification())
                    ServiceStatus.notificationUpdates++
                    delay(NOTIFICATION_MIN_INTERVAL_MS)
                }
        }

        // Midnight: switch to the new day even if nobody takes a step. The broadcasts cover
        // clock and time zone changes and the screen turning on; the timer covers the rest.
        ContextCompat.registerReceiver(
            this,
            clockChanged,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                // Catches the date change at the latest when the lock screen is looked at.
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        graph.prefs.registerOnSharedPreferenceChangeListener(optionsChanged)
        scope.launch {
            while (true) {
                val now = ZonedDateTime.now(ZoneId.systemDefault())
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                // Short hops instead of one long sleep: this timer stands still while the
                // phone is in deep sleep, so a single delay until midnight would fire late.
                delay((Duration.between(now, nextMidnight).toMillis() + 1_000).coerceAtMost(10 * 60_000L))
                day.value = graph.repository.todayEpochDay()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        graph.log.add("App swiped away from recent apps (service keeps running unless the system stops it)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (begun) {
            reader.stop()
            runCatching { unregisterReceiver(clockChanged) }
            runCatching { unregisterReceiver(screenOff) }
            graph.prefs.unregisterOnSharedPreferenceChangeListener(optionsChanged)
            graph.log.add("Service destroyed")
        }
        ServiceStatus.running.value = false
        ServiceStatus.listenerRegistered = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REASON = "reason"

        /** One minute. Lets a sensor with a hardware queue batch; harmless without one. */
        private const val MAX_REPORT_LATENCY_MICROS = 60_000_000

        /** Updating on every step would waste battery and can get the app rate-limited. */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 5_000L

        /** Survives service restarts within one process, so the placeholder is usually right. */
        @Volatile
        private var lastShownSteps: Long = 0

        fun canRun(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts the service unless it already runs. Safe to call from anywhere, any number of
         * times. Android refuses foreground service starts from the background in some
         * situations; that is logged, not thrown.
         *
         * @return true when the service runs or the start request was accepted.
         */
        fun start(context: Context, reason: String): Boolean {
            if (ServiceStatus.running.value) return true
            val log = AppGraph.get(context).log
            if (!canRun(context)) {
                log.add("Service not started ($reason): physical activity permission missing")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StepService::class.java).putExtra(EXTRA_REASON, reason),
                )
                true
            } catch (e: Exception) {
                log.add("Service start refused ($reason): ${e.javaClass.simpleName}")
                false
            }
        }
    }
}
