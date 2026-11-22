// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sample

import app.tandem.plugins.mousepad.MousepadSender
import app.tandem.plugins.mpris.MprisController
import app.tandem.plugins.mpris.MprisNowPlaying
import app.tandem.plugins.mpris.MprisState
import app.tandem.plugins.presenter.PresenterSender
import app.tandem.plugins.runcommand.RunCommandController
import app.tandem.plugins.runcommand.RunCommandState
import app.tandem.protocol.body.DesktopCommand
import app.tandem.protocol.body.KeyModifiers
import app.tandem.protocol.body.MousepadBody
import app.tandem.protocol.body.MprisAction
import app.tandem.protocol.body.SpecialKey
import app.tandem.state.KeyMods
import app.tandem.state.NowPlaying
import app.tandem.state.SlideDeck
import app.tandem.state.PointerButton
import app.tandem.state.RemoteKey
import app.tandem.state.RunCommand
import app.tandem.state.RemoteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.tandem.ui.remote.CommandClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trackpad and keyboard go through [MousepadSender], media through [MprisController], and the laser
 * dot through [PresenterSender]. Run commands go through [RunCommandController].
 */
@Singleton
class RealRemoteSource
    @Inject
    constructor(
        private val mousepad: MousepadSender,
        private val mpris: MprisController,
        private val presenter: PresenterSender,
        private val runCommands: RunCommandController,
        runCommandState: RunCommandState,
        mprisState: MprisState,
        sample: FakeRemoteSource,
    ) : RemoteSource by sample {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // The position advances on a one second tick while something plays.
        private val clock: Flow<Long> =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(TICK_MS)
                }
            }

        override val nowPlaying: StateFlow<NowPlaying?> =
            combine(mprisState.nowPlaying, clock) { media, now -> media?.toNowPlaying(now) }
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        override val players: StateFlow<List<String>> = mprisState.playerNames

        override fun selectPlayer(name: String) {
            scope.launch { mpris.selectPlayer(name) }
        }

        override fun togglePlayPause() {
            scope.launch { mpris.action(MprisAction.PlayPause) }
        }

        override fun skip(next: Boolean) {
            scope.launch { mpris.action(if (next) MprisAction.Next else MprisAction.Previous) }
        }

        override fun seekTo(positionMs: Long) {
            scope.launch { mpris.seekTo(positionMs) }
        }

        override fun setVolume(percent: Int) {
            scope.launch { mpris.setVolume(percent) }
        }

        override fun movePointer(
            dx: Float,
            dy: Float,
        ) = mousepad.move(dx, dy)

        override fun click(button: PointerButton) {
            val click =
                when (button) {
                    PointerButton.Left -> MousepadBody.Click.Single
                    PointerButton.Right -> MousepadBody.Click.Right
                    PointerButton.Middle -> MousepadBody.Click.Middle
                }
            scope.launch { mousepad.click(click) }
        }

        private val deckState = MutableStateFlow(SlideDeck(name = "Presentation", slide = 1, total = 0))
        override val deck: StateFlow<SlideDeck> = deckState.asStateFlow()

        // The desktop does not report slide numbers, so the count is kept here from the keys sent.
        override fun changeSlide(next: Boolean) {
            deckState.value = deckState.value.let { it.copy(slide = (it.slide + if (next) 1 else -1).coerceAtLeast(1)) }
            scope.launch { mousepad.special(if (next) SpecialKey.PageDown else SpecialKey.PageUp) }
        }

        override fun slideshow(start: Boolean) {
            if (start) deckState.value = deckState.value.copy(slide = 1)
            scope.launch { mousepad.special(if (start) SpecialKey.F5 else SpecialKey.Escape) }
        }

        override fun presenterPoint(
            dx: Float,
            dy: Float,
        ) = presenter.move(dx, dy)

        override fun presenterStop() = presenter.stop()

        override val commands: StateFlow<List<RunCommand>> =
            runCommandState.commands
                .map { list -> list.map(::toRunCommand) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        override val canAddCommands: StateFlow<Boolean> = runCommandState.canAdd

        override fun runCommand(id: String) {
            scope.launch { runCommands.run(id) }
        }

        override fun refreshCommands() {
            scope.launch { runCommands.refresh() }
        }

        override fun setupCommands() {
            scope.launch { runCommands.setup() }
        }

        override fun doubleClick() {
            scope.launch { mousepad.click(MousepadBody.Click.Double) }
        }

        override fun hold(down: Boolean) {
            scope.launch { mousepad.click(if (down) MousepadBody.Click.Hold else MousepadBody.Click.Release) }
        }

        override fun scroll(dy: Float) {
            scope.launch { mousepad.scroll(dy) }
        }

        override fun sendText(
            text: String,
            mods: KeyMods,
        ) {
            scope.launch { mousepad.type(text, mods.toBody()) }
        }

        override fun pressKey(
            key: RemoteKey,
            mods: KeyMods,
        ) {
            scope.launch { mousepad.special(key.toSpecial(), mods.toBody()) }
        }
    }

private const val TICK_MS = 1_000L
private const val STOP_TIMEOUT_MS = 5_000L

private fun MprisNowPlaying.toNowPlaying(nowMs: Long) =
    NowPlaying(
        title = title.ifBlank { "Unknown title" },
        artist = artist,
        source = player,
        positionMs = positionAt(nowMs),
        durationMs = lengthMs,
        playing = isPlaying,
        volume = volume,
    )

private fun toRunCommand(command: DesktopCommand) =
    RunCommand(
        id = command.id,
        name = command.name,
        command = command.command,
        icon = CommandClassifier.icon(command.name, command.command),
        destructive = CommandClassifier.isDestructive(command.name, command.command),
    )

private fun KeyMods.toBody() = KeyModifiers(ctrl, alt, shift, superKey)

private fun RemoteKey.toSpecial(): SpecialKey =
    when (this) {
        RemoteKey.Backspace -> SpecialKey.Backspace
        RemoteKey.Tab -> SpecialKey.Tab
        RemoteKey.Enter -> SpecialKey.Enter
        RemoteKey.Escape -> SpecialKey.Escape
        RemoteKey.Delete -> SpecialKey.Delete
        RemoteKey.Left -> SpecialKey.Left
        RemoteKey.Up -> SpecialKey.Up
        RemoteKey.Right -> SpecialKey.Right
        RemoteKey.Down -> SpecialKey.Down
        RemoteKey.Home -> SpecialKey.Home
        RemoteKey.End -> SpecialKey.End
        RemoteKey.PageUp -> SpecialKey.PageUp
        RemoteKey.PageDown -> SpecialKey.PageDown
    }
