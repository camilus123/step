package si.marko.step

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import si.marko.step.diag.BootInfo
import si.marko.step.diag.BootTracker
import si.marko.step.diag.DiagSection
import si.marko.step.diag.DiagnosticsReport
import si.marko.step.diag.EventLog
import si.marko.step.sensor.SensorReading
import si.marko.step.sensor.StepSensorReader
import java.io.File
import java.time.Instant
import java.time.ZoneId

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val log = EventLog(File(app.filesDir, "events.log"))
    private val reader = StepSensorReader(app, ::onReading)

    var reading by mutableStateOf<SensorReading?>(null)
        private set
    var firstRawThisSession by mutableStateOf<Long?>(null)
        private set
    var listening by mutableStateOf(false)
        private set
    var events by mutableStateOf(log.snapshot())
        private set

    /** Bumped whenever something outside Compose state may have changed (permissions, time). */
    var refreshTick by mutableIntStateOf(0)
        private set

    val hasSensor: Boolean get() = reader.stepCounter != null

    init {
        logEvent("App started: version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (!hasSensor) logEvent("No TYPE_STEP_COUNTER sensor on this device")
        checkForReboot()
    }

    // ---- permissions -------------------------------------------------------------------

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    val canReadSensor: Boolean get() = isGranted(Manifest.permission.ACTIVITY_RECOGNITION)

    fun missingPermissions(): Array<String> = buildList {
        if (!canReadSensor) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33 && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun onPermissionResult(result: Map<String, Boolean>) {
        result.forEach { (permission, granted) ->
            logEvent("Permission ${permission.substringAfterLast('.')}: ${if (granted) "granted" else "denied"}")
        }
        startListening()
    }

    // ---- sensor ------------------------------------------------------------------------

    fun startListening() {
        refreshTick++
        if (listening || !hasSensor || !canReadSensor) return
        listening = reader.start()
        logEvent(if (listening) "Sensor listener registered" else "Sensor listener registration REFUSED by the system")
    }

    fun stopListening() {
        if (!listening) return
        reader.stop()
        listening = false
    }

    fun refresh() {
        refreshTick++
    }

    private fun onReading(new: SensorReading) {
        val previous = reading
        if (previous == null) {
            firstRawThisSession = new.rawSteps
            val lastSeen = prefs.getLong(KEY_LAST_RAW, -1L)
            val since = when {
                lastSeen < 0 -> "no earlier value stored"
                new.rawSteps >= lastSeen -> "last stored value $lastSeen, so +${new.rawSteps - lastSeen} while the app was not listening"
                else -> "last stored value $lastSeen is higher, so the counter was reset in between"
            }
            logEvent("First reading: raw=${new.rawSteps}, age at delivery ${new.ageAtDeliveryMillis} ms ($since)")
        } else if (new.rawSteps < previous.rawSteps) {
            // Should only happen across a reboot, which also restarts the app. Worth knowing if not.
            logEvent("Raw value went DOWN without an app restart: ${previous.rawSteps} -> ${new.rawSteps}")
            firstRawThisSession = new.rawSteps
        }
        reading = new
        prefs.edit().putLong(KEY_LAST_RAW, new.rawSteps).apply()
    }

    // ---- boot --------------------------------------------------------------------------

    private fun currentBootInfo(): BootInfo {
        val bootCount = runCatching {
            Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        return BootInfo(bootCount, System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }

    private fun checkForReboot() {
        val previous = if (prefs.contains(KEY_BOOT_TIME)) {
            BootInfo(
                bootCount = prefs.getInt(KEY_BOOT_COUNT, -1).takeIf { it >= 0 },
                bootTimeMillis = prefs.getLong(KEY_BOOT_TIME, 0L),
            )
        } else null
        val current = currentBootInfo()
        if (BootTracker.rebootedSince(previous, current)) {
            logEvent("Reboot detected: boot count ${previous?.bootCount} -> ${current.bootCount}, booted at ${formatTime(current.bootTimeMillis)}")
            prefs.edit().putLong(KEY_LAST_REBOOT_DETECTED, System.currentTimeMillis()).apply()
        }
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, current.bootCount ?: -1)
            .putLong(KEY_BOOT_TIME, current.bootTimeMillis)
            .apply()
    }

    // ---- diagnostics -------------------------------------------------------------------

    private fun logEvent(message: String) {
        log.add(message)
        events = log.snapshot()
    }

    private fun formatTime(millis: Long): String =
        EventLog.TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun sensorRows(label: String, sensor: Sensor?): List<Pair<String, String>> =
        if (sensor == null) {
            listOf("$label present" to "NO")
        } else {
            listOf(
                "$label present" to "yes",
                "$label name" to sensor.name,
                "$label vendor" to sensor.vendor,
                "$label version" to sensor.version.toString(),
                "$label power" to "${sensor.power} mA",
                "$label wake-up" to yesNo(sensor.isWakeUpSensor),
                "$label FIFO reserved / max" to "${sensor.fifoReservedEventCount} / ${sensor.fifoMaxEventCount}",
            )
        }

    fun buildReport(): DiagnosticsReport {
        val app = getApplication<Application>()
        val current = reading
        val boot = currentBootInfo()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lastReboot = prefs.getLong(KEY_LAST_REBOOT_DETECTED, 0L)

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
                    "Build" to Build.DISPLAY,
                    "Incremental" to Build.VERSION.INCREMENTAL,
                ),
            ),
            DiagSection(
                "Step sensor",
                sensorRows("Step counter", reader.stepCounter) +
                    listOf("Step detector present" to yesNo(reader.stepDetector != null)),
            ),
            DiagSection(
                "Reading",
                listOf(
                    "Listener registered" to yesNo(listening),
                    "Raw value" to (current?.rawSteps?.toString() ?: "none yet"),
                    "Reading taken at" to (current?.let { formatTime(it.eventWallMillis) } ?: "-"),
                    "Reading delivered at" to (current?.let { formatTime(it.deliveredWallMillis) } ?: "-"),
                    "Age at delivery" to (current?.let { "${it.ageAtDeliveryMillis} ms" } ?: "-"),
                    "Steps since app opened" to
                        (current?.let { c -> firstRawThisSession?.let { (c.rawSteps - it).toString() } } ?: "-"),
                ),
            ),
            DiagSection(
                "Permissions",
                listOf(
                    "ACTIVITY_RECOGNITION" to grantText(Manifest.permission.ACTIVITY_RECOGNITION),
                    "POST_NOTIFICATIONS" to
                        if (Build.VERSION.SDK_INT >= 33) grantText(Manifest.permission.POST_NOTIFICATIONS)
                        else "not needed on this Android version",
                    "Exempt from battery optimisation" to yesNo(power.isIgnoringBatteryOptimizations(app.packageName)),
                ),
            ),
            DiagSection(
                "Background service",
                listOf("Running" to "not part of this build (arrives in stage 2)"),
            ),
            DiagSection(
                "Boot",
                listOf(
                    "Boot count" to (boot.bootCount?.toString() ?: "not available"),
                    "Booted at" to formatTime(boot.bootTimeMillis),
                    "Last reboot detected at" to (if (lastReboot > 0) formatTime(lastReboot) else "none since install"),
                ),
            ),
        )
        return DiagnosticsReport(sections, log.snapshot().takeLast(50))
    }

    private fun grantText(permission: String) = if (isGranted(permission)) "granted" else "NOT granted"

    private companion object {
        const val KEY_LAST_RAW = "last_raw"
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_BOOT_TIME = "boot_time"
        const val KEY_LAST_REBOOT_DETECTED = "last_reboot_detected"
    }
}
