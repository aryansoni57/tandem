// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.presenter

import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.PresenterBody
import app.tandem.protocol.getBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenterBodyTest {
    @Test
    fun moveCarriesFractionsAndStopCarriesTheFlag() {
        val move = PresenterBody.Move(0.1f, -0.25f).toJson()
        assertEquals(0.1, move["dx"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        assertEquals(-0.25, move["dy"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        assertEquals(true, PresenterBody.Stop.toJson().getBoolean("stop"))
        assertEquals(PacketTypes.PRESENTER, PresenterBody.Stop.toPacket().type)
    }
}

class PresenterSenderTest {
    private val peers = FakePeerSender()
    private var device: String? = "dev1"
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val sender = PresenterSender(peers, { device }, scope)

    private suspend fun settle() = delay(100)

    @Test
    fun movesAreSentToTheConnectedDevice() =
        runBlocking {
            sender.move(0.1f, 0.2f)
            settle()
            val body = peers.sent.single().second.body
            assertEquals("dev1", peers.sent.single().first)
            assertEquals(0.1, body["dx"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        }

    @Test
    fun stopIsSentAndKeepsItsPlaceBetweenMoves() =
        runBlocking {
            sender.move(0.1f, 0f)
            settle()
            sender.stop()
            settle()
            sender.move(0.2f, 0f)
            settle()
            val types = peers.sent.map { it.second.body.getBoolean("stop") == true }
            assertEquals(listOf(false, true, false), types)
        }

    @Test
    fun movesThatPileUpBeforeTheSendAreSummed() =
        runBlocking {
            repeat(50) { sender.move(0.01f, 0.02f) }
            settle()
            val dx = peers.sent.sumOf { it.second.body["dx"]!!.jsonPrimitive.doubleOrNull!! }
            val dy = peers.sent.sumOf { it.second.body["dy"]!!.jsonPrimitive.doubleOrNull!! }
            assertEquals(0.5, dx, 1e-4)
            assertEquals(1.0, dy, 1e-4)
            assertTrue(peers.sent.size <= 50)
        }

    @Test
    fun nothingIsSentWithoutAConnectedDevice() =
        runBlocking {
            device = null
            sender.move(0.1f, 0.1f)
            sender.stop()
            settle()
            assertTrue(peers.sent.isEmpty())
        }
}
