// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import app.tandem.state.KeyMods

/** Pure rules for the trackpad gestures, kept apart from the pointer-input code so they can be tested. */
object TrackpadLogic {
    /** A second tap within this time of the first one is a double click. */
    const val DOUBLE_TAP_MS = 300L

    /** A finger down for this long without moving starts a drag (the left button is held). */
    const val HOLD_MS = 450L

    /** A touch that moves less than this many pixels counts as a tap, not a move. */
    const val TAP_SLOP_PX = 12f

    private const val SCROLL_SCALE = 0.1f

    fun isDoubleTap(
        previousTapMs: Long?,
        nowMs: Long,
    ): Boolean = previousTapMs != null && nowMs - previousTapMs in 0..DOUBLE_TAP_MS

    /**
     * Two fingers moving up scroll the page down, as on a touchscreen. The desktop's axis is positive
     * downwards, so the finger movement is negated.
     */
    fun scrollAmount(fingerDy: Float): Float = -fingerDy * SCROLL_SCALE

    /** Toggles one modifier in [mods]. */
    fun toggle(
        mods: KeyMods,
        modifier: Modifier,
    ): KeyMods =
        when (modifier) {
            Modifier.Ctrl -> mods.copy(ctrl = !mods.ctrl)
            Modifier.Alt -> mods.copy(alt = !mods.alt)
            Modifier.Shift -> mods.copy(shift = !mods.shift)
            Modifier.Super -> mods.copy(superKey = !mods.superKey)
        }

    enum class Modifier { Ctrl, Alt, Shift, Super }
}
