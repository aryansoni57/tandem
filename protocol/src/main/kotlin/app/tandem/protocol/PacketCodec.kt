// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class PacketException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Wire format: one UTF-8 JSON object per line, terminated by a single `\n`.
 * Decoding is lenient about unknown keys and about numeric strings, as peers differ.
 */
object PacketCodec {
    const val MAX_PACKET_SIZE: Int = 32 * 1024 * 1024
    const val MAX_IDENTITY_PACKET_SIZE: Int = 512 * 1024

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
        }

    /** Serializes [packet] to one line, including the trailing newline. */
    fun encode(packet: NetworkPacket): ByteArray {
        require(packet.type.isNotEmpty()) { "packet type must not be empty" }
        val obj =
            buildJsonObject {
                put("id", packet.id)
                put("type", packet.type)
                put("body", packet.body)
                if (packet.payloadSize != null) {
                    val port = packet.payloadPort ?: throw PacketException("payloadSize without payloadTransferInfo")
                    put("payloadSize", packet.payloadSize)
                    putJsonObject("payloadTransferInfo") { put("port", port) }
                }
            }
        return (json.encodeToString(JsonObject.serializer(), obj) + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Parses one line (without its newline). Throws [PacketException] on anything malformed. */
    fun decode(line: String): NetworkPacket {
        val root = parseRoot(line)
        val type = root["type"]?.stringOrNull() ?: throw PacketException("missing type")
        val body = root["body"] as? JsonObject ?: throw PacketException("missing body")
        val id = root["id"]?.longOrNull() ?: 0L
        val (payloadSize, payloadPort) = parsePayload(root)
        return NetworkPacket(id, type, body, payloadSize, payloadPort)
    }

    private fun parseRoot(line: String): JsonObject {
        if (line.isBlank()) throw PacketException("empty line")
        return try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: SerializationException) {
            throw PacketException("not a JSON object: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // jsonObject throws this when the root is an array or a primitive.
            throw PacketException("not a JSON object: ${e.message}", e)
        }
    }

    /** The payload size and port, or null for both when the packet has no payload. */
    private fun parsePayload(root: JsonObject): Pair<Long?, Int?> {
        val sizeElement = root["payloadSize"]
        if (sizeElement == null || sizeElement is JsonNull) return null to null
        val size = sizeElement.longOrNull() ?: throw PacketException("payloadSize is not a number")
        val info =
            root["payloadTransferInfo"] as? JsonObject
                ?: throw PacketException("payloadSize without payloadTransferInfo")
        val port = info["port"]?.intOrNull() ?: throw PacketException("payloadTransferInfo without port")
        return size to port
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Accepts a JSON number or a numeric string. */
    private fun JsonElement.longOrNull(): Long? =
        (this as? JsonPrimitive)?.takeIf {
            it !is JsonNull
        }?.content?.toLongOrNull()

    private fun JsonElement.intOrNull(): Int? = longOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
