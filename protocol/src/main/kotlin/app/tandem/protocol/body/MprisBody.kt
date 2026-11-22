// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body of `kdeconnect.mpris` from the desktop. One packet carries either the player list or the
 * state of one player, and a state packet may hold only the fields that changed, so every field is
 * nullable. Times are in milliseconds.
 */
data class MprisStatus(
    val playerList: List<String>? = null,
    val player: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isPlaying: Boolean? = null,
    val positionMs: Long? = null,
    val lengthMs: Long? = null,
    val volume: Int? = null,
    val canSeek: Boolean? = null,
    val canGoNext: Boolean? = null,
    val canGoPrevious: Boolean? = null,
) {
    companion object {
        fun parse(body: JsonObject): MprisStatus =
            MprisStatus(
                playerList = (body["playerList"] as? JsonArray)?.mapNotNull(::playerName),
                player = body.getString("player"),
                title = body.getString("title"),
                artist = body.getString("artist"),
                album = body.getString("album"),
                isPlaying = body.getBoolean("isPlaying"),
                positionMs = body.getLong("pos"),
                lengthMs = body.getLong("length"),
                volume = body.getInt("volume"),
                canSeek = body.getBoolean("canSeek"),
                canGoNext = body.getBoolean("canGoNext"),
                canGoPrevious = body.getBoolean("canGoPrevious"),
            )
    }
}

private fun playerName(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

/** What the phone asks the desktop to do with a media player, as `kdeconnect.mpris.request`. */
sealed interface MprisRequest {
    data object PlayerList : MprisRequest

    /** Asks for the full state and the volume of [player]. */
    data class NowPlaying(val player: String) : MprisRequest

    data class Action(val player: String, val action: MprisAction) : MprisRequest

    data class SetVolume(val player: String, val percent: Int) : MprisRequest

    data class SetPosition(val player: String, val positionMs: Long) : MprisRequest

    fun toJson(): JsonObject =
        buildJsonObject {
            when (this@MprisRequest) {
                PlayerList -> put("requestPlayerList", true)
                is NowPlaying -> {
                    put("player", player)
                    put("requestNowPlaying", true)
                    put("requestVolume", true)
                }
                is Action -> {
                    put("player", player)
                    put("action", action.wire)
                }
                is SetVolume -> {
                    put("player", player)
                    put("setVolume", percent.coerceIn(0, 100))
                }
                is SetPosition -> {
                    put("player", player)
                    put("SetPosition", positionMs.coerceAtLeast(0L))
                }
            }
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.MPRIS_REQUEST, toJson())
}

/** The player actions the desktop accepts. [wire] is the exact string it expects. */
enum class MprisAction(val wire: String) {
    Play("Play"),
    Pause("Pause"),
    PlayPause("PlayPause"),
    Next("Next"),
    Previous("Previous"),
    Stop("Stop"),
}
