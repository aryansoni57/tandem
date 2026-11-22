// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Publishes the laptop's media as a media session. media3 builds the notification and the lock screen
 * and quick settings controls from it. The service stops itself when the laptop has no player.
 */
@AndroidEntryPoint
class MprisMediaService : MediaSessionService() {
    @Inject lateinit var state: MprisState

    @Inject lateinit var controller: MprisController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MprisPlayer(controller, scope)
        session = MediaSession.Builder(this, player).build()
        scope.launch {
            state.nowPlaying.collect { media ->
                player.update(media)
                if (media == null) stopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /** Starts the service. Android may refuse a start from the background; the next player change retries. */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, MprisMediaService::class.java))
            } catch (_: IllegalStateException) {
                // Background start not allowed.
            }
        }
    }
}
