// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What happened to a call, as the desktop's `event` field spells it. */
enum class CallEvent(val wire: String) {
    Ringing("ringing"),
    Talking("talking"),
    MissedCall("missedCall"),
}

/**
 * Body of `kdeconnect.telephony`, sent to the desktop. With [cancel] set the desktop closes the
 * notification it showed for [event]. [thumbnailBase64] is a small contact photo, or null.
 */
data class TelephonyBody(
    val event: CallEvent,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val thumbnailBase64: String? = null,
    val cancel: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("event", event.wire)
            phoneNumber?.let { put("phoneNumber", it) }
            contactName?.let { put("contactName", it) }
            thumbnailBase64?.let { put("phoneThumbnail", it) }
            if (cancel) put("isCancel", true)
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.TELEPHONY, toJson())
}

/** Body of `kdeconnect.telephony.request_mute` from the desktop: `{action: "mute"}`. */
object TelephonyMuteRequest {
    fun isMute(body: JsonObject): Boolean = body.getString("action") == "mute"
}
