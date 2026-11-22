// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body of `kdeconnect.share.request`. A packet carries exactly one of: a URL, a text, or a file
 * (its payload follows on the payload socket, see [NetworkPacket.payloadSize]).
 */
sealed interface ShareRequestBody {
    data class Url(val url: String) : ShareRequestBody

    data class Text(val text: String) : ShareRequestBody

    /**
     * @property filename as the sender named it. Never use it as a path; sanitize it first.
     * @property numberOfFiles size of the batch this file belongs to.
     * @property totalPayloadSize summed size of the batch, or null when unknown.
     */
    data class File(
        val filename: String,
        val open: Boolean = false,
        val lastModified: Long? = null,
        val creationTime: Long? = null,
        val numberOfFiles: Int = 1,
        val totalPayloadSize: Long? = null,
    ) : ShareRequestBody

    fun toJson(): JsonObject =
        when (this) {
            is Url -> buildJsonObject { put("url", url) }
            is Text -> buildJsonObject { put("text", text) }
            is File ->
                buildJsonObject {
                    put("filename", filename)
                    put("open", open)
                    lastModified?.let { put("lastModified", it) }
                    creationTime?.let { put("creationTime", it) }
                    put("numberOfFiles", numberOfFiles)
                    totalPayloadSize?.let { put("totalPayloadSize", it) }
                }
        }

    /** Builds the packet. Pass a payload size and port only for [File]. */
    fun toPacket(
        id: Long = System.currentTimeMillis(),
        payloadSize: Long? = null,
        payloadPort: Int? = null,
    ): NetworkPacket {
        require(this is File || payloadSize == null) { "only a file carries a payload" }
        return NetworkPacket(id, PacketTypes.SHARE_REQUEST, toJson(), payloadSize, payloadPort)
    }

    companion object {
        /** Returns null when the body has none of url, text or filename. Empty strings count as absent. */
        fun parse(body: JsonObject): ShareRequestBody? {
            body.getString("url")?.takeIf { it.isNotEmpty() }?.let { return Url(it) }
            body.getString("text")?.takeIf { it.isNotEmpty() }?.let { return Text(it) }
            val filename = body.getString("filename")?.takeIf { it.isNotEmpty() } ?: return null
            return File(
                filename = filename,
                open = body.getBoolean("open") ?: false,
                lastModified = body.getLong("lastModified"),
                creationTime = body.getLong("creationTime"),
                numberOfFiles = body.getInt("numberOfFiles")?.takeIf { it >= 1 } ?: 1,
                totalPayloadSize = body.getLong("totalPayloadSize"),
            )
        }
    }
}

/** Body of `kdeconnect.share.request.update`: the batch grew. Sent after the first file of a batch. */
data class ShareUpdateBody(val numberOfFiles: Int, val totalPayloadSize: Long) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("numberOfFiles", numberOfFiles)
            put("totalPayloadSize", totalPayloadSize)
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.SHARE_REQUEST_UPDATE, toJson())

    companion object {
        fun parse(body: JsonObject): ShareUpdateBody? {
            val count = body.getInt("numberOfFiles")?.takeIf { it >= 1 } ?: return null
            val total = body.getLong("totalPayloadSize") ?: return null
            return ShareUpdateBody(count, total)
        }
    }
}
