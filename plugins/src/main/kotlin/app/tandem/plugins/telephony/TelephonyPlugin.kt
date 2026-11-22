// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.telephony

import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.TelephonyMuteRequest

/** Listens for the desktop's "mute" button on the call notification. */
class TelephonyPlugin(private val events: CallEvents) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.TELEPHONY_REQUEST_MUTE) return false
        if (TelephonyMuteRequest.isMute(packet.body)) events.muteRinger()
        return true
    }
}

object TelephonyDescriptor {
    fun create(events: CallEvents): PluginDescriptor =
        PluginDescriptor(
            id = "telephony",
            incoming = setOf(PacketTypes.TELEPHONY_REQUEST_MUTE),
            outgoing = setOf(PacketTypes.TELEPHONY),
            // Without these the phone state broadcast never arrives, so calls cannot be reported.
            requiredPermissions = emptyList(),
            optionalPermissions =
                listOf(
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.READ_CALL_LOG",
                    "android.permission.READ_CONTACTS",
                ),
            factory = { TelephonyPlugin(events) },
        )
}
