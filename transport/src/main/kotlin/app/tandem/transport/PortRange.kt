// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.io.IOException

/** Port constants from plan section 2.2. */
object PortRange {
    const val DISCOVERY_PORT = 1716
    val TCP_LISTEN: IntRange = 1716..1764
    val PAYLOAD: IntRange = 1739..1764

    /** Returns the result of [open] for the first port in [range] that does not throw. */
    fun <T> firstFree(
        range: IntRange,
        open: (Int) -> T,
    ): T {
        var last: IOException? = null
        for (port in range) {
            try {
                return open(port)
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException("no free port in $range", last)
    }
}
