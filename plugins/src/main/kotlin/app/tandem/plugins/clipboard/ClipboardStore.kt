// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import javax.inject.Inject
import javax.inject.Singleton

/** The clipboard text we last sent or received, and when. Sent to a peer on connect. */
data class ClipEntry(
    val text: String,
    val timestamp: Long,
)

/** In-memory record of the latest local clipboard change. */
@Singleton
class ClipboardStore
    @Inject
    constructor() {
        private val lock = Any()
        private var latest: ClipEntry? = null

        fun latest(): ClipEntry? = synchronized(lock) { latest }

        fun record(
            text: String,
            at: Long,
        ) {
            synchronized(lock) { latest = ClipEntry(text, at) }
        }
    }
