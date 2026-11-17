package si.marko.step.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import si.marko.step.AppGraph
import si.marko.step.sensor.StepSensorReader
import java.util.concurrent.TimeUnit

/**
 * Safety net for when the system has killed the service. Runs about every half hour and
 *
 * 1. reads the step counter once and stores the reading, so steps keep landing in roughly the
 *    right hours even if the service cannot be brought back, and
 * 2. tries to restart the service. Android only allows that from the background for apps
 *    exempt from battery optimisation; a refusal is logged and tried again next time.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ServiceStatus.running.value) return Result.success()
        val graph = AppGraph.get(applicationContext)
        if (!StepService.canRun(applicationContext)) return Result.success()

        val reading = StepSensorReader.readOnce(applicationContext)
        if (reading != null) {
            runCatching { graph.repository.record(reading, graph.boot, "watchdog") }
        }
        val accepted = StepService.start(applicationContext, "watchdog found it stopped")
        graph.log.add(
            "Watchdog: service was not running; reading " +
                (if (reading != null) "stored (raw=${reading.rawSteps})" else "unavailable") +
                ", restart " + (if (accepted) "requested" else "not possible")
        )
        return Result.success()
    }
}

object Watchdog {
    private const val WORK_NAME = "watchdog"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(30, TimeUnit.MINUTES).build(),
        )
    }
}
