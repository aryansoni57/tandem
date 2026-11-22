// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

/** What one plugin contributes to our identity and how it gets loaded for a device. */
data class CapabilitySpec(
    val id: String,
    val incoming: Set<String>,
    val outgoing: Set<String>,
    val enabled: Boolean = true,
    val minSdk: Int = 26,
    val listenToUnpaired: Boolean = false,
)

data class Advertised(val incoming: Set<String>, val outgoing: Set<String>)

object Capabilities {
    /** Packet types that are always allowed, whether or not the peer advertised them. */
    val SEND_GATE_EXEMPT: Set<String> = PacketTypes.UNPAIRED_ALLOWED

    /** Union of enabled plugins whose minSdk the device meets. */
    fun advertised(
        specs: List<CapabilitySpec>,
        deviceSdk: Int,
    ): Advertised {
        val usable = specs.filter { it.enabled && deviceSdk >= it.minSdk }
        return Advertised(
            incoming = usable.flatMap { it.incoming }.toSet(),
            outgoing = usable.flatMap { it.outgoing }.toSet(),
        )
    }

    /**
     * Per-device load rule: load plugin P for device D only if the capabilities overlap in
     * either direction, and D is paired or P listens to unpaired devices.
     */
    fun shouldLoad(
        plugin: CapabilitySpec,
        peerIncoming: Set<String>,
        peerOutgoing: Set<String>,
        paired: Boolean,
    ): Boolean {
        val overlap = peerOutgoing.any { it in plugin.incoming } || peerIncoming.any { it in plugin.outgoing }
        return overlap && (paired || plugin.listenToUnpaired)
    }

    /** Send gate: only send a type the peer advertised as incoming (identity and pair are exempt). */
    fun canSend(
        type: String,
        peerIncoming: Set<String>,
    ): Boolean = type in SEND_GATE_EXEMPT || type in peerIncoming
}
