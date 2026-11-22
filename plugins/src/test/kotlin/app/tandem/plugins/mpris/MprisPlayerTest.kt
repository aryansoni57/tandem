// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import androidx.media3.common.Player
import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.body.MprisStatus
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The media3 facade over the laptop's player: state in, commands out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MprisPlayerTest {
    private val peers = FakePeerSender()
    private val state = MprisState()
    private val player =
        MprisPlayer(MprisController(peers, state), CoroutineScope(Job() + Dispatchers.Unconfined)) { 4_000L }

    private fun playing() =
        MprisNowPlaying(
            deviceId = "dev",
            player = "vlc",
            title = "Song",
            artist = "Band",
            isPlaying = true,
            positionMs = 1_000L,
            lengthMs = 200_000L,
            updatedAtMs = 1_000L,
        )

    private fun connect() {
        state.update("dev", MprisStatus(playerList = listOf("vlc")), 0L)
    }

    @Test
    fun noMediaMeansIdle() {
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test
    fun stateShowsTitleArtistDurationAndPosition() {
        player.update(playing())
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals("Song", player.mediaMetadata.title.toString())
        assertEquals("Band", player.mediaMetadata.artist.toString())
        assertEquals(200_000L, player.duration)
        // 3 s after the position was received, a playing track is 3 s further on.
        assertEquals(4_000L, player.currentPosition)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun pausedStateIsNotPlaying() {
        player.update(playing().copy(isPlaying = false))
        assertFalse(player.playWhenReady)
    }

    @Test
    fun commandsAreForwardedToTheLaptop() {
        connect()
        player.update(playing())
        player.pause()
        player.seekToNext()
        player.seekTo(30_000L)
        val bodies = peers.sent.map { it.second.body }
        assertEquals("Pause", bodies[0].getString("action"))
        assertEquals("Next", bodies[1].getString("action"))
        assertEquals(30_000L, bodies[2].getLong("SetPosition"))
    }
}
