// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.serialization.json.JsonObject

/**
 * `kdeconnect.findmyphone.request` has an empty body. Received, it asks this phone to ring (a second one
 * stops the ring). Sent, it asks the desktop's "find this device" plugin to ring the laptop.
 */
object FindMyPhoneRequest {
    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.FINDMYPHONE_REQUEST, JsonObject(emptyMap()))
}
