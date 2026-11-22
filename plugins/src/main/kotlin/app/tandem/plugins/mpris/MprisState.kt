// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import app.tandem.protocol.body.MprisStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What is playing on one desktop player. [updatedAtMs] is when [positionMs] was received. */
data class MprisNowPlaying(
    val deviceId: String,
    val player: String,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val lengthMs: Long = 0L,
    val volume: Int = 0,
    val updatedAtMs: Long = 0L,
) {
    /** The position now: the received one plus the time since, while playing, never past the end. */
    fun positionAt(nowMs: Long): Long {
        val advanced = if (isPlaying) positionMs + (nowMs - updatedAtMs).coerceAtLeast(0L) else positionMs
        return if (lengthMs > 0) advanced.coerceIn(0L, lengthMs) else advanced.coerceAtLeast(0L)
    }
}

/**
 * The latest media state of the connected desktop. Packets update it in place: a packet that carries
 * only a position leaves the title alone. With several players, the first one listed is shown.
 */
@Singleton
class MprisState
    @Inject
    constructor() {
        private val lock = Any()
        private val flow = MutableStateFlow<MprisNowPlaying?>(null)
        val nowPlaying: StateFlow<MprisNowPlaying?> = flow.asStateFlow()

        private val playerFlow = MutableStateFlow<List<String>>(emptyList())

        /** Every player the desktop listed, in its order. */
        val playerNames: StateFlow<List<String>> = playerFlow.asStateFlow()

        private var deviceId: String? = null
        private var players: List<String> = emptyList()

        /** The player to control, or null before the desktop has listed one. */
        val player: String? get() = synchronized(lock) { flow.value?.player ?: players.firstOrNull() }

        val device: String? get() = synchronized(lock) { deviceId }

        /** Returns the player whose state should be requested after this update, or null. */
        fun update(
            device: String,
            status: MprisStatus,
            nowMs: Long,
        ): String? =
            synchronized(lock) {
                deviceId = device
                status.playerList?.let { list ->
                    players = list
                    playerFlow.value = list
                    val current = flow.value
                    if (list.isEmpty()) {
                        flow.value = null
                        return@synchronized null
                    }
                    if (current == null || current.player !in list) {
                        flow.value = MprisNowPlaying(device, list.first(), updatedAtMs = nowMs)
                        return@synchronized list.first()
                    }
                }
                val current = flow.value ?: return@synchronized null
                val stamp = if (status.positionMs != null || status.isPlaying != null) nowMs else current.updatedAtMs
                if (status.player != null && status.player != current.player) return@synchronized null
                flow.value =
                    current.copy(
                        title = status.title ?: current.title,
                        artist = status.artist ?: current.artist,
                        isPlaying = status.isPlaying ?: current.isPlaying,
                        positionMs = status.positionMs ?: current.positionMs,
                        lengthMs = status.lengthMs ?: current.lengthMs,
                        volume = status.volume ?: current.volume,
                        updatedAtMs = stamp,
                    )
                null
            }

        /**
         * Switches to [name]. The state shown is cleared until the desktop answers for the new player.
         * Returns false when [name] is not a listed player.
         */
        fun select(name: String): Boolean =
            synchronized(lock) {
                val device = deviceId ?: return@synchronized false
                if (name !in players) return@synchronized false
                if (flow.value?.player != name) flow.value = MprisNowPlaying(device, name)
                true
            }

        /** Forgets everything from [device], for example when it disconnects. */
        fun clear(device: String) {
            synchronized(lock) {
                if (deviceId == device) {
                    deviceId = null
                    players = emptyList()
                    playerFlow.value = emptyList()
                    flow.value = null
                }
            }
        }
    }
