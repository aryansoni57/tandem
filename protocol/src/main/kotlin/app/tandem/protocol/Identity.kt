// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class IdentityException(message: String) : IllegalArgumentException(message)

/**
 * Identity fields as seen in any of the three packet variants. Nullable fields are absent in
 * some variants (for example, the minimal desktop UDP packet has no capabilities).
 */
data class Identity(
    val deviceId: String,
    val deviceName: String? = null,
    val deviceType: DeviceType? = null,
    val protocolVersion: Int? = null,
    val incomingCapabilities: Set<String> = emptySet(),
    val outgoingCapabilities: Set<String> = emptySet(),
    val tcpPort: Int? = null,
    val targetDeviceId: String? = null,
    val targetProtocolVersion: Int? = null,
)

/** Builders and parsers for the identity packet variants (plan section 2.2). */
object IdentityPackets {
    const val PROTOCOL_VERSION = 8
    const val UDP_DISCOVERY_PORT = 1716

    /** UDP broadcast: full identity plus our TCP port. */
    fun toUdp(
        local: Identity,
        tcpPort: Int,
        id: Long = System.currentTimeMillis(),
    ): NetworkPacket = NetworkPacket(id, PacketTypes.IDENTITY, bodyOf(local, withTcpPort = tcpPort))

    /** First plaintext line on an outgoing TCP connection. */
    fun toConnection(
        local: Identity,
        targetDeviceId: String,
        id: Long = System.currentTimeMillis(),
    ): NetworkPacket {
        val body = bodyOf(local).toMutableMap()
        body["targetDeviceId"] = JsonPrimitive(targetDeviceId)
        body["targetProtocolVersion"] = JsonPrimitive(PROTOCOL_VERSION)
        return NetworkPacket(id, PacketTypes.IDENTITY, JsonObject(body))
    }

    /** Secure identity, sent first after TLS in both directions. */
    fun toSecure(
        local: Identity,
        id: Long = System.currentTimeMillis(),
    ): NetworkPacket = NetworkPacket(id, PacketTypes.IDENTITY, bodyOf(local))

    /**
     * Minimal parse used for UDP discovery. Only the packet type and a valid deviceId are
     * required, as the desktop may send a stripped-down packet. Pre-TLS fields are not trusted.
     */
    fun parseDiscovery(packet: NetworkPacket): Identity {
        if (packet.type != PacketTypes.IDENTITY) throw IdentityException("not an identity packet")
        val id = packet.body.getString("deviceId")
        if (!DeviceIdValidator.isValid(id)) throw IdentityException("invalid deviceId")
        return Identity(
            deviceId = id!!,
            deviceName = packet.body.getString("deviceName")?.let(DeviceNameFilter::filter),
            deviceType = DeviceType.fromWire(packet.body.getString("deviceType")),
            protocolVersion = packet.body.getInt("protocolVersion"),
            tcpPort = packet.body.getInt("tcpPort"),
        )
    }

    /**
     * Parses the plaintext connection packet that a desktop sends on a new TCP connection.
     * Throws if the target fields name another device or another protocol version.
     */
    fun parseConnection(
        packet: NetworkPacket,
        ourDeviceId: String,
    ): Identity {
        val identity = parseDiscovery(packet)
        val target = packet.body.getString("targetDeviceId")
        if (target != null && target != ourDeviceId) throw IdentityException("targetDeviceId is not us")
        val targetVersion = packet.body.getInt("targetProtocolVersion")
        if (targetVersion != null && targetVersion != PROTOCOL_VERSION) {
            throw IdentityException("targetProtocolVersion is not $PROTOCOL_VERSION")
        }
        return identity.copy(
            targetDeviceId = target,
            targetProtocolVersion = targetVersion,
            protocolVersion = packet.body.getInt("protocolVersion"),
        )
    }

    /**
     * Parses the secure identity. Every field is required here because this is the only
     * identity that counts. Throws [IdentityException] otherwise.
     */
    fun parseSecure(packet: NetworkPacket): Identity {
        if (packet.type != PacketTypes.IDENTITY) throw IdentityException("not an identity packet")
        val id = packet.body.getString("deviceId")
        if (!DeviceIdValidator.isValid(id)) throw IdentityException("invalid deviceId")
        val name = packet.body.getString("deviceName") ?: throw IdentityException("missing deviceName")
        val type =
            DeviceType.fromWire(packet.body.getString("deviceType"))
                ?: throw IdentityException("unknown deviceType")
        val version = packet.body.getInt("protocolVersion") ?: throw IdentityException("missing protocolVersion")
        return Identity(
            deviceId = id!!,
            deviceName = DeviceNameFilter.filter(name),
            deviceType = type,
            protocolVersion = version,
            incomingCapabilities = packet.body.getStringSet("incomingCapabilities"),
            outgoingCapabilities = packet.body.getStringSet("outgoingCapabilities"),
        )
    }

    /** Refuse a trusted peer whose protocol version went down since pairing. */
    fun isProtocolDowngrade(
        storedVersion: Int,
        peerVersion: Int,
    ): Boolean = peerVersion < storedVersion

    /** Peers below protocol 8 are refused outright. */
    fun isSupported(peerVersion: Int?): Boolean = peerVersion != null && peerVersion >= PROTOCOL_VERSION

    private fun stringArray(values: Set<String>): JsonArray = JsonArray(values.sorted().map { JsonPrimitive(it) })

    private fun bodyOf(
        local: Identity,
        withTcpPort: Int? = null,
    ): JsonObject {
        val map =
            linkedMapOf<String, JsonElement>(
                "deviceId" to JsonPrimitive(local.deviceId),
                "deviceName" to JsonPrimitive(local.deviceName ?: ""),
                "deviceType" to JsonPrimitive((local.deviceType ?: DeviceType.PHONE).wireName),
                "protocolVersion" to JsonPrimitive(PROTOCOL_VERSION),
                "incomingCapabilities" to stringArray(local.incomingCapabilities),
                "outgoingCapabilities" to stringArray(local.outgoingCapabilities),
            )
        if (withTcpPort != null) map["tcpPort"] = JsonPrimitive(withTcpPort)
        return JsonObject(map)
    }
}
