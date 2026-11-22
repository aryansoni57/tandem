// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body of `kdeconnect.notification` sent to the desktop. [time] is a String of milliseconds, as the
 * reference client sends it. The icon, when there is one, follows as a payload and [payloadHash] is
 * the uppercase MD5 of its bytes, so the desktop can reuse a cached icon.
 */
data class NotificationBody(
    val id: String,
    val appName: String,
    val title: String,
    val text: String,
    val ticker: String,
    val isClearable: Boolean,
    val time: Long,
    val silent: Boolean,
    val requestReplyId: String? = null,
    val actions: List<String> = emptyList(),
    val payloadHash: String? = null,
    val groupName: String? = null,
    val conversation: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("appName", appName)
            put("title", title)
            put("text", text)
            put("ticker", ticker)
            put("isClearable", isClearable)
            put("time", time.toString())
            put("silent", silent)
            requestReplyId?.let { put("requestReplyId", it) }
            if (actions.isNotEmpty()) put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
            payloadHash?.let { put("payloadHash", it) }
            groupName?.let { put("groupName", it) }
            if (conversation) put("conversation", true)
        }

    /** Pass a payload size and port only when the icon bytes follow. */
    fun toPacket(
        id: Long = System.currentTimeMillis(),
        payloadSize: Long? = null,
        payloadPort: Int? = null,
    ): NetworkPacket = NetworkPacket(id, PacketTypes.NOTIFICATION, toJson(), payloadSize, payloadPort)
}

/** Tells the desktop a notification is gone: `{id, isCancel: true}`. */
data class NotificationCancelBody(val id: String) {
    fun toPacket(packetId: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(
            packetId,
            PacketTypes.NOTIFICATION,
            buildJsonObject {
                put("id", id)
                put("isCancel", true)
            },
        )
}

/** Body of `kdeconnect.notification.request` from the desktop. */
sealed interface NotificationRequestBody {
    /** `{request: true}`: send every current notification. */
    data object SendAll : NotificationRequestBody

    /** `{cancel: "<id>"}`: dismiss that notification on the phone. */
    data class Cancel(val id: String) : NotificationRequestBody

    companion object {
        fun parse(body: JsonObject): NotificationRequestBody? {
            body.getString("cancel")?.takeIf { it.isNotEmpty() }?.let { return Cancel(it) }
            return if (body.getBoolean("request") == true) SendAll else null
        }
    }
}

/** Body of `kdeconnect.notification.reply`: a reply typed on the desktop. */
data class NotificationReplyBody(val requestReplyId: String, val message: String) {
    companion object {
        fun parse(body: JsonObject): NotificationReplyBody? {
            val id = body.getString("requestReplyId")?.takeIf { it.isNotEmpty() } ?: return null
            val message = body.getString("message") ?: return null
            return NotificationReplyBody(id, message)
        }
    }
}

/** Body of `kdeconnect.notification.action`: the desktop pressed an action button. */
data class NotificationActionBody(val key: String, val action: String) {
    companion object {
        fun parse(body: JsonObject): NotificationActionBody? {
            val key = body.getString("key")?.takeIf { it.isNotEmpty() } ?: return null
            val action = body.getString("action")?.takeIf { it.isNotEmpty() } ?: return null
            return NotificationActionBody(key, action)
        }
    }
}
