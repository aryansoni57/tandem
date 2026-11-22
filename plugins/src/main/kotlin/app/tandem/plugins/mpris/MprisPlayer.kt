// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.tandem.protocol.body.MprisAction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows the laptop's player to Android as a media3 player, so the lock screen and the quick settings
 * media card get play, pause, next, previous and a seek bar. Nothing plays on the phone: every
 * command is forwarded to the desktop through [controller].
 *
 * Call [update] on the main thread whenever the desktop's state changes.
 */
@OptIn(UnstableApi::class)
class MprisPlayer(
    private val controller: MprisController,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var media: MprisNowPlaying? = null

    fun update(next: MprisNowPlaying?) {
        media = next
        invalidateState()
    }

    override fun getState(): State {
        val current = media ?: return State.Builder().setPlaybackState(STATE_IDLE).build()
        val commands =
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_GET_TIMELINE,
                )
                .build()
        val metadata =
            MediaMetadata.Builder()
                .setTitle(current.title.ifBlank { current.player })
                .setArtist(current.artist.ifBlank { null })
                .setDisplayTitle(current.title.ifBlank { current.player })
                .build()
        val item =
            MediaItemData.Builder(ITEM_UID)
                .setMediaItem(MediaItem.Builder().setMediaId(ITEM_UID).setMediaMetadata(metadata).build())
                .setMediaMetadata(metadata)
                .setDurationUs(if (current.lengthMs > 0) current.lengthMs * MICROS_PER_MILLI else C.TIME_UNSET)
                .setIsSeekable(current.lengthMs > 0)
                .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(current.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(current.positionAt(now()))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val action = if (playWhenReady) MprisAction.Play else MprisAction.Pause
        scope.launch { controller.action(action) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        scope.launch {
            when (seekCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.action(MprisAction.Next)
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.action(MprisAction.Previous)
                else -> controller.seekTo(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val ITEM_UID = "mpris"
        const val MICROS_PER_MILLI = 1_000L
    }
}
