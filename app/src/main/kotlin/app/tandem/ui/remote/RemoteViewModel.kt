// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.R
import app.tandem.state.KeyMods
import app.tandem.state.NowPlaying
import app.tandem.state.PointerButton
import app.tandem.state.RemoteKey
import app.tandem.state.RemoteSource
import app.tandem.state.RunCommand
import app.tandem.state.SlideDeck
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RemoteMode { Trackpad, Keyboard }

data class RemoteUiState(
    val mode: RemoteMode = RemoteMode.Trackpad,
    val pointerSpeed: Float = 0.6f,
    val typed: String = "",
    val mods: KeyMods = KeyMods.None,
)

/** Trackpad and keyboard. Gestures go straight to the remote source (TODO(phase6)). */
@HiltViewModel
class RemoteViewModel
    @Inject
    constructor(
        private val remote: RemoteSource,
    ) : ViewModel() {
        private val mode = MutableStateFlow(RemoteMode.Trackpad)
        private val speed = MutableStateFlow(0.6f)
        private val typed = MutableStateFlow("")
        private val mods = MutableStateFlow(KeyMods.None)

        val state: StateFlow<RemoteUiState> =
            combine(mode, speed, typed, mods) { m, s, text, k ->
                RemoteUiState(mode = m, pointerSpeed = s, typed = text, mods = k)
            }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

        fun selectMode(next: RemoteMode) {
            mode.value = next
        }

        fun setPointerSpeed(value: Float) {
            speed.value = value.coerceIn(0f, 1f)
        }

        fun onPointerDrag(
            dx: Float,
            dy: Float,
        ) {
            remote.movePointer(dx * speed.value * SPEED_SCALE, dy * speed.value * SPEED_SCALE)
        }

        fun onClick(button: PointerButton) = remote.click(button)

        fun onDoubleClick() = remote.doubleClick()

        fun onHold(down: Boolean) = remote.hold(down)

        fun onScroll(fingerDy: Float) = remote.scroll(TrackpadLogic.scrollAmount(fingerDy))

        fun toggleModifier(modifier: TrackpadLogic.Modifier) {
            mods.value = TrackpadLogic.toggle(mods.value, modifier)
        }

        /** A special key goes out with the held modifiers, which are then released. */
        fun pressKey(key: RemoteKey) {
            remote.pressKey(key, mods.value)
            mods.value = KeyMods.None
        }

        fun onTypedChange(text: String) {
            typed.value = text
        }

        fun sendTyped() {
            val text = typed.value
            if (text.isNotEmpty()) {
                remote.sendText(text, mods.value)
                typed.value = ""
                mods.value = KeyMods.None
            }
        }

        companion object {
            const val SPEED_SCALE = 2f
        }
    }

data class MediaUiState(
    val playing: NowPlaying,
    val seekPreview: Float? = null,
    val players: List<String> = emptyList(),
) {
    /** Slider position 0..1: the drag preview while the thumb moves, otherwise the real position. */
    val seekFraction: Float
        get() = seekPreview ?: if (playing.durationMs > 0) playing.positionMs.toFloat() / playing.durationMs else 0f
}

/** Media control on the laptop. Seeking is committed when the slider is released. */
@HiltViewModel
class MediaViewModel
    @Inject
    constructor(
        private val remote: RemoteSource,
    ) : ViewModel() {
        private val preview = MutableStateFlow<Float?>(null)

        val state: StateFlow<MediaUiState> =
            combine(remote.nowPlaying, preview, remote.players) { now, drag, names ->
                MediaUiState(now ?: NowPlaying.Empty, drag, names)
            }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    MediaUiState(remote.nowPlaying.value ?: NowPlaying.Empty, players = remote.players.value),
                )

        fun togglePlayPause() = remote.togglePlayPause()

        fun skip(next: Boolean) = remote.skip(next)

        fun previewSeek(fraction: Float) {
            preview.value = fraction.coerceIn(0f, 1f)
        }

        fun commitSeek() {
            val fraction = preview.value ?: return
            preview.value = null
            remote.seekTo((fraction * state.value.playing.durationMs).toLong())
        }

        fun setVolume(percent: Int) = remote.setVolume(percent)

        fun selectPlayer(name: String) = remote.selectPlayer(name)
    }

data class PresenterUiState(
    val deck: SlideDeck,
    val timer: PresenterTimer,
    val nowMs: Long,
    val pointerActive: Boolean,
) {
    val elapsedMs: Long get() = timer.elapsedMs(nowMs)
}

/** Presentation remote. The timer ticks once per second while running. */
@HiltViewModel
class PresenterViewModel
    @Inject
    constructor(
        private val remote: RemoteSource,
    ) : ViewModel() {
        private val timer = MutableStateFlow(PresenterTimer())
        private val pointer = MutableStateFlow(false)
        private val clock = MutableStateFlow(System.currentTimeMillis())

        init {
            viewModelScope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    clock.value = System.currentTimeMillis()
                }
            }
        }

        val state: StateFlow<PresenterUiState> =
            combine(remote.deck, timer, clock, pointer) { deck, t, now, active ->
                PresenterUiState(deck = deck, timer = t, nowMs = now, pointerActive = active)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PresenterUiState(remote.deck.value, PresenterTimer(), System.currentTimeMillis(), false),
            )

        fun toggleTimer(now: Long = System.currentTimeMillis()) {
            timer.value = timer.value.toggle(now)
        }

        fun resetTimer() {
            timer.value = timer.value.reset()
        }

        fun setPointerActive(active: Boolean) {
            pointer.value = active
            // Letting go hides the dot on the laptop at once instead of after its half second timeout.
            if (!active) remote.presenterStop()
        }

        /** Touch drag, the fallback for a phone without a rotation sensor. [dx] and [dy] are in pixels. */
        fun onPointerDrag(
            dx: Float,
            dy: Float,
        ) {
            if (pointer.value) remote.presenterPoint(dx * DRAG_SCALE, dy * DRAG_SCALE)
        }

        /** A turn of the phone, already in screen fractions. */
        fun onGyro(
            dx: Float,
            dy: Float,
        ) {
            if (pointer.value) remote.presenterPoint(dx, dy)
        }

        fun step(next: Boolean) = remote.changeSlide(next)

        fun slideshow(start: Boolean) = remote.slideshow(start)

        override fun onCleared() {
            remote.presenterStop()
            super.onCleared()
        }

        companion object {
            /** About 500 px of drag crosses the screen. */
            const val DRAG_SCALE = 0.002f
            const val TICK_MS = 1_000L
        }
    }

data class CommandsUiState(
    val commands: List<RunCommand> = emptyList(),
    val confirm: CommandConfirmState = CommandConfirmState(),
    val canAdd: Boolean = false,
)

/** Run commands list with the confirm step for risky commands. */
@HiltViewModel
class RunCommandsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val remote: RemoteSource,
    ) : ViewModel() {
        private val confirm = MutableStateFlow(CommandConfirmState())
        private val messages = Channel<String>(Channel.BUFFERED)

        val messageFlow: Flow<String> = messages.receiveAsFlow()

        val state: StateFlow<CommandsUiState> =
            combine(remote.commands, confirm, remote.canAddCommands) { list, c, canAdd ->
                CommandsUiState(list, c, canAdd)
            }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommandsUiState())

        init {
            remote.refreshCommands()
        }

        fun setup() = remote.setupCommands()

        fun request(command: RunCommand) = applyStep(CommandConfirm.request(confirm.value, command))

        fun confirmPending() = applyStep(CommandConfirm.confirm(confirm.value))

        fun cancelPending() = applyStep(CommandConfirm.cancel())

        private fun applyStep(step: CommandStep) {
            confirm.value = step.state
            step.run?.let { command ->
                remote.runCommand(command.id)
                messages.trySend(context.getString(R.string.commands_sent, command.name))
            }
        }
    }
