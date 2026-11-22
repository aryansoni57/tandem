// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.ping

import app.tandem.plugins.FakeContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.PingBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingPluginTest {
    private val shown = mutableListOf<Pair<String, String?>>()
    private val fake = FakeContext(peerIncoming = setOf(PacketTypes.PING))
    private val plugin = PingPlugin(fake.context) { id, message -> shown += id to message }

    @Test
    fun incomingPingWithMessageIsShown() {
        val packet = PingBody("hi").toPacket(id = 1)
        val handled = runBlocking { plugin.onPacket(packet) }
        assertTrue(handled)
        assertEquals(listOf(FakeContext.DEVICE to "hi"), shown)
    }

    @Test
    fun incomingPingWithoutMessageShowsNullMessage() {
        runBlocking { plugin.onPacket(PingBody().toPacket(id = 2)) }
        assertEquals(listOf(FakeContext.DEVICE to null), shown)
    }

    @Test
    fun otherPacketTypesAreNotHandled() {
        val other = NetworkPacket(3, PacketTypes.BATTERY, JsonObject(emptyMap()))
        assertFalse(runBlocking { plugin.onPacket(other) })
        assertTrue(shown.isEmpty())
    }

    @Test
    fun pingReplyIsNeverSentAutomatically() {
        runBlocking { plugin.onPacket(PingBody("hi").toPacket(id = 4)) }
        assertTrue(fake.sent.isEmpty())
    }
}
