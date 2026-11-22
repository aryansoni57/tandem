// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.PointerButton
import kotlin.math.hypot
import kotlinx.coroutines.withTimeoutOrNull
import app.tandem.state.RemoteKey

/** Route entry for the Remote tab. */
@Composable
fun RemoteEntry(
    onPresenter: () -> Unit,
    onCommands: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    RemoteScreen(
        state = state,
        onSelectMode = viewModel::selectMode,
        onPointerSpeed = viewModel::setPointerSpeed,
        onPointerDrag = viewModel::onPointerDrag,
        onClick = viewModel::onClick,
        onDoubleClick = viewModel::onDoubleClick,
        onHold = viewModel::onHold,
        onScroll = viewModel::onScroll,
        onTypedChange = viewModel::onTypedChange,
        onSendTyped = viewModel::sendTyped,
        onToggleModifier = viewModel::toggleModifier,
        onKey = viewModel::pressKey,
        onPresenter = onPresenter,
        onCommands = onCommands,
    )
}

/** Remote tab: trackpad (touch surface, buttons, pointer speed) or keyboard, with a FAB to switch. */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onSelectMode: (RemoteMode) -> Unit,
    onPointerSpeed: (Float) -> Unit,
    onPointerDrag: (Float, Float) -> Unit,
    onClick: (PointerButton) -> Unit,
    onDoubleClick: () -> Unit,
    onHold: (Boolean) -> Unit,
    onScroll: (Float) -> Unit,
    onTypedChange: (String) -> Unit,
    onSendTyped: () -> Unit,
    onToggleModifier: (TrackpadLogic.Modifier) -> Unit,
    onKey: (RemoteKey) -> Unit,
    onPresenter: () -> Unit,
    onCommands: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_title)) },
                actions = {
                    IconButton(onClick = onPresenter) {
                        Icon(TandemIcons.Slideshow, contentDescription = stringResource(R.string.remote_presenter))
                    }
                    IconButton(onClick = onCommands) {
                        Icon(TandemIcons.Tune, contentDescription = stringResource(R.string.remote_commands))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.mode == RemoteMode.Trackpad) {
                FloatingActionButton(onClick = { onSelectMode(RemoteMode.Keyboard) }) {
                    Icon(TandemIcons.Keyboard, contentDescription = stringResource(R.string.remote_keyboard))
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
            ) {
                RemoteMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { onSelectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RemoteMode.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    if (mode == RemoteMode.Trackpad) {
                                        R.string.remote_mode_trackpad
                                    } else {
                                        R.string.remote_mode_keyboard
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            when (state.mode) {
                RemoteMode.Trackpad ->
                    TrackpadContent(state, onPointerSpeed, onPointerDrag, onClick, onDoubleClick, onHold, onScroll)
                RemoteMode.Keyboard ->
                    KeyboardContent(state, onTypedChange, onSendTyped, onToggleModifier, onKey)
            }
        }
    }
}

@Composable
private fun TrackpadContent(
    state: RemoteUiState,
    onPointerSpeed: (Float) -> Unit,
    onPointerDrag: (Float, Float) -> Unit,
    onClick: (PointerButton) -> Unit,
    onDoubleClick: () -> Unit,
    onHold: (Boolean) -> Unit,
    onScroll: (Float) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Box(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag("trackpad")
                    .clip(RoundedCornerShape(32.dp))
                    .background(surface)
                    .trackpadGestures(
                        onMove = onPointerDrag,
                        onScroll = onScroll,
                        onTap = { onClick(PointerButton.Left) },
                        onDoubleTap = onDoubleClick,
                        onHold = onHold,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(TandemIcons.Swipe, contentDescription = null, tint = hintColor, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.remote_trackpad_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = hintColor,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // FlowRow: at a large font size the third button drops to a second line instead of cutting its label.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClickButton(R.string.remote_left_click) { onClick(PointerButton.Left) }
            ClickButton(R.string.remote_middle_click) { onClick(PointerButton.Middle) }
            ClickButton(R.string.remote_right_click) { onClick(PointerButton.Right) }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            Text(stringResource(R.string.remote_pointer_speed), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = state.pointerSpeed,
                onValueChange = onPointerSpeed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One of the three pointer buttons. The narrow padding lets "Middle" fit at the largest font size. */
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.ClickButton(
    label: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).widthIn(min = 96.dp).height(56.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(stringResource(label)) }
}

@Composable
private fun KeyboardContent(
    state: RemoteUiState,
    onTypedChange: (String) -> Unit,
    onSendTyped: () -> Unit,
    onToggleModifier: (TrackpadLogic.Modifier) -> Unit,
    onKey: (RemoteKey) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.typed,
            onValueChange = onTypedChange,
            label = { Text(stringResource(R.string.remote_type_label)) },
            trailingIcon = {
                IconButton(onClick = onSendTyped, enabled = state.typed.isNotEmpty()) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.remote_send_text))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.remote_keyboard_hint), style = MaterialTheme.typography.bodyMedium)

        Text(stringResource(R.string.remote_modifiers), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val mods = state.mods
            ModifierChip(R.string.remote_mod_ctrl, mods.ctrl) { onToggleModifier(TrackpadLogic.Modifier.Ctrl) }
            ModifierChip(R.string.remote_mod_alt, mods.alt) { onToggleModifier(TrackpadLogic.Modifier.Alt) }
            ModifierChip(R.string.remote_mod_shift, mods.shift) { onToggleModifier(TrackpadLogic.Modifier.Shift) }
            ModifierChip(R.string.remote_mod_super, mods.superKey) { onToggleModifier(TrackpadLogic.Modifier.Super) }
        }

        Text(stringResource(R.string.remote_keys), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButtons.forEach { (key, label, description) ->
                val spoken = stringResource(description)
                OutlinedButton(
                    onClick = { onKey(key) },
                    modifier = Modifier.semantics { contentDescription = spoken },
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@Composable
private fun ModifierChip(
    label: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

private data class KeyButton(val key: RemoteKey, val label: Int, val description: Int)

private val KeyButtons =
    listOf(
        KeyButton(RemoteKey.Escape, R.string.remote_key_esc, R.string.remote_key_esc_d),
        KeyButton(RemoteKey.Tab, R.string.remote_key_tab, R.string.remote_key_tab_d),
        KeyButton(RemoteKey.Enter, R.string.remote_key_enter, R.string.remote_key_enter_d),
        KeyButton(RemoteKey.Backspace, R.string.remote_key_backspace, R.string.remote_key_backspace_d),
        KeyButton(RemoteKey.Delete, R.string.remote_key_delete, R.string.remote_key_delete_d),
        KeyButton(RemoteKey.Left, R.string.remote_key_left, R.string.remote_key_left_d),
        KeyButton(RemoteKey.Up, R.string.remote_key_up, R.string.remote_key_up_d),
        KeyButton(RemoteKey.Down, R.string.remote_key_down, R.string.remote_key_down_d),
        KeyButton(RemoteKey.Right, R.string.remote_key_right, R.string.remote_key_right_d),
        KeyButton(RemoteKey.Home, R.string.remote_key_home, R.string.remote_key_home_d),
        KeyButton(RemoteKey.End, R.string.remote_key_end, R.string.remote_key_end_d),
        KeyButton(RemoteKey.PageUp, R.string.remote_key_pgup, R.string.remote_key_pgup_d),
        KeyButton(RemoteKey.PageDown, R.string.remote_key_pgdn, R.string.remote_key_pgdn_d),
    )

/**
 * One finger moves the pointer, a short touch taps, two taps in a row double-click, two fingers scroll,
 * and a finger held still before moving drags (the left button is held until release).
 */
private fun Modifier.trackpadGestures(
    onMove: (Float, Float) -> Unit,
    onScroll: (Float) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHold: (Boolean) -> Unit,
): Modifier =
    pointerInput(Unit) {
        var lastTapMs: Long? = null
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var travelled = 0f
            var holding = false
            var scrolling = false
            while (true) {
                val idle = travelled <= TrackpadLogic.TAP_SLOP_PX && !holding && !scrolling
                val event =
                    if (idle) {
                        withTimeoutOrNull(TrackpadLogic.HOLD_MS) { awaitPointerEvent() }
                    } else {
                        awaitPointerEvent()
                    }
                if (event == null) {
                    holding = true
                    onHold(true)
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    when {
                        holding -> onHold(false)
                        scrolling || travelled > TrackpadLogic.TAP_SLOP_PX -> Unit
                        else -> {
                            val upMs = event.changes.first().uptimeMillis
                            if (TrackpadLogic.isDoubleTap(lastTapMs, upMs)) {
                                lastTapMs = null
                                onDoubleTap()
                            } else {
                                lastTapMs = upMs
                                onTap()
                            }
                        }
                    }
                    break
                }
                if (pressed.size >= 2) {
                    scrolling = true
                    val dy = pressed.map { it.positionChange().y }.average().toFloat()
                    if (dy != 0f) onScroll(dy)
                    event.changes.forEach { it.consume() }
                } else if (!scrolling) {
                    val change = pressed.first()
                    val delta = change.positionChange()
                    travelled += hypot(delta.x, delta.y)
                    if (travelled > TrackpadLogic.TAP_SLOP_PX || holding) {
                        if (delta != Offset.Zero) onMove(delta.x, delta.y)
                        change.consume()
                    }
                }
            }
        }
    }
