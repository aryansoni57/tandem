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

class IdentityTest {
    private val ourId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val peerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val local =
        Identity(
            deviceId = ourId,
            deviceName = "Tandem Phone",
            deviceType = DeviceType.PHONE,
            incomingCapabilities = setOf("kdeconnect.ping", "kdeconnect.battery"),
            outgoingCapabilities = setOf("kdeconnect.battery"),
        )

    private fun obj(vararg e: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*e))

    @Test
    fun idValidatorAcceptsUuidHexAndRejectsOthers() {
        assertTrue(DeviceIdValidator.isValid(ourId))
        assertTrue(DeviceIdValidator.isValid("a".repeat(38)))
        assertFalse(DeviceIdValidator.isValid("a".repeat(31)))
        assertFalse(DeviceIdValidator.isValid("a".repeat(39)))
        assertFalse(DeviceIdValidator.isValid("aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa"))
        assertFalse(DeviceIdValidator.isValid(null))
    }

    @Test
    fun nameFilterStripsPunctuationAndCapsLength() {
        assertEquals("Bobs laptop", DeviceNameFilter.filter("Bob's laptop!"))
        assertEquals("x".repeat(32), DeviceNameFilter.filter("x".repeat(40)))
        assertEquals("a b", DeviceNameFilter.filter("a (b)"))
    }

    @Test
    fun udpPacketCarriesFullIdentityAndTcpPort() {
        val packet = IdentityPackets.toUdp(local, tcpPort = 1716, id = 5)
        assertEquals("kdeconnect.identity", packet.type)
        assertEquals(1716, packet.body.getInt("tcpPort"))
        assertEquals("Tandem Phone", packet.body.getString("deviceName"))
        assertEquals(8, packet.body.getInt("protocolVersion"))
        assertFalse(packet.hasPayload)
    }

    @Test
    fun minimalDesktopUdpPacketIsAccepted() {
        val packet = NetworkPacket(1, "kdeconnect.identity", obj("deviceId" to JsonPrimitive(peerId)))
        val parsed = IdentityPackets.parseDiscovery(packet)
        assertEquals(peerId, parsed.deviceId)
        assertNull(parsed.tcpPort)
    }

    @Test
    fun invalidDeviceIdInDiscoveryIsRejected() {
        val packet = NetworkPacket(1, "kdeconnect.identity", obj("deviceId" to JsonPrimitive("short")))
        assertFailsWith<IdentityException> { IdentityPackets.parseDiscovery(packet) }
    }

    @Test
    fun wrongPacketTypeIsRejected() {
        val packet = NetworkPacket(1, "kdeconnect.ping", obj("deviceId" to JsonPrimitive(peerId)))
        assertFailsWith<IdentityException> { IdentityPackets.parseDiscovery(packet) }
    }

    @Test
    fun connectionPacketCarriesTargetFields() {
        val packet = IdentityPackets.toConnection(local, targetDeviceId = peerId, id = 2)
        assertEquals(peerId, packet.body.getString("targetDeviceId"))
        assertEquals(8, packet.body.getInt("targetProtocolVersion"))
    }

    @Test
    fun connectionPacketForAnotherDeviceIsRejected() {
        val packet = IdentityPackets.toConnection(local, targetDeviceId = peerId)
        // The packet targets peerId, so a receiver that is not peerId must reject it.
        assertFailsWith<IdentityException> { IdentityPackets.parseConnection(packet, ourDeviceId = ourId) }
        IdentityPackets.parseConnection(packet, ourDeviceId = peerId)
    }

    @Test
    fun targetProtocolVersionMismatchIsRejected() {
        val packet =
            NetworkPacket(
                1,
                "kdeconnect.identity",
                obj(
                    "deviceId" to JsonPrimitive(peerId),
                    "targetDeviceId" to JsonPrimitive(ourId),
                    "targetProtocolVersion" to JsonPrimitive("7"),
                ),
            )
        assertFailsWith<IdentityException> { IdentityPackets.parseConnection(packet, ourId) }
    }

    @Test
    fun secureIdentityRoundTripsCapabilities() {
        val parsed = IdentityPackets.parseSecure(IdentityPackets.toSecure(local, id = 3))
        assertEquals(ourId, parsed.deviceId)
        assertEquals(DeviceType.PHONE, parsed.deviceType)
        assertEquals(setOf("kdeconnect.ping", "kdeconnect.battery"), parsed.incomingCapabilities)
        assertEquals(setOf("kdeconnect.battery"), parsed.outgoingCapabilities)
    }

    @Test
    fun secureIdentityRequiresAllFields() {
        val missingType =
            NetworkPacket(
                1,
                "kdeconnect.identity",
                obj(
                    "deviceId" to JsonPrimitive(peerId),
                    "deviceName" to JsonPrimitive("Desk"),
                    "protocolVersion" to JsonPrimitive(8),
                ),
            )
        assertFailsWith<IdentityException> { IdentityPackets.parseSecure(missingType) }
    }

    @Test
    fun protocolDowngradeAndVersionSupport() {
        assertTrue(IdentityPackets.isProtocolDowngrade(storedVersion = 8, peerVersion = 7))
        assertFalse(IdentityPackets.isProtocolDowngrade(storedVersion = 8, peerVersion = 8))
        assertTrue(IdentityPackets.isSupported(8))
        assertFalse(IdentityPackets.isSupported(7))
        assertFalse(IdentityPackets.isSupported(null))
    }
}
