package si.marko.step.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One report from the step counter.
 *
 * @param rawSteps steps since the last boot, as the sensor reports them.
 * @param eventElapsedNanos the sensor's own timestamp for the reading (time since boot).
 *        On the Xiaomi 17T this is the moment of the last counted step, not the delivery time.
 * @param deliveredElapsedNanos time since boot when the app received it.
 * @param deliveredWallMillis wall clock time when the app received it.
 */
data class SensorReading(
    val rawSteps: Long,
    val eventElapsedNanos: Long,
    val deliveredElapsedNanos: Long,
    val deliveredWallMillis: Long,
) {
    /** How old the reading already was when it arrived. Never negative. */
    val ageAtDeliveryMillis: Long
        get() = ((deliveredElapsedNanos - eventElapsedNanos) / 1_000_000).coerceAtLeast(0)

    /** Wall clock time at which the sensor took the reading. */
    val eventWallMillis: Long get() = deliveredWallMillis - ageAtDeliveryMillis
}

/** Thin wrapper around the hardware step counter. */
class StepSensorReader(
    context: Context,
    private val onReading: (SensorReading) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    /**
     * @param maxReportLatencyMicros how long the sensor may hold readings back so the processor
     *        can sleep. Sensors without a hardware queue (the 17T reports none) ignore it.
     * @return false when there is no sensor or the system refused the registration.
     */
    fun start(maxReportLatencyMicros: Int = 0): Boolean {
        val sensor = stepCounter ?: return false
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, maxReportLatencyMicros)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        onReading(
            SensorReading(
                rawSteps = event.values[0].toLong(),
                eventElapsedNanos = event.timestamp,
                deliveredElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                deliveredWallMillis = System.currentTimeMillis(),
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * Registers briefly and returns the sensor's current value, or null when there is no
         * sensor, no permission, or no answer in time. The sensor reports its current value
         * right after registration, so this is quick.
         */
        suspend fun readOnce(context: Context, timeoutMillis: Long = 5_000): SensorReading? {
            var reader: StepSensorReader? = null
            return try {
                withTimeoutOrNull(timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        val r = StepSensorReader(context) { if (continuation.isActive) continuation.resume(it) }
                        reader = r
                        if (!r.start()) continuation.resume(null)
                    }
                }
            } finally {
                reader?.stop()
            }
        }
    }
}
