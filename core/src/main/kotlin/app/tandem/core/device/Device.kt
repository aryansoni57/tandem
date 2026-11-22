// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import app.tandem.core.plugin.LoadedPlugins
import app.tandem.protocol.PairingState
import app.tandem.protocol.PairingStateMachine

/** The plugins loaded for [link], created while the device was [paired] (or not). */
class PluginSlot(
    val link: DeviceLink,
    val paired: Boolean,
    val loaded: LoadedPlugins,
)

/** Per-device runtime holder: the live link (if any), the pairing state machine and loaded plugins. */
class Device(
    val deviceId: String,
    trusted: Boolean,
) {
    val machine = PairingStateMachine(if (trusted) PairingState.Paired else PairingState.NotPaired)

    /** The current link. Replaced when a duplicate with the same certificate arrives. */
    var link: DeviceLink? = null

    var plugins: PluginSlot? = null
}
