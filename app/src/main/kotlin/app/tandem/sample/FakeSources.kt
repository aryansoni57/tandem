// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sample

import app.tandem.state.CallsSmsSettings
import app.tandem.state.ClipItem
import app.tandem.state.ClipboardHistorySource
import app.tandem.state.ConnectionRoute
import app.tandem.state.ConnectionSource
import app.tandem.state.HandledItem
import app.tandem.state.KeyMods
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.NowPlaying
import app.tandem.state.PointerButton
import app.tandem.state.RemoteKey
import app.tandem.state.RemoteSource
import app.tandem.state.RunCommand
import app.tandem.state.SlideDeck
import app.tandem.state.SyncApp
import app.tandem.state.SyncSettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// Sample implementations. Nothing here talks to a device. Each class is replaced by the real
// plugin when its phase lands (see the TODO on the matching interface in app.tandem.state).

/** TODO(phase2): replace with the clipboard history query and a persisted auto-sync setting. */
class FakeClipboardHistorySource
    @Inject
    constructor() : ClipboardHistorySource {
        private val clipState = MutableStateFlow<List<ClipItem>>(SampleData.clips)
        override val clips: StateFlow<List<ClipItem>> = clipState.asStateFlow()

        private val autoSyncState = MutableStateFlow(true)
        override val autoSync: StateFlow<Boolean> = autoSyncState.asStateFlow()

        override fun setAutoSync(enabled: Boolean) {
            autoSyncState.value = enabled
        }
    }

/** TODO(phase4): notification sync. TODO(phase11): calls. TODO(phase12): SMS. */
class FakeSyncSettingsSource
    @Inject
    constructor() : SyncSettingsSource {
        private val notificationState = MutableStateFlow<NotificationSyncSettings>(SampleData.notificationSettings)
        override val notifications: StateFlow<NotificationSyncSettings> = notificationState.asStateFlow()

        private val appState = MutableStateFlow<List<SyncApp>>(SampleData.syncApps)
        override val apps: StateFlow<List<SyncApp>> = appState.asStateFlow()

        private val callState = MutableStateFlow<CallsSmsSettings>(SampleData.calls)
        override val calls: StateFlow<CallsSmsSettings> = callState.asStateFlow()

        private val handledState = MutableStateFlow<List<HandledItem>>(SampleData.handled)
        override val handled: StateFlow<List<HandledItem>> = handledState.asStateFlow()

        override fun setShowOnLaptop(enabled: Boolean) {
            notificationState.value = notificationState.value.copy(showOnLaptop = enabled)
        }

        override fun setScreenOffOnly(enabled: Boolean) {
            notificationState.value = notificationState.value.copy(screenOffOnly = enabled)
        }

        override fun setAppEnabled(
            id: String,
            enabled: Boolean,
        ) {
            appState.value = appState.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override fun setTextFromLaptop(enabled: Boolean) {
            callState.value = callState.value.copy(textFromLaptop = enabled)
        }

        override fun setCallAlerts(enabled: Boolean) {
            callState.value = callState.value.copy(callAlerts = enabled)
        }

        override fun setPauseMediaOnCall(enabled: Boolean) {
            callState.value = callState.value.copy(pauseMediaOnCall = enabled)
        }

    }

/** TODO(phase6): remote input. TODO(phase7): media. TODO(phase8): presenter. TODO(phase9): run commands. */
class FakeRemoteSource
    @Inject
    constructor() : RemoteSource {
        private val playingState = MutableStateFlow<NowPlaying>(SampleData.nowPlaying)
        override val nowPlaying: StateFlow<NowPlaying> = playingState.asStateFlow()

        override val players: StateFlow<List<String>> =
            MutableStateFlow(listOf("Browser", "Video player")).asStateFlow()

        override fun selectPlayer(name: String) {
            playingState.value = playingState.value.copy(source = name)
        }

        private val commandState = MutableStateFlow<List<RunCommand>>(SampleData.commands)
        override val commands: StateFlow<List<RunCommand>> = commandState.asStateFlow()

        private val deckState = MutableStateFlow<SlideDeck>(SampleData.deck)
        override val deck: StateFlow<SlideDeck> = deckState.asStateFlow()

        override fun togglePlayPause() {
            playingState.value = playingState.value.copy(playing = !playingState.value.playing)
        }

        override fun skip(next: Boolean) {
            // Sample only: the track does not change.
        }

        override fun seekTo(positionMs: Long) {
            playingState.value =
                playingState.value.copy(
                    positionMs = positionMs.coerceIn(0L, playingState.value.durationMs),
                )
        }

        override fun setVolume(percent: Int) {
            playingState.value = playingState.value.copy(volume = percent.coerceIn(0, 100))
        }

        override val canAddCommands: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

        override fun refreshCommands() {
            // Sample only.
        }

        override fun setupCommands() {
            // Sample only.
        }

        override fun runCommand(id: String) {
            // Sample only: nothing runs on the laptop.
        }

        override fun movePointer(
            dx: Float,
            dy: Float,
        ) {
            // Sample only.
        }

        override fun click(button: PointerButton) {
            // Sample only.
        }

        override fun sendText(
            text: String,
            mods: KeyMods,
        ) {
            // Sample only.
        }

        override fun scroll(dy: Float) {
            // Sample only.
        }

        override fun doubleClick() {
            // Sample only.
        }

        override fun hold(down: Boolean) {
            // Sample only.
        }

        override fun pressKey(
            key: RemoteKey,
            mods: KeyMods,
        ) {
            // Sample only.
        }

        override fun changeSlide(next: Boolean) {
            val current = deckState.value
            val slide = (current.slide + if (next) 1 else -1).coerceIn(1, current.total)
            deckState.value = current.copy(slide = slide)
        }

        override fun slideshow(start: Boolean) {
            // Sample only.
        }

        override fun presenterPoint(
            dx: Float,
            dy: Float,
        ) {
            // Sample only.
        }

        override fun presenterStop() {
            // Sample only.
        }

        override fun lockLaptop() {
            // Sample only.
        }
    }

/** TODO(later): connection routes and browser extension switches. TODO(phase1): real latency from ping. */
class FakeConnectionSource
    @Inject
    constructor() : ConnectionSource {
        private val routeState = MutableStateFlow<List<ConnectionRoute>>(SampleData.routes)
        override val routes: StateFlow<List<ConnectionRoute>> = routeState.asStateFlow()

        private val selectedState = MutableStateFlow(SampleData.routes.first().id)
        override val selectedRouteId: StateFlow<String> = selectedState.asStateFlow()

        private val latencyState = MutableStateFlow<Int?>(SampleData.LATENCY_MS)
        override val latencyMs: StateFlow<Int?> = latencyState.asStateFlow()

        private val linksState = MutableStateFlow(true)
        override val linksFromBrowser: StateFlow<Boolean> = linksState.asStateFlow()

        private val mediaState = MutableStateFlow(true)
        override val browserMedia: StateFlow<Boolean> = mediaState.asStateFlow()

        override fun selectRoute(id: String) {
            selectedState.value = id
        }

        override fun setLinksFromBrowser(enabled: Boolean) {
            linksState.value = enabled
        }

        override fun setBrowserMedia(enabled: Boolean) {
            mediaState.value = enabled
        }
    }
