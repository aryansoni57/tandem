// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.state

import kotlinx.coroutines.flow.StateFlow

/*
 * State holder contracts for the screens whose protocol plugins do not exist yet. Each
 * contract has a sample implementation in app.tandem.sample. Replace the binding in
 * app.tandem.sample.SampleModule when the plugin lands.
 */

enum class TransferKind { Video, Image, Document, Link }

enum class TransferDirection { ToLaptop, FromLaptop }

/** Ends that are not a success. Null means done (or still running). */
enum class TransferStatus { Failed, Cancelled }

/**
 * One row: a file batch, a text or a link. [progress] is 0..1 while running and null when finished.
 * The row id is the batch id; cancelling a row cancels its batch.
 */
data class Transfer(
    val id: String,
    val name: String,
    val kind: TransferKind,
    val direction: TransferDirection,
    val sizeMb: Float?,
    val progress: Float?,
    val timeLabel: String,
    val status: TransferStatus? = null,
)

/**
 * The phone's storage as shared with the laptop. [folders] are shared, [candidates] could be added.
 * [hasAccess] is false until the user grants "all files access", and nothing is reachable before that.
 */
data class SharedStorage(
    val enabled: Boolean,
    val usedGb: Float,
    val totalGb: Float,
    val folders: List<String>,
    val candidates: List<String> = emptyList(),
    val hasAccess: Boolean = true,
)

/** Files and links, and the phone storage the laptop can browse. */
interface TransferSource {
    val transfers: StateFlow<List<Transfer>>
    val today: StateFlow<List<Transfer>>
    val storage: StateFlow<SharedStorage>

    /** Sends the link to the first connected device. Returns false when none is connected. */
    suspend fun sendLink(url: String): Boolean

    fun cancel(id: String)

    fun setStorageEnabled(enabled: Boolean)

    fun addSharedFolder(name: String)

    fun removeSharedFolder(name: String)
}

enum class ClipSource { Laptop, Phone }

data class ClipItem(
    val id: String,
    val text: String,
    val source: ClipSource,
    val timeLabel: String,
    val monospace: Boolean,
)

/** Clipboard history and auto-sync. TODO(phase2): history query and persisted auto-sync toggle. */
interface ClipboardHistorySource {
    val clips: StateFlow<List<ClipItem>>
    val autoSync: StateFlow<Boolean>

    fun setAutoSync(enabled: Boolean)
}

enum class SyncCategory { Messaging, Work, Shopping }

data class SyncApp(
    val id: String,
    val name: String,
    val category: SyncCategory,
    val enabled: Boolean,
    val note: String,
    val initial: String,
)

data class CallsSmsSettings(
    val textFromLaptop: Boolean,
    val callAlerts: Boolean,
    val pauseMediaOnCall: Boolean,
)

enum class HandledKind { Sms, Missed, Messages }

data class HandledItem(
    val id: String,
    val title: String,
    val detail: String,
    val kind: HandledKind,
    val timeLabel: String,
    val initials: String?,
)

data class NotificationSyncSettings(
    val showOnLaptop: Boolean,
    val screenOffOnly: Boolean,
)

/** Notification sync (TODO(phase4)) and calls and SMS (TODO(phase11), TODO(phase12)). */
interface SyncSettingsSource {
    val notifications: StateFlow<NotificationSyncSettings>
    val apps: StateFlow<List<SyncApp>>
    val calls: StateFlow<CallsSmsSettings>
    val handled: StateFlow<List<HandledItem>>

    fun setShowOnLaptop(enabled: Boolean)

    fun setScreenOffOnly(enabled: Boolean)

    fun setAppEnabled(
        id: String,
        enabled: Boolean,
    )

    fun setTextFromLaptop(enabled: Boolean)

    fun setCallAlerts(enabled: Boolean)

    fun setPauseMediaOnCall(enabled: Boolean)

}

data class NowPlaying(
    val title: String,
    val artist: String,
    val source: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val volume: Int,
) {
    companion object {
        /** Shown on the media screen when no player is active. */
        val Empty =
            NowPlaying(
                title = "Nothing playing",
                artist = "",
                source = "",
                positionMs = 0L,
                durationMs = 0L,
                playing = false,
                volume = 0,
            )
    }
}

data class RunCommand(
    val id: String,
    val name: String,
    val command: String,
    val icon: CommandIcon,
    val destructive: Boolean,
)

enum class CommandIcon { Lock, Sleep, Backup, Screenshot, Power, Generic }

data class SlideDeck(
    val name: String,
    val slide: Int,
    /** Zero when the slide count is not known. */
    val total: Int,
)

enum class PointerButton { Left, Right, Middle }

/** Keys with no text form. The names follow the desktop's special key table. */
enum class RemoteKey { Backspace, Tab, Enter, Escape, Delete, Left, Up, Right, Down, Home, End, PageUp, PageDown }

/** Modifier keys held for one key press. */
data class KeyMods(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val superKey: Boolean = false,
) {
    val any: Boolean get() = ctrl || alt || shift || superKey

    companion object {
        val None = KeyMods()
    }
}

/** Media, run commands, remote input and presenter. TODO(phase6..phase9). */
// One façade for the five remote features while they share a screen group. Split it per feature if it grows.
@Suppress("TooManyFunctions")
interface RemoteSource {
    /** Null when the laptop has no active media player. */
    val nowPlaying: StateFlow<NowPlaying?>

    /** Names of the laptop's media players. Empty when none is running. */
    val players: StateFlow<List<String>>
    val commands: StateFlow<List<RunCommand>>

    /** True when the laptop lets the phone open its "add command" dialog. */
    val canAddCommands: StateFlow<Boolean>
    val deck: StateFlow<SlideDeck>

    fun togglePlayPause()

    /** Controls the player called [name], one of [players]. */
    fun selectPlayer(name: String)

    fun skip(next: Boolean)

    fun seekTo(positionMs: Long)

    fun setVolume(percent: Int)

    fun runCommand(id: String)

    /** Asks the laptop for its command list again. */
    fun refreshCommands()

    /** Opens the laptop's dialog for adding commands. */
    fun setupCommands()

    fun movePointer(
        dx: Float,
        dy: Float,
    )

    fun click(button: PointerButton)

    /** Types [text] on the laptop. With [mods] held, a letter becomes a shortcut such as Ctrl+C. */
    fun sendText(
        text: String,
        mods: KeyMods = KeyMods.None,
    )

    fun scroll(dy: Float)

    fun doubleClick()

    /** Holds (true) or releases (false) the left button, for drag and drop. */
    fun hold(down: Boolean)

    fun pressKey(
        key: RemoteKey,
        mods: KeyMods = KeyMods.None,
    )

    fun changeSlide(next: Boolean)

    /** Starts (F5) or ends (Escape) the slideshow on the laptop. */
    fun slideshow(start: Boolean)

    /** Moves the laser dot by [dx] and [dy], as fractions of the screen width. */
    fun presenterPoint(
        dx: Float,
        dy: Float,
    )

    /** Hides the laser dot. */
    fun presenterStop()

    fun lockLaptop()
}

data class ConnectionRoute(
    val id: String,
    val name: String,
    val detail: String,
    val viaVpn: Boolean,
)

/** Connection routes and browser extension switches. TODO(later): OpenVPN route and browser extension. */
interface ConnectionSource {
    val routes: StateFlow<List<ConnectionRoute>>
    val selectedRouteId: StateFlow<String>
    val latencyMs: StateFlow<Int?>
    val linksFromBrowser: StateFlow<Boolean>
    val browserMedia: StateFlow<Boolean>

    fun selectRoute(id: String)

    fun setLinksFromBrowser(enabled: Boolean)

    fun setBrowserMedia(enabled: Boolean)
}
