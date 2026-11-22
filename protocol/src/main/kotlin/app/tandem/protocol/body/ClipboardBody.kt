// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Body of `kdeconnect.clipboard`: the new clipboard text. */
data class ClipboardBody(val content: String) {
    fun toJson(): JsonObject = buildJsonObject { put("content", content) }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(
            id,
            PacketTypes.CLIPBOARD,
            toJson(),
        )

    companion object {
        fun parse(body: JsonObject): ClipboardBody? = body.getString("content")?.let(::ClipboardBody)
    }
}

/**
 * Body of `kdeconnect.clipboard.connect`, sent once a link opens. [timestamp] is the sender's time
 * of its last local clipboard change, in milliseconds. Zero means "no change known".
 */
data class ClipboardConnectBody(val content: String, val timestamp: Long) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("content", content)
            put("timestamp", timestamp)
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.CLIPBOARD_CONNECT, toJson())

    companion object {
        fun parse(body: JsonObject): ClipboardConnectBody? {
            val content = body.getString("content") ?: return null
            val timestamp = body.getLong("timestamp") ?: return null
            return ClipboardConnectBody(content, timestamp)
        }
    }
}

/** Body of `kdeconnect.clipboard.file`: a file placed on the clipboard. The bytes follow as a payload. */
data class ClipboardFileBody(val filename: String) {
    fun toJson(): JsonObject = buildJsonObject { put("filename", filename) }

    fun toPacket(
        id: Long = System.currentTimeMillis(),
        payloadSize: Long? = null,
        payloadPort: Int? = null,
    ): NetworkPacket = NetworkPacket(id, PacketTypes.CLIPBOARD_FILE, toJson(), payloadSize, payloadPort)

    companion object {
        fun parse(body: JsonObject): ClipboardFileBody? =
            body.getString("filename")?.takeIf { it.isNotEmpty() }?.let(::ClipboardFileBody)
    }
}
