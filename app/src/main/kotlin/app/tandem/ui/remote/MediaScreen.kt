// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.remote

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.NowPlaying

/** Route entry for the media screen. */
@Composable
fun MediaEntry(
    onBack: () -> Unit,
    viewModel: MediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    MediaScreen(
        state = state,
        onBack = onBack,
        onTogglePlay = viewModel::togglePlayPause,
        onSkip = viewModel::skip,
        onSeekPreview = viewModel::previewSeek,
        onSeekCommit = viewModel::commitSeek,
        onVolume = viewModel::setVolume,
        onPlayer = viewModel::selectPlayer,
    )
}

/** Media screen: art, seek slider, morphing play button, volume and player chips. */
@Composable
fun MediaScreen(
    state: MediaUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onSeekPreview: (Float) -> Unit,
    onSeekCommit: () -> Unit,
    onVolume: (Int) -> Unit,
    onPlayer: (String) -> Unit,
) {
    val now: NowPlaying = state.playing
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.media_playing_on_laptop),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 16.dp)
                        .size(260.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TandemIcons.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(96.dp),
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp)) {
                Text(now.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.media_artist_source, now.artist, now.source),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Slider(
                    value = state.seekFraction,
                    onValueChange = onSeekPreview,
                    onValueChangeFinished = onSeekCommit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatTime((state.seekFraction * now.durationMs).toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatTime(now.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = { onSkip(false) }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        TandemIcons.SkipPrevious,
                        contentDescription = stringResource(R.string.media_previous),
                        modifier = Modifier.size(32.dp),
                    )
                }
                MorphingPlayButton(playing = now.playing, onClick = onTogglePlay)
                IconButton(onClick = { onSkip(true) }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        TandemIcons.SkipNext,
                        contentDescription = stringResource(R.string.media_next),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    TandemIcons.VolumeDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = now.volume / 100f,
                    onValueChange = { onVolume((it * 100).toInt()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(TandemIcons.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.players.forEach { name ->
                    FilterChip(
                        selected = state.playing.source == name,
                        onClick = { onPlayer(name) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }
}

/**
 * Play/pause button whose corner radius morphs between a circle and a rounded square. This stands in
 * for the Material shape morph, because graphics-shapes is not in the offline Gradle cache.
 */
@Composable
fun MorphingPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner by animateIntAsState(targetValue = if (playing) 28 else 50, label = "playCorner")
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(88.dp),
        shape = RoundedCornerShape(percent = corner),
        colors = IconButtonDefaults.filledIconButtonColors(),
    ) {
        Icon(
            if (playing) TandemIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.media_pause else R.string.media_play),
            modifier = Modifier.size(40.dp),
        )
    }
}

/** Position or duration as m:ss. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}
