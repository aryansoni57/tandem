// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.NotificationActionBody
import app.tandem.protocol.body.NotificationReplyBody
import app.tandem.protocol.body.NotificationRequestBody
import kotlinx.coroutines.launch

/**
 * Answers the desktop: send the current list, dismiss one, reply to one, press an action. The send
 * for new and removed notifications comes from [NotificationBridge], driven by the listener.
 */
class NotificationsPlugin(
    private val context: PluginContext,
    private val bridge: NotificationBridge,
    private val source: NotificationSource,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        when (packet.type) {
            PacketTypes.NOTIFICATION_REQUEST -> onRequest(packet)
            PacketTypes.NOTIFICATION_REPLY ->
                NotificationReplyBody.parse(packet.body)?.let { source.reply(it.requestReplyId, it.message) }
            PacketTypes.NOTIFICATION_ACTION ->
                NotificationActionBody.parse(packet.body)?.let { source.invokeAction(it.key, it.action) }
            else -> return false
        }
        return true
    }

    private fun onRequest(packet: NetworkPacket) {
        when (val request = NotificationRequestBody.parse(packet.body)) {
            NotificationRequestBody.SendAll ->
                // Off the packet loop: this sends packets and icon payloads.
                context.scope.launch { bridge.sendAll(context.deviceId, source.current()) }
            is NotificationRequestBody.Cancel -> source.cancel(request.id)
            null -> Unit
        }
    }
}
