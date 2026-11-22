// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.ping

import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.PingBody

/** Shows incoming pings. Sending a ping is a user action handled by the device screen. */
class PingPlugin(
    private val context: PluginContext,
    private val sink: PingSink,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.PING) return false
        sink.onPing(context.deviceId, PingBody.parse(packet.body).message)
        return true
    }
}

/** Where an incoming ping goes. The Android implementation posts a notification. */
fun interface PingSink {
    fun onPing(
        deviceId: String,
        message: String?,
    )
}
