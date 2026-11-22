// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.icon.TandemIcons

/** Route entry for the presenter remote. */
@Composable
fun PresenterEntry(
    onBack: () -> Unit,
    viewModel: PresenterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val gyro = remember { GyroPointer(context) { dx, dy -> viewModel.onGyro(dx, dy) } }
    // The sensor runs only while the surface is held. Without one, dragging still moves the dot.
    LaunchedEffect(state.pointerActive) { if (state.pointerActive) gyro.start() else gyro.stop() }
    // While this screen is open the volume keys change slides: down is next, up is previous.
    DisposableEffect(Unit) {
        VolumeKeys.handler = { next -> viewModel.step(next) }
        onDispose {
            VolumeKeys.handler = null
            gyro.stop()
            viewModel.setPointerActive(false)
        }
    }
    PresenterScreen(
        state = state,
        onBack = onBack,
        onToggleTimer = { viewModel.toggleTimer() },
        onResetTimer = viewModel::resetTimer,
        onPointerActive = viewModel::setPointerActive,
        onPointerDrag = viewModel::onPointerDrag,
        onStep = viewModel::step,
        onSlideshow = viewModel::slideshow,
    )
}

/** Presentation remote: deck status, hold-to-point surface, large Back and Next buttons, and the timer. */
@Composable
fun PresenterScreen(
    state: PresenterUiState,
    onBack: () -> Unit,
    onToggleTimer: () -> Unit,
    onResetTimer: () -> Unit,
    onPointerActive: (Boolean) -> Unit,
    onPointerDrag: (Float, Float) -> Unit,
    onStep: (Boolean) -> Unit,
    onSlideshow: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presenter_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = onToggleTimer,
                        label = { Text(PresenterTimer.format(state.elapsedMs)) },
                        leadingIcon = { Icon(TandemIcons.Timer, contentDescription = null) },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = onResetTimer) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.presenter_reset_timer))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TandemIcons.Slideshow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.deck.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.deck.total > 0) {
                            stringResource(R.string.presenter_slide_of, state.deck.slide, state.deck.total)
                        } else {
                            stringResource(R.string.presenter_slide, state.deck.slide)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HoldSurface(
                active = state.pointerActive,
                onActive = onPointerActive,
                onDrag = onPointerDrag,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onStep(false) },
                    modifier = Modifier.weight(1f).height(72.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.presenter_back))
                }
                Button(
                    onClick = { onStep(true) },
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Text(stringResource(R.string.presenter_next))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onSlideshow(true) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.presenter_start))
                }
                OutlinedButton(onClick = { onSlideshow(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.presenter_end))
                }
            }

            Text(
                stringResource(R.string.presenter_volume_keys),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Long press to start pointing, then drag. Movement is reported while the surface is held. */
@Composable
private fun HoldSurface(
    active: Boolean,
    onActive: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container =
        if (active) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val content =
        if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(32.dp))
                .background(container)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onActive(true) },
                        onDragEnd = { onActive(false) },
                        onDragCancel = { onActive(false) },
                        onDrag = { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(TandemIcons.AdsClick, contentDescription = null, tint = content, modifier = Modifier.height(40.dp))
            Text(
                stringResource(if (active) R.string.presenter_pointer_active else R.string.presenter_hold_title),
                style = MaterialTheme.typography.titleMedium,
                color = content,
            )
            Text(
                stringResource(R.string.presenter_hold_body),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}
