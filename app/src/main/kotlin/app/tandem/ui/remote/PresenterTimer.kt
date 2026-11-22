// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

/**
 * Stopwatch for the presenter screen. Time is passed in so the logic stays pure and testable.
 * [runningSince] is the start of the current run, or null while paused.
 */
data class PresenterTimer(
    val accumulatedMs: Long = 0L,
    val runningSince: Long? = null,
) {
    val running: Boolean get() = runningSince != null

    fun elapsedMs(now: Long): Long = accumulatedMs + (runningSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)

    fun toggle(now: Long): PresenterTimer =
        if (running) {
            PresenterTimer(accumulatedMs = elapsedMs(now), runningSince = null)
        } else {
            copy(runningSince = now)
        }

    fun reset(): PresenterTimer = PresenterTimer()

    companion object {
        /** Formats milliseconds as mm:ss, or h:mm:ss after one hour. */
        fun format(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
