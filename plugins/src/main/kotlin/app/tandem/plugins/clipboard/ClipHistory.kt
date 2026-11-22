// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.core.data.ClipDao
import app.tandem.core.data.ClipEntity
import app.tandem.core.data.SettingsStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Records clipboard text for the history screen. Does nothing unless the user turned history on. */
fun interface ClipHistory {
    suspend fun record(
        text: String,
        deviceId: String?,
        at: Long,
    )
}

@Singleton
class ClipboardHistoryRepository
    @Inject
    constructor(
        private val dao: ClipDao,
        private val settings: SettingsStore,
    ) : ClipHistory {
        override suspend fun record(
            text: String,
            deviceId: String?,
            at: Long,
        ) {
            if (!settings.clipboardHistory.first()) return
            dao.insert(ClipEntity(text = text, deviceId = deviceId, timestamp = at))
            dao.trim(MAX_ENTRIES)
        }

        companion object {
            const val MAX_ENTRIES = 200
        }
    }
