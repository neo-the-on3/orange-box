package io.github.neotheon3.orangebox.core.driving.crash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.common.OrangeLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import kotlin.math.sqrt

/** One accelerometer reading: [x], [y], [z] in m/s², gravity included; [tMillis] is the sensor's own clock. */
data class MotionSample(val tMillis: Long, val x: Float, val y: Float, val z: Float) {
    /** Total acceleration, g (1.0 at rest). */
    val magnitudeG: Double get() = sqrt((x * x + y * y + z * z).toDouble()) / STANDARD_GRAVITY

    companion object {
        const val STANDARD_GRAVITY = 9.80665

        /** A reading of [g] along one axis, for traces and tests. */
        fun ofG(tMillis: Long, g: Double) = MotionSample(tMillis, 0f, 0f, (g * STANDARD_GRAVITY).toFloat())
    }
}

/** Cold: the sensor is only registered while the flow is collected (the crash monitor collects only while driving). */
fun interface MotionSource {
    fun samples(): Flow<MotionSample>
}

/** Replays fixed samples; the fake source for tests and recorded traces. */
class ReplayMotionSource(private val trace: List<MotionSample>) : MotionSource {
    override fun samples(): Flow<MotionSample> = trace.asFlow()
}

/**
 * The platform accelerometer at ~50 Hz (SENSOR_DELAY_GAME). Completes empty on devices without one.
 * No permission is needed (the 200 Hz cap of API 31+ does not apply at this rate).
 */
class AndroidMotionSource @Inject constructor(@ApplicationContext private val context: Context) : MotionSource {
    override fun samples(): Flow<MotionSample> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            OrangeLog.i(OrangeLog.Areas.APP, "motion_off", "reason" to "no_accelerometer")
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(MotionSample(event.timestamp / 1_000_000, event.values[0], event.values[1], event.values[2]))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        OrangeLog.i(OrangeLog.Areas.APP, "motion_on")
        awaitClose {
            manager.unregisterListener(listener)
            OrangeLog.i(OrangeLog.Areas.APP, "motion_off", "reason" to "stopped")
        }
    }
}
