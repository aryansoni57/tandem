// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import app.tandem.protocol.DeviceType

/** Pairing UI state for one device. */
sealed interface PairingUi {
    data object Idle : PairingUi

    /** We sent a request. [verificationKey] is the 8-character code to compare. */
    data class Requested(val verificationKey: String, val expiresAtMs: Long) : PairingUi

    /** The peer sent a request that we can accept or reject. */
    data class Incoming(val verificationKey: String, val expiresAtMs: Long) : PairingUi

    data class Failed(val reason: String) : PairingUi
}

/** Everything the UI shows about one device. */
data class DeviceState(
    val deviceId: String,
    val name: String,
    val type: DeviceType = DeviceType.PHONE,
    val paired: Boolean = false,
    val reachable: Boolean = false,
    val protocolVersion: Int? = null,
    val address: String? = null,
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val certSha256: String? = null,
    val pairing: PairingUi = PairingUi.Idle,
)

/** Raised when a peer asks us to pair. The app shows the notification. */
data class PairingPrompt(val deviceId: String, val deviceName: String, val verificationKey: String)
