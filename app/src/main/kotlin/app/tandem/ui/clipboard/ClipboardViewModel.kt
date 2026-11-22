// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.clipboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.R
import app.tandem.core.device.DeviceManager
import app.tandem.plugins.clipboard.AndroidSystemClipboard
import app.tandem.plugins.clipboard.ClipboardSync
import app.tandem.plugins.clipboard.describe
import app.tandem.state.ClipItem
import app.tandem.state.ClipboardHistorySource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClipboardUiState(
    val query: String = "",
    val filter: ClipFilter = ClipFilter.All,
    val autoSync: Boolean = true,
    val items: List<ClipItem> = emptyList(),
)

@HiltViewModel
class ClipboardViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val history: ClipboardHistorySource,
        private val deviceManager: DeviceManager,
        private val clipboardSync: ClipboardSync,
        private val systemClipboard: AndroidSystemClipboard,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val filter = MutableStateFlow(ClipFilter.All)
        private val messages = Channel<String>(Channel.BUFFERED)

        val messageFlow: Flow<String> = messages.receiveAsFlow()

        val state: StateFlow<ClipboardUiState> =
            combine(history.clips, history.autoSync, query, filter) { clips, auto, text, chip ->
                ClipboardUiState(query = text, filter = chip, autoSync = auto, items = clips.filterClips(chip, text))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipboardUiState())

        fun onQueryChange(text: String) {
            query.value = text
        }

        fun onFilterSelected(chip: ClipFilter) {
            filter.value = chip
        }

        fun setAutoSync(enabled: Boolean) {
            history.setAutoSync(enabled)
        }

        /** Puts the item on the phone clipboard. */
        fun copy(item: ClipItem) {
            viewModelScope.launch {
                systemClipboard.writeText(item.text)
                messages.send(context.getString(R.string.clipboard_copied))
            }
        }

        /** Sends whatever is on the phone clipboard to every connected, paired device. */
        fun sendCurrent() {
            viewModelScope.launch {
                val targets = deviceManager.deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
                messages.send(clipboardSync.sendLocal(targets).describe())
            }
        }
    }
