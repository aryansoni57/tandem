// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import app.tandem.plugins.FakeContext
import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.MprisAction
import app.tandem.protocol.body.MprisRequest
import app.tandem.protocol.body.MprisStatus
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MprisBodyTest {
    @Test
    fun requestsUseTheDesktopFieldNames() {
        assertEquals(true, MprisRequest.PlayerList.toJson().getBoolean("requestPlayerList"))
        val now = MprisRequest.NowPlaying("vlc").toJson()
        assertEquals("vlc", now.getString("player"))
        assertEquals(true, now.getBoolean("requestNowPlaying"))
        assertEquals(true, now.getBoolean("requestVolume"))
        assertEquals("PlayPause", MprisRequest.Action("vlc", MprisAction.PlayPause).toJson().getString("action"))
        assertEquals(100, MprisRequest.SetVolume("vlc", 250).toJson().getInt("setVolume"))
        assertEquals(0L, MprisRequest.SetPosition("vlc", -5L).toJson().getLong("SetPosition"))
        assertEquals(PacketTypes.MPRIS_REQUEST, MprisRequest.PlayerList.toPacket().type)
    }

    @Test
    fun statusKeepsOnlyTheFieldsThePacketHas() {
        val body =
            buildJsonObject {
                put("player", "vlc")
                put("pos", 42_000L)
                put("isPlaying", true)
            }
        val status = MprisStatus.parse(body)
        assertEquals(42_000L, status.positionMs)
        assertEquals(true, status.isPlaying)
        assertNull(status.title)
        assertNull(status.playerList)
    }

    @Test
    fun playerListIsParsedInOrder() {
        val names = JsonArray(listOf(JsonPrimitive("Firefox"), JsonPrimitive("vlc")))
        val body = buildJsonObject { put("playerList", names) }
        assertEquals(listOf("Firefox", "vlc"), MprisStatus.parse(body).playerList)
    }
}

class MprisStateTest {
    private val state = MprisState()

    @Test
    fun firstListedPlayerIsChosenAndNeedsItsState() {
        val ask = state.update("d", MprisStatus(playerList = listOf("Firefox", "vlc")), 1_000L)
        assertEquals("Firefox", ask)
        assertEquals("Firefox", state.player)
        assertEquals("d", state.device)
    }

    @Test
    fun partialPacketsMergeIntoTheCurrentState() {
        state.update("d", MprisStatus(playerList = listOf("vlc")), 0L)
        val playing =
            MprisStatus(
                player = "vlc", title = "Song", artist = "Band",
                lengthMs = 200_000L, isPlaying = true, positionMs = 10_000L,
            )
        state.update("d", playing, 1_000L)
        state.update("d", MprisStatus(player = "vlc", volume = 55), 2_000L)
        val now = state.nowPlaying.value!!
        assertEquals("Song", now.title)
        assertEquals(55, now.volume)
        assertEquals(10_000L, now.positionMs)
        // Seven seconds after the position arrived, a playing track is seven seconds further on.
        assertEquals(17_000L, now.positionAt(8_000L))
    }

    @Test
    fun positionStopsAtTheEndAndWhilePaused() {
        val near = MprisNowPlaying("d", "vlc", isPlaying = true, positionMs = 195_000L, lengthMs = 200_000L)
        assertEquals(200_000L, near.positionAt(60_000L))
        assertEquals(195_000L, near.copy(isPlaying = false).positionAt(60_000L))
    }

    @Test
    fun packetForAnotherPlayerIsIgnoredAndEmptyListClears() {
        state.update("d", MprisStatus(playerList = listOf("vlc")), 0L)
        state.update("d", MprisStatus(player = "other", title = "Nope"), 0L)
        assertEquals("", state.nowPlaying.value!!.title)
        state.update("d", MprisStatus(playerList = emptyList()), 0L)
        assertNull(state.nowPlaying.value)
    }

    @Test
    fun selectSwitchesPlayerAndClearsItsState() {
        state.update("d", MprisStatus(playerList = listOf("Firefox", "vlc")), 0L)
        state.update("d", MprisStatus(player = "Firefox", title = "Clip"), 0L)
        assertEquals(listOf("Firefox", "vlc"), state.playerNames.value)
        assertTrue(state.select("vlc"))
        assertEquals("vlc", state.nowPlaying.value!!.player)
        assertEquals("", state.nowPlaying.value!!.title)
        assertFalse(state.select("unknown"))
        assertEquals("vlc", state.player)
    }

    @Test
    fun clearForgetsOnlyThatDevice() {
        state.update("d", MprisStatus(playerList = listOf("vlc")), 0L)
        state.clear("other")
        assertEquals("vlc", state.player)
        state.clear("d")
        assertNull(state.nowPlaying.value)
        assertNull(state.device)
    }
}

class MprisPluginTest {
    private val fake = FakeContext(setOf(PacketTypes.MPRIS_REQUEST))
    private val state = MprisState()
    private val plugin = MprisPlugin(fake.context, state) { 5_000L }

    private fun packet(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        NetworkPacket(1, PacketTypes.MPRIS, buildJsonObject(build))

    @Test
    fun linkOpenAsksForPlayers() =
        runBlocking {
            plugin.onCreate()
            assertEquals(true, fake.sent.single().body.getBoolean("requestPlayerList"))
        }

    @Test
    fun playerListTriggersNowPlayingRequest() =
        runBlocking {
            plugin.onPacket(packet { put("playerList", JsonArray(listOf(JsonPrimitive("vlc")))) })
            val request = fake.sent.single().body
            assertEquals("vlc", request.getString("player"))
            assertEquals(true, request.getBoolean("requestNowPlaying"))
        }

    @Test
    fun otherPacketTypesAreNotHandledAndDestroyClears() =
        runBlocking {
            assertFalse(plugin.onPacket(NetworkPacket(1, PacketTypes.PING, buildJsonObject {})))
            plugin.onPacket(packet { put("playerList", JsonArray(listOf(JsonPrimitive("vlc")))) })
            plugin.onDestroy()
            assertNull(state.nowPlaying.value)
        }
}

class MprisControllerTest {
    private val peers = FakePeerSender()
    private val state = MprisState()
    private val controller = MprisController(peers, state)

    @Test
    fun noPlayerMeansNothingIsSent() =
        runBlocking {
            assertFalse(controller.action(MprisAction.Next))
            assertTrue(peers.sent.isEmpty())
        }

    @Test
    fun selectingAPlayerAsksForItsState() =
        runBlocking {
            state.update("dev9", MprisStatus(playerList = listOf("Firefox", "vlc")), 0L)
            assertTrue(controller.selectPlayer("vlc"))
            val request = peers.sent.single().second.body
            assertEquals("vlc", request.getString("player"))
            assertEquals(true, request.getBoolean("requestNowPlaying"))
            assertFalse(controller.selectPlayer("nope"))
        }

    @Test
    fun commandsGoToTheDeviceAndPlayerThatReported() =
        runBlocking {
            state.update("dev9", MprisStatus(playerList = listOf("vlc")), 0L)
            assertTrue(controller.action(MprisAction.PlayPause))
            assertTrue(controller.seekTo(30_000L))
            assertTrue(controller.setVolume(40))
            assertEquals(listOf("dev9", "dev9", "dev9"), peers.sent.map { it.first })
            assertEquals("PlayPause", peers.sent[0].second.body.getString("action"))
            assertEquals(30_000L, peers.sent[1].second.body.getLong("SetPosition"))
            assertEquals(40, peers.sent[2].second.body.getInt("setVolume"))
        }
}
