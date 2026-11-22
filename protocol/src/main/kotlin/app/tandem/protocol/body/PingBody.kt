// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Body of `kdeconnect.ping`. The message is optional. */
data class PingBody(val message: String? = null) {
    fun toJson(): JsonObject =
        buildJsonObject {
            if (message != null) put("message", message)
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket = NetworkPacket(id, PacketTypes.PING, toJson())

    companion object {
        fun parse(body: JsonObject): PingBody = PingBody(body.getString("message")?.takeIf { it.isNotEmpty() })
    }
}
