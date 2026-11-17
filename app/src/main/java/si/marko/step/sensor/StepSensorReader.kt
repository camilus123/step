package si.marko.step.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * One report from the step counter.
 *
 * @param rawSteps steps since the last boot, as the sensor reports them.
 * @param eventElapsedNanos the sensor's own timestamp for the reading (time since boot).
 * @param deliveredElapsedNanos time since boot when the app received it.
 * @param deliveredWallMillis wall clock time when the app received it.
 */
data class SensorReading(
    val rawSteps: Long,
    val eventElapsedNanos: Long,
    val deliveredElapsedNanos: Long,
    val deliveredWallMillis: Long,
) {
    /** How old the reading already was when it arrived. Large values mean batching. */
    val ageAtDeliveryMillis: Long get() = (deliveredElapsedNanos - eventElapsedNanos) / 1_000_000

    /** Wall clock time at which the sensor took the reading. */
    val eventWallMillis: Long get() = deliveredWallMillis - ageAtDeliveryMillis
}

/** Thin wrapper around the hardware step counter. Stage 1 only reads it while the app is open. */
class StepSensorReader(
    context: Context,
    private val onReading: (SensorReading) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    /** @return false when there is no sensor or the system refused the registration. */
    fun start(): Boolean {
        val sensor = stepCounter ?: return false
        // No batching here: this screen exists to watch the value change live.
        return sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
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
}
