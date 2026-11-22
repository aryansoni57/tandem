// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins

import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginContext
import app.tandem.protocol.NetworkPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/** A PluginContext whose sends are recorded. [peerIncoming] decides what the send gate lets through. */
class FakeContext(
    peerIncoming: Set<String>,
    deviceId: String = DEVICE,
) {
    val sent = mutableListOf<NetworkPacket>()

    val context: PluginContext =
        PluginContext(
            deviceId = deviceId,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            peerIncoming = { peerIncoming },
            sender = { packet ->
                sent += packet
                true
            },
        )

    companion object {
        const val DEVICE = "0123456789abcdef0123456789abcdef"
    }
}

/** Records packets sent to devices by id. Accepts a send only when [accept] says so. */
class FakePeerSender(private val accept: (String) -> Boolean = { true }) : PeerSender {
    val sent = mutableListOf<Pair<String, NetworkPacket>>()

    override suspend fun sendPacket(
        deviceId: String,
        packet: NetworkPacket,
    ): Boolean {
        if (!accept(deviceId)) return false
        sent += deviceId to packet
        return true
    }
}
