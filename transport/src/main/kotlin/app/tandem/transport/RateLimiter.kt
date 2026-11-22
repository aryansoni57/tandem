// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Admits at most one event per [minIntervalMs] per key. The discovery path uses it per IP and
 * per deviceId (1 connection per second, as in the reference).
 */
class RateLimiter(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val last = ConcurrentHashMap<String, Long>()

    /** Returns true and records the event when [key] may proceed now. */
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        var allowed = false
        last.compute(key) { _, previous ->
            if (previous == null || now - previous >= minIntervalMs) {
                allowed = true
                now
            } else {
                previous
            }
        }
        return allowed
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
    }
}
