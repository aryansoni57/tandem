// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketCodecTest {
    private fun decodeLine(line: String) = PacketCodec.decode(line.trimEnd('\n'))

    @Test
    fun roundTripWithoutPayloadOmitsPayloadKeys() {
        val packet = NetworkPacket(42, "kdeconnect.ping", JsonObject(mapOf("message" to JsonPrimitive("hi"))))
        val bytes = PacketCodec.encode(packet)
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue(text.endsWith("\n"), "must be newline terminated")
        assertEquals(1, text.count { it == '\n' }, "exactly one newline")
        assertFalse(text.contains("payloadSize"), "payloadSize must be omitted without a payload")
        assertFalse(text.contains("payloadTransferInfo"))
        assertEquals(packet, decodeLine(text))
    }

    @Test
    fun roundTripWithPayload() {
        val packet = NetworkPacket(7, "kdeconnect.share.request", JsonObject(emptyMap()), 4_200_000L, 1739)
        val text = PacketCodec.encode(packet).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"payloadTransferInfo\":{\"port\":1739}"))
        assertEquals(packet, decodeLine(text))
    }

    @Test
    fun unknownSizeMinusOneIsAllowed() {
        val packet = NetworkPacket(1, "t", JsonObject(emptyMap()), -1L, 1740)
        assertEquals(packet, decodeLine(PacketCodec.encode(packet).toString(Charsets.UTF_8)))
    }

    @Test
    fun payloadSizeWithoutPortIsRejectedOnEncode() {
        val packet = NetworkPacket(1, "t", JsonObject(emptyMap()), 10L, null)
        assertFailsWith<PacketException> { PacketCodec.encode(packet) }
    }

    @Test
    fun payloadSizeWithoutTransferInfoIsRejectedOnDecode() {
        assertFailsWith<PacketException> {
            decodeLine("""{"id":1,"type":"t","body":{},"payloadSize":10}""")
        }
    }

    @Test
    fun numericStringsAreAcceptedForIdAndPayloadSize() {
        val p =
            decodeLine(
                """{"id":"123","type":"t","body":{},"payloadSize":"55","payloadTransferInfo":{"port":"1741"}}""",
            )
        assertEquals(123L, p.id)
        assertEquals(55L, p.payloadSize)
        assertEquals(1741, p.payloadPort)
    }

    @Test
    fun unparseableIdFallsBackToZero() {
        assertEquals(0L, decodeLine("""{"id":"abc","type":"t","body":{}}""").id)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val p = decodeLine("""{"id":1,"type":"t","body":{"x":1},"futureField":[1,2,3]}""")
        assertEquals("t", p.type)
        assertNull(p.payloadSize)
    }

    @Test
    fun missingTypeOrBodyIsRejected() {
        assertFailsWith<PacketException> { decodeLine("""{"id":1,"body":{}}""") }
        assertFailsWith<PacketException> { decodeLine("""{"id":1,"type":"t"}""") }
    }

    @Test
    fun blankAndNonObjectLinesAreRejected() {
        assertFailsWith<PacketException> { decodeLine("   ") }
        assertFailsWith<PacketException> { decodeLine("[1,2]") }
        assertFailsWith<PacketException> { decodeLine("{not json") }
    }

    @Test
    fun largePayloadSizeIsRepresentable() {
        val big = NetworkPacket(1, "t", JsonObject(emptyMap()), 10L * 1024 * 1024, 1739)
        assertEquals(big.payloadSize, decodeLine(PacketCodec.encode(big).toString(Charsets.UTF_8)).payloadSize)
        assertTrue(PacketCodec.MAX_PACKET_SIZE == 32 * 1024 * 1024)
    }
}
