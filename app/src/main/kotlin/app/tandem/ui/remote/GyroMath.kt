// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/** Turns how the phone points into movements of the laser dot. Pure maths, so it can be tested. */
object GyroMath {
    /** How far the dot travels per radian of turn, as a fraction of the screen width. */
    const val SENSITIVITY = 1.2f

    /**
     * Where the top edge of the phone points, from a device-to-world [rotation] matrix (9 values, row
     * major, as `SensorManager.getRotationMatrixFromVector` fills it; x east, y north, z up).
     * Returns yaw (clockwise from north, in radians) and pitch (above the horizon is positive).
     */
    fun yawPitch(rotation: FloatArray): Pair<Float, Float> {
        val x = rotation[1]
        val y = rotation[4]
        val z = rotation[7].coerceIn(-1f, 1f)
        return atan2(x, y) to asin(z)
    }

    /** The shortest signed turn from [previous] to [current], so crossing north does not jump by 2 pi. */
    fun angleDelta(
        previous: Float,
        current: Float,
    ): Float {
        var delta = current - previous
        while (delta > PI) delta -= TWO_PI
        while (delta < -PI) delta += TWO_PI
        return delta
    }

    /** Turning right moves the dot right; pointing higher moves it up, which is negative on screen. */
    fun screenDelta(
        yawDelta: Float,
        pitchDelta: Float,
        sensitivity: Float = SENSITIVITY,
    ): Pair<Float, Float> = (yawDelta * sensitivity) to (-pitchDelta * sensitivity)

    private const val TWO_PI = (2 * PI).toFloat()
}
