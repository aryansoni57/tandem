// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ClipboardBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSyncTest {
    private class FakeSystem(var readValue: String?) : SystemClipboard {
        override suspend fun readText(skipSensitive: Boolean): String? = readValue

        override suspend fun writeText(text: String) {
            readValue = text
        }

        override suspend fun writeFile(
            uri: String,
            mime: String,
        ) = Unit
    }

    private class FakeHistory : ClipHistory {
        val recorded = mutableListOf<String>()

        override suspend fun record(
            text: String,
            deviceId: String?,
            at: Long,
        ) {
            recorded += text
        }
    }

    private fun sync(
        system: SystemClipboard,
        peers: FakePeerSender,
        store: ClipboardStore,
        history: ClipHistory = FakeHistory(),
    ) = ClipboardSync(store, system, peers, history, skipSensitive = { true }, now = { 42L })

    @Test
    fun sendLocalReadsTheClipboardAndSendsToEveryTarget() {
        val peers = FakePeerSender()
        val store = ClipboardStore()
        val outcome = runBlocking { sync(FakeSystem("copied"), peers, store).sendLocal(listOf("a", "b")) }
        assertEquals(SendOutcome.Sent(2), outcome)
        assertEquals(listOf("a", "b"), peers.sent.map { it.first })
        peers.sent.forEach { (_, packet) ->
            assertEquals(PacketTypes.CLIPBOARD, packet.type)
            assertEquals("copied", ClipboardBody.parse(packet.body)?.content)
        }
        assertEquals(ClipEntry("copied", 42L), store.latest())
    }

    @Test
    fun emptyClipboardSendsNothing() {
        val peers = FakePeerSender()
        val outcome = runBlocking { sync(FakeSystem(null), peers, ClipboardStore()).sendLocal(listOf("a")) }
        assertEquals(SendOutcome.NothingToSend, outcome)
        assertTrue(peers.sent.isEmpty())
    }

    @Test
    fun noAcceptingDeviceIsReportedAndTextIsStillRecordedLocally() {
        val peers = FakePeerSender(accept = { false })
        val store = ClipboardStore()
        val history = FakeHistory()
        val outcome = runBlocking { sync(FakeSystem("x"), peers, store, history).sendLocal(listOf("a")) }
        assertEquals(SendOutcome.NoDevice, outcome)
        assertEquals(ClipEntry("x", 42L), store.latest())
        assertEquals(listOf("x"), history.recorded)
    }

    @Test
    fun describeGivesAHumanLine() {
        assertEquals("Clipboard sent to 1 device.", SendOutcome.Sent(1).describe())
        assertEquals("Clipboard sent to 2 devices.", SendOutcome.Sent(2).describe())
    }
}
