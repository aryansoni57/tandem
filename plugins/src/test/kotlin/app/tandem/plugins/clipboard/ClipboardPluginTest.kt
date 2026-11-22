// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.plugins.FakeContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ClipboardBody
import app.tandem.protocol.body.ClipboardConnectBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPluginTest {
    private class FakeSystem(var readValue: String? = null) : SystemClipboard {
        val written = mutableListOf<String>()

        override suspend fun readText(skipSensitive: Boolean): String? = readValue

        override suspend fun writeText(text: String) {
            written += text
        }

        val files = mutableListOf<Pair<String, String>>()

        override suspend fun writeFile(
            uri: String,
            mime: String,
        ) {
            files += uri to mime
        }
    }

    private class FakeHistory : ClipHistory {
        val recorded = mutableListOf<Triple<String, String?, Long>>()

        override suspend fun record(
            text: String,
            deviceId: String?,
            at: Long,
        ) {
            recorded += Triple(text, deviceId, at)
        }
    }

    private val allowed = setOf(PacketTypes.CLIPBOARD, PacketTypes.CLIPBOARD_CONNECT)

    private fun plugin(
        fake: FakeContext,
        store: ClipboardStore,
        system: FakeSystem,
        history: FakeHistory,
    ) = ClipboardPlugin(fake.context, store, system, history, now = { 1_000L })

    private fun connect(
        content: String,
        timestamp: Long,
    ) = ClipboardConnectBody(content, timestamp).toPacket(id = 1)

    @Test
    fun connectSendsNothingWhenNoClipIsKnown() {
        val fake = FakeContext(allowed)
        runBlocking { plugin(fake, ClipboardStore(), FakeSystem(), FakeHistory()).onCreate() }
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun connectSendsOurLatestClipWithItsTimestamp() {
        val fake = FakeContext(allowed)
        val store = ClipboardStore().apply { record("hello", 100L) }
        runBlocking { plugin(fake, store, FakeSystem(), FakeHistory()).onCreate() }
        val packet = fake.sent.single()
        assertEquals(PacketTypes.CLIPBOARD_CONNECT, packet.type)
        assertEquals(ClipboardConnectBody("hello", 100L), ClipboardConnectBody.parse(packet.body))
    }

    @Test
    fun newerConnectClipIsWrittenToTheLocalClipboard() {
        val fake = FakeContext(allowed)
        val store = ClipboardStore().apply { record("old", 100L) }
        val system = FakeSystem()
        val history = FakeHistory()
        val handled = runBlocking { plugin(fake, store, system, history).onPacket(connect("from desktop", 500L)) }
        assertTrue(handled)
        assertEquals(listOf("from desktop"), system.written)
        assertEquals(ClipEntry("from desktop", 1_000L), store.latest())
        assertEquals(listOf(Triple("from desktop", FakeContext.DEVICE, 1_000L)), history.recorded)
    }

    @Test
    fun olderOrZeroConnectClipDoesNotOverwriteOurs() {
        val fake = FakeContext(allowed)
        val store = ClipboardStore().apply { record("ours", 300L) }
        val system = FakeSystem()
        val plugin = plugin(fake, store, system, FakeHistory())
        runBlocking {
            assertTrue(plugin.onPacket(connect("older", 200L)))
            assertTrue(plugin.onPacket(connect("equal", 300L)))
            assertTrue(plugin.onPacket(connect("unknown", 0L)))
        }
        assertTrue(system.written.isEmpty())
        assertEquals(ClipEntry("ours", 300L), store.latest())
    }

    @Test
    fun plainClipboardPacketIsAppliedRegardlessOfTimestamp() {
        val fake = FakeContext(allowed)
        val system = FakeSystem()
        val packet = ClipboardBody("typed on desktop").toPacket(id = 2)
        runBlocking { plugin(fake, ClipboardStore(), system, FakeHistory()).onPacket(packet) }
        assertEquals(listOf("typed on desktop"), system.written)
    }

    @Test
    fun emptyTextIsIgnored() {
        val fake = FakeContext(allowed)
        val system = FakeSystem()
        runBlocking {
            plugin(
                fake,
                ClipboardStore(),
                system,
                FakeHistory(),
            ).onPacket(ClipboardBody("").toPacket(id = 3))
        }
        assertTrue(system.written.isEmpty())
    }

    @Test
    fun unrelatedPacketsAreNotHandled() {
        val fake = FakeContext(allowed)
        val packet = NetworkPacket(4, PacketTypes.PING, JsonObject(emptyMap()))
        assertFalse(runBlocking { plugin(fake, ClipboardStore(), FakeSystem(), FakeHistory()).onPacket(packet) })
    }
}
