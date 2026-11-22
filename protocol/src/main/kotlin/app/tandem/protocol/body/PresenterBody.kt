// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body of `kdeconnect.presenter`. The desktop shows a red dot over the slides. [Move] shifts the dot by
 * [dx] and [dy] as a fraction of the screen width, and the dot appears on the first packet. [Stop]
 * hides it. The desktop also hides it by itself after half a second without a packet.
 */
sealed interface PresenterBody {
    data class Move(val dx: Float, val dy: Float) : PresenterBody

    data object Stop : PresenterBody

    fun toJson(): JsonObject =
        buildJsonObject {
            when (this@PresenterBody) {
                is Move -> {
                    put("dx", dx.toDouble())
                    put("dy", dy.toDouble())
                }
                Stop -> put("stop", true)
            }
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.PRESENTER, toJson())
}
