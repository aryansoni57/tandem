// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginBodiesTest {
    private fun obj(text: String): JsonObject = PacketCodec.decode(text).body

    @Test
    fun pingWithoutMessageIsAnEmptyBody() {
        val packet = PingBody().toPacket(id = 7)
        assertEquals(PacketTypes.PING, packet.type)
        assertEquals(7L, packet.id)
        assertEquals(JsonObject(emptyMap()), packet.body)
        assertNull(PingBody.parse(packet.body).message)
    }

    @Test
    fun pingMessageRoundTripsAndEmptyStringIsNoMessage() {
        val withText = PingBody("hi there")
        assertEquals("hi there", PingBody.parse(withText.toJson()).message)
        assertNull(PingBody.parse(obj("""{"id":1,"type":"kdeconnect.ping","body":{"message":""}}""")).message)
    }

    @Test
    fun batteryBodyWritesTheThreeWireFields() {
        val json = BatteryBody(currentCharge = 64, isCharging = true, thresholdEvent = 1).toJson()
        assertEquals(64, json["currentCharge"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", json["isCharging"]!!.jsonPrimitive.content)
        assertEquals(1, json["thresholdEvent"]!!.jsonPrimitive.content.toInt())
        assertEquals(PacketTypes.BATTERY, BatteryBody(50, false).toPacket().type)
    }

    @Test
    fun batteryBodyParsesNumericStringsAndDefaultsThresholdToNone() {
        val parsed =
            BatteryBody.parse(
                obj("""{"id":1,"type":"kdeconnect.battery","body":{"currentCharge":"42","isCharging":"false"}}"""),
            )
        assertEquals(BatteryBody(42, false, BatteryBody.THRESHOLD_NONE), parsed)
        assertFalse(parsed!!.isLowEvent)
    }

    @Test
    fun batteryBodyRejectsMissingOrOutOfRangeCharge() {
        assertNull(BatteryBody.parse(obj("""{"id":1,"type":"t","body":{"isCharging":true}}""")))
        assertNull(BatteryBody.parse(obj("""{"id":1,"type":"t","body":{"currentCharge":-1,"isCharging":true}}""")))
        assertNull(BatteryBody.parse(obj("""{"id":1,"type":"t","body":{"currentCharge":101,"isCharging":true}}""")))
        assertNull(BatteryBody.parse(obj("""{"id":1,"type":"t","body":{"currentCharge":50}}""")))
    }

    @Test
    fun lowThresholdEventIsRecognised() {
        val body =
            BatteryBody.parse(
                obj("""{"id":1,"type":"t","body":{"currentCharge":15,"isCharging":false,"thresholdEvent":1}}"""),
            )
        assertTrue(body!!.isLowEvent)
    }

    @Test
    fun batteryBodyRejectsOutOfRangeConstruction() {
        val failed = runCatching { BatteryBody(101, false) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun batteryRequestAsksForStatus() {
        val packet = BatteryRequestBody.toPacket(id = 3)
        assertEquals(PacketTypes.BATTERY_REQUEST, packet.type)
        assertEquals(JsonPrimitive(true), packet.body["request"])
    }

    @Test
    fun clipboardBodyRoundTrips() {
        val packet = ClipboardBody("copied text").toPacket(id = 9)
        assertEquals(PacketTypes.CLIPBOARD, packet.type)
        assertEquals("copied text", ClipboardBody.parse(packet.body)?.content)
        assertNull(ClipboardBody.parse(JsonObject(emptyMap())))
    }

    @Test
    fun clipboardConnectNeedsContentAndTimestamp() {
        val packet = ClipboardConnectBody("clip", 1_700_000_000_123L).toPacket(id = 1)
        assertEquals(PacketTypes.CLIPBOARD_CONNECT, packet.type)
        assertEquals(ClipboardConnectBody("clip", 1_700_000_000_123L), ClipboardConnectBody.parse(packet.body))
        assertNull(ClipboardConnectBody.parse(obj("""{"id":1,"type":"t","body":{"content":"x"}}""")))
        assertNull(ClipboardConnectBody.parse(obj("""{"id":1,"type":"t","body":{"timestamp":5}}""")))
    }

    @Test
    fun clipboardConnectAcceptsTimestampAsNumericString() {
        val parsed = ClipboardConnectBody.parse(obj("""{"id":1,"type":"t","body":{"content":"x","timestamp":"250"}}"""))
        assertEquals(250L, parsed?.timestamp)
    }

    @Test
    fun packetsEncodeToOneLineAndDecodeBack() {
        val packet = BatteryBody(80, true).toPacket(id = 11)
        val bytes = PacketCodec.encode(packet).toString(Charsets.UTF_8)
        assertEquals(1, bytes.count { it == '\n' })
        val decoded = PacketCodec.decode(bytes.trimEnd('\n'))
        assertEquals(BatteryBody.parse(decoded.body), BatteryBody(80, true))
    }
}
