// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import app.tandem.designsystem.theme.TandemTheme
import app.tandem.state.PointerButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real trackpad surface with touch input and checks which callbacks fire. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackpadGestureTest {
    @get:Rule val compose = createComposeRule()

    private val moves = mutableListOf<Offset>()
    private val scrolls = mutableListOf<Float>()
    private val clicks = mutableListOf<PointerButton>()
    private var doubles = 0
    private val holds = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent {
            TandemTheme(dynamicColor = false) {
                RemoteScreen(
                    state = RemoteUiState(),
                    onSelectMode = {},
                    onPointerSpeed = {},
                    onPointerDrag = { dx, dy -> moves += Offset(dx, dy) },
                    onClick = { clicks += it },
                    onDoubleClick = { doubles++ },
                    onHold = { holds += it },
                    onScroll = { scrolls += it },
                    onTypedChange = {},
                    onSendTyped = {},
                    onToggleModifier = {},
                    onKey = {},
                    onPresenter = {},
                    onCommands = {},
                )
            }
        }
    }

    @Test
    fun aQuickTouchIsALeftClick() {
        show()
        compose.onNodeWithTag("trackpad").performTouchInput {
            down(center)
            up()
        }
        assertEquals(listOf(PointerButton.Left), clicks)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun oneFingerDragMovesThePointer() {
        show()
        compose.onNodeWithTag("trackpad").performTouchInput {
            down(center)
            moveBy(Offset(40f, 10f))
            moveBy(Offset(20f, 0f))
            up()
        }
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.sumOf { it.x.toDouble() } > 0.0)
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun twoTapsInARowDoubleClick() {
        show()
        compose.onNodeWithTag("trackpad").performTouchInput {
            down(center)
            up()
            advanceEventTime(100)
            down(center)
            up()
        }
        assertEquals(1, doubles)
        assertEquals(1, clicks.size)
    }

    @Test
    fun twoFingersUpScrollDown() {
        show()
        compose.onNodeWithTag("trackpad").performTouchInput {
            down(0, center)
            down(1, center + Offset(60f, 0f))
            moveBy(0, Offset(0f, -50f))
            moveBy(1, Offset(0f, -50f))
            up(0)
            up(1)
        }
        assertTrue(scrolls.isNotEmpty())
        // The screen reports the raw finger movement (up is negative); the view model turns it into a scroll.
        assertTrue(scrolls.all { it < 0f })
        assertTrue(moves.isEmpty())
    }

    @Test
    fun holdingStillThenDraggingHoldsTheButton() {
        compose.mainClock.autoAdvance = false
        show()
        compose.onNodeWithTag("trackpad").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(TrackpadLogic.HOLD_MS + 100)
        compose.onNodeWithTag("trackpad").performTouchInput {
            moveBy(Offset(30f, 0f))
            up()
        }
        compose.mainClock.advanceTimeBy(50)
        assertEquals(listOf(true, false), holds)
        assertTrue(moves.isNotEmpty())
        assertTrue(clicks.isEmpty())
    }
}
