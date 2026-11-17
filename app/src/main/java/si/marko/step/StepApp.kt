package si.marko.step

import android.app.Application
import si.marko.step.service.Watchdog

class StepApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this).log.add("Process started: version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Watchdog.schedule(this)
    }
}
