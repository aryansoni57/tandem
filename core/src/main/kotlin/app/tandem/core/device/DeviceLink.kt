// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import app.tandem.protocol.NetworkPacket
import app.tandem.transport.PeerSession
import kotlinx.coroutines.channels.ClosedSendChannelException

/** One live, identity-verified TLS connection to a device. */
class DeviceLink(val session: PeerSession) {
    val deviceId: String get() = session.peer.deviceId
    val certDer: ByteArray get() = session.peerCertDer
    val isOpen: Boolean get() = session.channel.isOpen

    /** Silently drops the packet when the link has closed in the meantime. */
    suspend fun send(packet: NetworkPacket) {
        try {
            session.channel.send(packet)
        } catch (_: ClosedSendChannelException) {
            // The link closed while we were sending. Nothing to report.
        }
    }

    fun close() {
        session.channel.close()
    }
}
