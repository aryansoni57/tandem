// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.PacketTypes

object NotificationsDescriptor {
    val INCOMING =
        setOf(PacketTypes.NOTIFICATION_REQUEST, PacketTypes.NOTIFICATION_REPLY, PacketTypes.NOTIFICATION_ACTION)

    fun create(
        bridge: NotificationBridge,
        source: NotificationSource,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "notifications",
            incoming = INCOMING,
            outgoing = setOf(PacketTypes.NOTIFICATION),
            requiredPermissions = emptyList(),
            factory = { NotificationsPlugin(it, bridge, source) },
        )
}
