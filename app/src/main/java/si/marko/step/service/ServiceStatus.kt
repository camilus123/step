package si.marko.step.service

import kotlinx.coroutines.flow.MutableStateFlow
import si.marko.step.sensor.SensorReading

/** Live facts about the counting service, for the screen and the diagnostics. Process-local. */
object ServiceStatus {
    val running = MutableStateFlow(false)
    val lastReading = MutableStateFlow<SensorReading?>(null)

    @Volatile var startedAtMillis: Long = 0
    @Volatile var startReason: String = "-"
    @Volatile var listenerRegistered: Boolean = false
    @Volatile var readingsThisRun: Long = 0
    @Volatile var notificationUpdates: Long = 0
}
