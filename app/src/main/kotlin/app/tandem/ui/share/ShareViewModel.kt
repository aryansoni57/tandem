// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.R
import app.tandem.core.device.DeviceManager
import app.tandem.plugins.clipboard.ClipboardSync
import app.tandem.plugins.clipboard.describe
import app.tandem.state.ClipboardHistorySource
import app.tandem.state.SharedStorage
import app.tandem.state.Transfer
import app.tandem.state.TransferSource
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

data class ShareUiState(
    val link: String = "",
    val transfers: List<Transfer> = emptyList(),
    val storage: SharedStorage? = null,
    val autoSyncClipboard: Boolean = true,
    val linkValid: Boolean = false,
)

@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val transfers: TransferSource,
        private val clipboard: ClipboardHistorySource,
        private val deviceManager: DeviceManager,
        private val clipboardSync: ClipboardSync,
    ) : ViewModel() {
        private val link = MutableStateFlow(DEFAULT_LINK)
        private val messages = Channel<String>(Channel.BUFFERED)

        /** One-off snackbar text. */
        val messageFlow: Flow<String> = messages.receiveAsFlow()

        val state: StateFlow<ShareUiState> =
            combine(link, transfers.transfers, transfers.storage, clipboard.autoSync) { text, list, storage, auto ->
                ShareUiState(
                    link = text,
                    transfers = list,
                    storage = storage,
                    autoSyncClipboard = auto,
                    linkValid = isLinkLike(text),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareUiState())

        fun onLinkChange(text: String) {
            link.value = text
        }

        fun sendLink() {
            val text = link.value.trim()
            if (!isLinkLike(text)) {
                emit(R.string.share_link_invalid)
                return
            }
            viewModelScope.launch {
                val sent = transfers.sendLink(text)
                messages.send(context.getString(if (sent) R.string.share_link_sent else R.string.share_no_device))
            }
        }

        fun cancel(id: String) {
            transfers.cancel(id)
        }

        /** Sends the phone clipboard to every connected, paired device. */
        fun sendClipboard() {
            viewModelScope.launch {
                val targets = deviceManager.deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
                messages.send(clipboardSync.sendLocal(targets).describe())
            }
        }

        fun setStorageEnabled(enabled: Boolean) {
            transfers.setStorageEnabled(enabled)
        }

        fun addSharedFolder(name: String) = transfers.addSharedFolder(name)

        fun removeSharedFolder(name: String) = transfers.removeSharedFolder(name)

        fun setAutoSyncClipboard(enabled: Boolean) {
            clipboard.setAutoSync(enabled)
        }

        private fun emit(
            @StringRes id: Int,
        ) {
            viewModelScope.launch { messages.send(context.getString(id)) }
        }

        companion object {
            /** Empty, so that nothing is sent to the laptop until the user types a link. */
            const val DEFAULT_LINK = ""

            /** Minimal check: no spaces and a dot in the host part. Full URL parsing is the plugin's job. */
            fun isLinkLike(text: String): Boolean =
                text.isNotBlank() && !text.any { it.isWhitespace() } && text.contains('.')
        }
    }
