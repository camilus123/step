package si.marko.step.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts counting after the phone boots and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone booted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        StepService.start(context, reason)
        Watchdog.schedule(context)
    }
}
