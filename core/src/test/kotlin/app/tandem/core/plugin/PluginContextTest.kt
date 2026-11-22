// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContextTest {
    private val sent = mutableListOf<NetworkPacket>()

    private fun context(peerIncoming: Set<String>) =
        PluginContext(
            deviceId = "dev",
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            peerIncoming = { peerIncoming },
            sender = { packet ->
                sent += packet
                true
            },
        )

    private fun packet(type: String) = NetworkPacket(1, type, JsonObject(emptyMap()))

    @Test
    fun sendIsDroppedWhenPeerDidNotAdvertiseTheType() {
        val accepted = runBlocking { context(emptySet()).send(packet(PacketTypes.PING)) }
        assertFalse(accepted)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sendGoesThroughWhenPeerAdvertisedTheType() {
        val accepted = runBlocking { context(setOf(PacketTypes.PING)).send(packet(PacketTypes.PING)) }
        assertTrue(accepted)
        assertEquals(listOf(PacketTypes.PING), sent.map { it.type })
    }

    @Test
    fun identityAndPairAreExemptFromTheGate() {
        runBlocking {
            context(emptySet()).send(packet(PacketTypes.IDENTITY))
            context(emptySet()).send(packet(PacketTypes.PAIR))
        }
        assertEquals(listOf(PacketTypes.IDENTITY, PacketTypes.PAIR), sent.map { it.type })
    }
}
