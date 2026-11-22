// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getInt
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The packet version we send and accept. Version 1 lacked a field and is not compatible. */
const val SMS_PACKET_VERSION = 2

/**
 * One text message as the desktop wants it. [type] is Android's `Telephony.Sms.MESSAGE_TYPE_*` (1 received,
 * 2 sent). [addresses] are the other parties, never our own number. [event] is a bit set: 1 text, 2 several
 * recipients. [id] is any value that is unique per message.
 */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val addresses: List<String>,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean,
    val subId: Int? = null,
) {
    val event: Int get() = EVENT_TEXT or (if (addresses.size > 1) EVENT_MULTI_TARGET else 0)

    fun toJson(): JsonObject =
        buildJsonObject {
            put("event", event)
            put("body", body)
            put("addresses", buildJsonArray { addresses.forEach { add(buildJsonObject { put("address", it) }) } })
            // The date travels as a string of milliseconds, as the reference client sends it.
            put("date", date.toString())
            put("type", type.toString())
            put("thread_id", threadId)
            put("_id", id)
            put("read", read)
            subId?.let { put("sub_id", it) }
        }

    companion object {
        const val EVENT_TEXT = 0x1
        const val EVENT_MULTI_TARGET = 0x2
        const val TYPE_RECEIVED = 1
        const val TYPE_SENT = 2
    }
}

/** `kdeconnect.sms.messages`: a batch of messages pushed to the desktop. */
data class SmsMessagesBody(val messages: List<SmsMessage>) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("version", SMS_PACKET_VERSION)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.SMS_MESSAGES, toJson())
}

/** `kdeconnect.sms.request_conversation`: the messages of one thread, newest first. */
data class SmsConversationRequest(
    val threadId: Long,
    /** Only messages older than this time (ms), or null for the newest. */
    val rangeStartMs: Long?,
    /** At most this many, or null for all. */
    val limit: Int?,
) {
    companion object {
        fun parse(body: JsonObject): SmsConversationRequest? {
            val thread = body.getLong("threadID") ?: return null
            return SmsConversationRequest(thread, body.getLong("rangeStartTimestamp"), body.getInt("numberToRequest"))
        }
    }
}

/**
 * `kdeconnect.sms.request`: the desktop asks the phone to send a text. Attachments are not supported, so a
 * request that has any is flagged with [hasAttachments] and carries no body text to send alone.
 */
data class SmsSendRequest(
    val addresses: List<String>,
    val body: String,
    val subId: Int?,
    val hasAttachments: Boolean,
) {
    companion object {
        fun parse(body: JsonObject): SmsSendRequest? {
            val listed =
                (body["addresses"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.getString("address")) }.orEmpty()
            // Old desktops sent a single phoneNumber instead of the addresses list.
            val addresses = listed.ifEmpty { listOfNotNull(body.getString("phoneNumber")) }.filter { it.isNotBlank() }
            if (addresses.isEmpty()) return null
            val attachments = (body["attachments"] as? JsonArray)?.isNotEmpty() == true
            return SmsSendRequest(
                addresses = addresses,
                body = body.getString("messageBody").orEmpty(),
                subId = body.getInt("subID") ?: body.getInt("sub_id"),
                hasAttachments = attachments,
            )
        }
    }
}
