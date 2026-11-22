// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import app.tandem.sample.SampleData
import app.tandem.state.RunCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenterTimerTest {
    @Test
    fun timerAccumulatesAcrossPauses() {
        var timer = PresenterTimer().toggle(now = 1_000L)
        assertTrue(timer.running)
        assertEquals(2_500L, timer.elapsedMs(now = 3_500L))

        timer = timer.toggle(now = 3_500L)
        assertFalse(timer.running)
        assertEquals(2_500L, timer.elapsedMs(now = 9_000L))

        timer = timer.toggle(now = 10_000L)
        assertEquals(7_000L, timer.elapsedMs(now = 14_500L))
    }

    @Test
    fun resetClearsEverything() {
        val timer = PresenterTimer(accumulatedMs = 5_000L, runningSince = null).reset()
        assertEquals(0L, timer.elapsedMs(now = 99_999L))
        assertFalse(timer.running)
    }

    @Test
    fun formatUsesMinutesAndHoursWhenNeeded() {
        assertEquals("07:12", PresenterTimer.format(432_000L))
        assertEquals("00:00", PresenterTimer.format(-5L))
        assertEquals("1:02:03", PresenterTimer.format(3_723_000L))
    }
}

class CommandConfirmTest {
    private val lock = SampleData.commands.first { it.id == "lock" }
    private val poweroff = SampleData.commands.first { it.id == "poweroff" }

    @Test
    fun safeCommandRunsImmediately() {
        val step = CommandConfirm.request(CommandConfirmState(), lock)
        assertEquals(lock, step.run)
        assertNull(step.state.pending)
    }

    @Test
    fun destructiveCommandWaitsForConfirm() {
        val requested = CommandConfirm.request(CommandConfirmState(), poweroff)
        assertNull(requested.run)
        assertEquals(poweroff, requested.state.pending)

        val confirmed = CommandConfirm.confirm(requested.state)
        assertEquals(poweroff, confirmed.run)
        assertNull(confirmed.state.pending)
    }

    @Test
    fun cancelDropsPendingWithoutRunning() {
        val requested = CommandConfirm.request(CommandConfirmState(), poweroff)
        val cancelled = CommandConfirm.cancel()
        assertNull(cancelled.run)
        assertNull(cancelled.state.pending)
    }

    @Test
    fun confirmWithoutPendingDoesNothing() {
        val step = CommandConfirm.confirm(CommandConfirmState())
        assertNull(step.run)
    }

    @Test
    fun safeCommandClearsAnyPendingShutdown() {
        val other = RunCommand("x", "X", "x", lock.icon, destructive = false)
        val step = CommandConfirm.request(CommandConfirmState(pending = poweroff), other)
        assertNull(step.state.pending)
        assertEquals(other, step.run)
    }
}

class TrackpadLogicTest {
    @Test
    fun secondTapInsideTheWindowIsADoubleTap() {
        assertTrue(TrackpadLogic.isDoubleTap(previousTapMs = 1_000L, nowMs = 1_250L))
        assertFalse(TrackpadLogic.isDoubleTap(previousTapMs = 1_000L, nowMs = 1_400L))
        assertFalse(TrackpadLogic.isDoubleTap(previousTapMs = null, nowMs = 1_000L))
        assertFalse(TrackpadLogic.isDoubleTap(previousTapMs = 2_000L, nowMs = 1_000L))
    }

    @Test
    fun fingersMovingUpScrollDown() {
        assertTrue(TrackpadLogic.scrollAmount(fingerDy = -20f) > 0f)
        assertTrue(TrackpadLogic.scrollAmount(fingerDy = 20f) < 0f)
        assertEquals(0f, TrackpadLogic.scrollAmount(0f), 0f)
    }

    @Test
    fun modifiersToggleOneAtATime() {
        var mods = app.tandem.state.KeyMods.None
        mods = TrackpadLogic.toggle(mods, TrackpadLogic.Modifier.Ctrl)
        mods = TrackpadLogic.toggle(mods, TrackpadLogic.Modifier.Shift)
        assertTrue(mods.ctrl && mods.shift && !mods.alt && !mods.superKey)
        assertTrue(mods.any)
        mods = TrackpadLogic.toggle(mods, TrackpadLogic.Modifier.Ctrl)
        assertFalse(mods.ctrl)
        assertFalse(app.tandem.state.KeyMods.None.any)
    }
}
