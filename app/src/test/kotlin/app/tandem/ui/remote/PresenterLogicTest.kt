// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI

class GyroMathTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test
    fun phoneHeldLevelAndFacingNorthIsZeroZero() {
        val (yaw, pitch) = GyroMath.yawPitch(identity)
        assertEquals(0f, yaw, 1e-6f)
        assertEquals(0f, pitch, 1e-6f)
    }

    @Test
    fun pointingEastIsAQuarterTurnAndUpIsPositivePitch() {
        // The device's top edge (second column) points east: world x.
        val east = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(PI.toFloat() / 2, GyroMath.yawPitch(east).first, 1e-6f)
        // Top edge pointing straight up: world z.
        val up = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(PI.toFloat() / 2, GyroMath.yawPitch(up).second, 1e-6f)
    }

    @Test
    fun angleDeltaTakesTheShortWayAcrossTheSeam() {
        val seam = PI.toFloat() - 0.05f
        assertEquals(0.1f, GyroMath.angleDelta(seam, -seam), 1e-5f)
        assertEquals(-0.1f, GyroMath.angleDelta(-seam, seam), 1e-5f)
        assertEquals(0.3f, GyroMath.angleDelta(0.2f, 0.5f), 1e-6f)
    }

    @Test
    fun turningRightMovesRightAndPointingHigherMovesUp() {
        val (dx, dy) = GyroMath.screenDelta(yawDelta = 0.1f, pitchDelta = 0.1f, sensitivity = 2f)
        assertTrue(dx > 0f)
        assertTrue(dy < 0f)
        assertEquals(0.2f, dx, 1e-6f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeKeysTest {
    private val taken = mutableListOf<Boolean>()

    @After
    fun release() {
        VolumeKeys.handler = null
    }

    private fun key(
        code: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
    ) = KeyEvent(0L, 0L, action, code, repeat)

    @Test
    fun withoutAHandlerTheKeyIsLeftToTheSystem() {
        assertFalse(VolumeKeys.onKey(key(KeyEvent.KEYCODE_VOLUME_DOWN)))
    }

    @Test
    fun downIsNextAndUpIsPrevious() {
        VolumeKeys.handler = { taken += it }
        assertTrue(VolumeKeys.onKey(key(KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertTrue(VolumeKeys.onKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(true, false), taken)
    }

    @Test
    fun releaseAndHeldRepeatsAreTakenButDoNotStepAgain() {
        VolumeKeys.handler = { taken += it }
        assertTrue(VolumeKeys.onKey(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP)))
        assertTrue(VolumeKeys.onKey(key(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 3)))
        assertTrue(taken.isEmpty())
    }

    @Test
    fun otherKeysAreNotTaken() {
        VolumeKeys.handler = { taken += it }
        assertFalse(VolumeKeys.onKey(key(KeyEvent.KEYCODE_BACK)))
    }
}
