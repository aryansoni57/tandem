// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class HandledType { Sms, Missed }

/** One thing the phone did for the laptop, shown in the "Handled" list of the Sync screen. */
data class HandledEntry(
    val id: Long,
    val type: HandledType,
    val title: String,
    val detail: String,
    val timeMs: Long,
)

/** The latest [MAX] handled items, newest first. Kept in memory: it is a recent-activity list, not history. */
@Singleton
class HandledLog
    @Inject
    constructor() {
        private val flow = MutableStateFlow<List<HandledEntry>>(emptyList())
        val entries: StateFlow<List<HandledEntry>> = flow.asStateFlow()
        private var next = 0L

        @Synchronized
        fun add(
            type: HandledType,
            title: String,
            detail: String,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            flow.value = (listOf(HandledEntry(next++, type, title, detail, nowMs)) + flow.value).take(MAX)
        }

        private companion object {
            const val MAX = 20
        }
    }
