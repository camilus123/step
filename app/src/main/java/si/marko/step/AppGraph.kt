package si.marko.step

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import si.marko.step.core.BootInfo
import si.marko.step.data.StepDatabase
import si.marko.step.data.StepRepository
import si.marko.step.diag.EventLog
import java.io.File

/** The app's few long-lived objects, shared by the activity, the service and the watchdog. */
class AppGraph private constructor(context: Context) {

    val prefs: SharedPreferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    val log = EventLog(File(context.filesDir, "events.log"))
    private val database = StepDatabase.open(context)
    val repository = StepRepository(
        dao = database.stepDao(),
        log = log,
        onReboot = { prefs.edit().putLong(KEY_LAST_REBOOT_DETECTED, it).apply() },
    )

    /** The phone cannot reboot while this process lives, so this is read once. */
    val boot: BootInfo = run {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        BootInfo(bootCount, System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }

    companion object {
        const val KEY_LAST_REBOOT_DETECTED = "last_reboot_detected"

        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
