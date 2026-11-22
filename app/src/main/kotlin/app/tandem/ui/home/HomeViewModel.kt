// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.R
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import app.tandem.core.device.PeerStateStore
import app.tandem.state.ConnectionSource
import app.tandem.state.NowPlaying
import app.tandem.state.RemoteSource
import app.tandem.state.Transfer
import app.tandem.state.TransferSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Home screen shows. Device, battery and transfers are real or sample as noted in the sources. */
data class HomeUiState(
    val device: DeviceState? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val latencyMs: Int? = null,
    val nowPlaying: NowPlaying? = null,
    val today: List<Transfer> = emptyList(),
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        deviceManager: DeviceManager,
        peerState: PeerStateStore,
        transfers: TransferSource,
        remote: RemoteSource,
        connection: ConnectionSource,
    ) : ViewModel() {
        private val remoteSource = remote
        private val devices = deviceManager.deviceStates
        private val messages = Channel<String>(Channel.BUFFERED)

        val messageFlow: Flow<String> = messages.receiveAsFlow()
        private val paired = combine(devices, peerState.batteries) { list, batteries -> list to batteries }

        val state: StateFlow<HomeUiState> =
            combine(
                paired,
                transfers.today,
                remote.nowPlaying,
                connection.latencyMs,
            ) { (list, batteries), today, nowPlaying, latency ->
                val device = list.firstOrNull { it.paired && it.reachable } ?: list.firstOrNull { it.paired }
                val battery = device?.let { batteries[it.deviceId] }
                HomeUiState(
                    device = device,
                    batteryPercent = battery?.level,
                    charging = battery?.charging == true,
                    latencyMs = latency,
                    nowPlaying = nowPlaying,
                    today = today,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

        fun togglePlayPause() = remoteSource.togglePlayPause()

        /** TODO(phase9): locking goes through the run-command plugin. The sample only reports it. */
        fun lockLaptop() {
            remoteSource.lockLaptop()
            emit(R.string.home_laptop_locked)
        }

        private fun emit(
            @StringRes id: Int,
        ) {
            viewModelScope.launch { messages.send(context.getString(id)) }
        }
    }
