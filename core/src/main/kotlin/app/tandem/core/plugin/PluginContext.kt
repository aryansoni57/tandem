// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.Capabilities
import app.tandem.protocol.NetworkPacket
import kotlinx.coroutines.CoroutineScope

/**
 * What a plugin may use: its device, a scope tied to the device, and a send function.
 *
 * [send] applies the send gate: a packet type the peer did not advertise as incoming is dropped.
 * [peerIncoming] is read at send time, so it follows the live link.
 */
class PluginContext(
    val deviceId: String,
    val scope: CoroutineScope,
    private val peerIncoming: () -> Set<String>,
    private val sender: suspend (NetworkPacket) -> Boolean,
) {
    /** Returns false when the peer does not accept the type, or when no link is open. */
    suspend fun send(packet: NetworkPacket): Boolean {
        if (!Capabilities.canSend(packet.type, peerIncoming())) return false
        return sender(packet)
    }
}
