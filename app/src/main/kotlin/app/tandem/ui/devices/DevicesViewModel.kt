// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.R
import app.tandem.core.data.SettingsStore
import app.tandem.core.device.BatteryState
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import app.tandem.core.device.PeerStateStore
import app.tandem.core.service.ConnectionService
import app.tandem.plugins.clipboard.ClipboardSync
import app.tandem.plugins.clipboard.describe
import app.tandem.protocol.body.FindMyPhoneRequest
import app.tandem.protocol.body.PingBody
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deviceManager: DeviceManager,
        private val settings: SettingsStore,
        private val peerState: PeerStateStore,
        private val clipboardSync: ClipboardSync,
    ) : ViewModel() {
        val devices: StateFlow<List<DeviceState>> = deviceManager.deviceStates

        val peerBatteries: StateFlow<Map<String, BatteryState>> = peerState.batteries

        private val statusMessage = MutableStateFlow<String?>(null)

        /** The last result of a ping or clipboard action on the device screen. */
        val message: StateFlow<String?> = statusMessage.asStateFlow()

        /** Device whose pairing sheet is open. Null when the sheet is closed. */
        private val pairingTarget = MutableStateFlow<String?>(null)
        val pairingTargetId: StateFlow<String?> = pairingTarget

        /** One-off snackbar text for the Devices screen. */
        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow: Flow<String> = messages.receiveAsFlow()

        /** Null until the stored preference is read, so the UI does not flash the wrong screen. */
        val onboarded: StateFlow<Boolean?> =
            settings.onboardingDone
                .map<Boolean, Boolean?> { it }
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

        init {
            viewModelScope.launch {
                if (settings.onboardingDone.first()) ConnectionService.start(context)
            }
        }

        fun finishOnboarding() {
            viewModelScope.launch {
                settings.setOnboardingDone()
                ConnectionService.start(context)
            }
        }

        fun openPairing(deviceId: String) {
            pairingTarget.value = deviceId
        }

        fun closePairing() {
            pairingTarget.value = null
        }

        /** Opens the pairing sheet for the first nearby device that is not paired. Returns false when there is none. */
        fun pairNearby(): Boolean {
            val candidate = devices.value.firstOrNull { !it.paired } ?: return false
            pairingTarget.value = candidate.deviceId
            return true
        }

        /** TODO(phase10): ringing the laptop goes through the find-my-phone plugin. The sample only reports it. */
        fun ringLaptop() {
            viewModelScope.launch { messages.send(context.getString(R.string.devices_ringing)) }
        }

        fun requestPairing(deviceId: String) = launchAction { deviceManager.requestPairing(deviceId) }

        fun acceptPairing(deviceId: String) = launchAction { deviceManager.acceptPairing(deviceId) }

        fun rejectPairing(deviceId: String) = launchAction { deviceManager.rejectPairing(deviceId) }

        fun unpair(deviceId: String) = launchAction { deviceManager.unpair(deviceId) }

        fun ping(deviceId: String) =
            launchAction {
                val sent = deviceManager.sendPacket(deviceId, PingBody().toPacket())
                statusMessage.value = if (sent) "Ping sent." else "Not connected to this device."
            }

        /** Asks the laptop to ring. It does so only if its "Find this device" plugin is on. */
        fun ringLaptop(deviceId: String) =
            launchAction {
                val sent = deviceManager.sendPacket(deviceId, FindMyPhoneRequest.toPacket())
                statusMessage.value =
                    if (sent) {
                        "Ring request sent."
                    } else {
                        "This device cannot ring. Turn on Find this device on the laptop."
                    }
            }

        fun sendClipboard(deviceId: String) =
            launchAction {
                statusMessage.value = clipboardSync.sendLocal(listOf(deviceId)).describe()
            }

        /** Pull to refresh: send our identity again so nearby devices reconnect. */
        fun refresh() {
            deviceManager.announce()
        }

        private fun launchAction(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }
    }
