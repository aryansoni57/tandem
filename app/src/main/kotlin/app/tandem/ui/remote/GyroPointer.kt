// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reports how far the phone turned since the last sample, in screen fractions, while started. The first
 * sample after [start] only sets the reference, so the dot does not jump. Uses the game rotation vector,
 * which needs no magnetometer and does not drift north.
 */
class GyroPointer(
    context: Context,
    private val onDelta: (dx: Float, dy: Float) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotationMatrix = FloatArray(MATRIX_SIZE)
    private var last: Pair<Float, Float>? = null
    private var running = false

    /** Returns false when the phone has no rotation sensor. */
    fun start(): Boolean {
        if (running) return true
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return false
        last = null
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return running
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(this)
        running = false
        last = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val now = GyroMath.yawPitch(rotationMatrix)
        val before = last
        last = now
        if (before == null) return
        val (dx, dy) =
            GyroMath.screenDelta(
                GyroMath.angleDelta(before.first, now.first),
                GyroMath.angleDelta(before.second, now.second),
            )
        if (dx != 0f || dy != 0f) onDelta(dx, dy)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private companion object {
        const val MATRIX_SIZE = 9
    }
}
