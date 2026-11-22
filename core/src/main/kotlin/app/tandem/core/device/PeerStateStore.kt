// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The battery status a peer reported. */
data class BatteryState(
    val level: Int,
    val charging: Boolean,
    val low: Boolean = false,
)

/** Peer data that plugins publish for the UI. Entries are removed when the plugin is destroyed. */
@Singleton
class PeerStateStore
    @Inject
    constructor() {
        private val battery = MutableStateFlow<Map<String, BatteryState>>(emptyMap())

        val batteries: StateFlow<Map<String, BatteryState>> = battery.asStateFlow()

        fun setBattery(
            deviceId: String,
            state: BatteryState,
        ) {
            battery.update { it + (deviceId to state) }
        }

        fun clearBattery(deviceId: String) {
            battery.update { it - deviceId }
        }
    }
