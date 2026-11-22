// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import app.tandem.protocol.NetworkPacket
import java.net.InetAddress

/** A paired device with a live link: where its payloads connect, and what it accepts. */
data class LivePeer(val address: InetAddress, val incoming: Set<String>)

/** Sends a packet to a paired, connected device. Used by the UI and by plugins outside a link. */
interface PeerSender {
    /** Returns false when the device is unpaired, not connected, or does not accept the packet type. */
    suspend fun sendPacket(
        deviceId: String,
        packet: NetworkPacket,
    ): Boolean

    /** The live link of a paired device, or null when it is unpaired or not connected. */
    suspend fun livePeer(deviceId: String): LivePeer? = null
}
