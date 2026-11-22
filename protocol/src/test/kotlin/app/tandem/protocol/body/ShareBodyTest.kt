// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareBodyTest {
    @Test
    fun urlBodyParses() {
        val body = buildJsonObject { put("url", "https://kde.org") }
        assertEquals(ShareRequestBody.Url("https://kde.org"), ShareRequestBody.parse(body))
    }

    @Test
    fun textBodyParses() {
        val body = buildJsonObject { put("text", "hello") }
        assertEquals(ShareRequestBody.Text("hello"), ShareRequestBody.parse(body))
    }

    @Test
    fun emptyUrlAndTextAreIgnored() {
        assertNull(ShareRequestBody.parse(buildJsonObject { put("url", "") }))
        assertNull(ShareRequestBody.parse(buildJsonObject { put("text", "") }))
        assertNull(ShareRequestBody.parse(JsonObject(emptyMap())))
    }

    @Test
    fun fileBodyReadsOptionalFieldsAndNumericStrings() {
        val body =
            buildJsonObject {
                put("filename", "a.jpg")
                put("open", JsonPrimitive("true"))
                put("lastModified", JsonPrimitive("1732030000000"))
                put("numberOfFiles", 3)
                put("totalPayloadSize", 4200L)
            }
        val parsed = ShareRequestBody.parse(body) as ShareRequestBody.File
        assertEquals("a.jpg", parsed.filename)
        assertTrue(parsed.open)
        assertEquals(1732030000000L, parsed.lastModified)
        assertEquals(3, parsed.numberOfFiles)
        assertEquals(4200L, parsed.totalPayloadSize)
    }

    @Test
    fun fileDefaultsWhenOptionalFieldsAreMissing() {
        val parsed = ShareRequestBody.parse(buildJsonObject { put("filename", "x") }) as ShareRequestBody.File
        assertFalse(parsed.open)
        assertEquals(1, parsed.numberOfFiles)
        assertNull(parsed.totalPayloadSize)
        assertNull(parsed.lastModified)
    }

    @Test
    fun fileToPacketCarriesPayloadFieldsThroughTheCodec() {
        val file = ShareRequestBody.File("a.jpg", numberOfFiles = 2, totalPayloadSize = 10)
        val packet = file.toPacket(id = 7, payloadSize = 4, payloadPort = 1739)
        assertEquals(PacketTypes.SHARE_REQUEST, packet.type)
        val line = String(PacketCodec.encode(packet), Charsets.UTF_8).trimEnd('\n')
        val decoded = PacketCodec.decode(line)
        assertEquals(file, ShareRequestBody.parse(decoded.body))
        assertEquals(4L, decoded.payloadSize)
        assertEquals(1739, decoded.payloadPort)
    }

    @Test
    fun textAndUrlPacketsHaveNoPayload() {
        val packet = ShareRequestBody.Url("https://kde.org").toPacket(id = 1)
        assertNull(packet.payloadSize)
        assertFalse(String(PacketCodec.encode(packet), Charsets.UTF_8).contains("payload"))
    }

    @Test
    fun textCannotCarryPayload() {
        assertFailsWith<IllegalArgumentException> {
            ShareRequestBody.Text("hi").toPacket(payloadSize = 1, payloadPort = 1739)
        }
    }

    @Test
    fun updateRoundTrips() {
        val packet = ShareUpdateBody(4, 99L).toPacket(id = 2)
        assertEquals(PacketTypes.SHARE_REQUEST_UPDATE, packet.type)
        assertEquals(ShareUpdateBody(4, 99L), ShareUpdateBody.parse(packet.body))
        assertNull(ShareUpdateBody.parse(buildJsonObject { put("numberOfFiles", 0) }))
    }
}
