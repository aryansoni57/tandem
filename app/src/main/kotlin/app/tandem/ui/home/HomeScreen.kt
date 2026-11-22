// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.QuickAction
import app.tandem.designsystem.component.SectionHeader
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.NowPlaying
import app.tandem.state.Transfer
import app.tandem.ui.share.icon
import app.tandem.ui.share.supportingText

/** Route entry: wires the Home ViewModel to the stateless [HomeScreen]. */
@Composable
fun HomeEntry(
    onSend: () -> Unit,
    onClipboard: () -> Unit,
    onTrackpad: () -> Unit,
    onMedia: () -> Unit,
    onConnection: () -> Unit,
    onDevices: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { onMessage(it) }
    }
    HomeScreen(
        state = state,
        onSend = onSend,
        onClipboard = onClipboard,
        onTrackpad = onTrackpad,
        onLockPc = viewModel::lockLaptop,
        onMedia = onMedia,
        onTogglePlay = viewModel::togglePlayPause,
        onConnection = onConnection,
        onDevices = onDevices,
    )
}

/** Large top app bar, connected-device hero, quick actions, now playing, recent list and the extended FAB. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSend: () -> Unit,
    onClipboard: () -> Unit,
    onTrackpad: () -> Unit,
    onLockPc: () -> Unit,
    onMedia: () -> Unit,
    onTogglePlay: () -> Unit,
    onConnection: () -> Unit,
    onDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_search))
                    }
                    IconButton(onClick = onDevices) {
                        Icon(
                            TandemIcons.Smartphone,
                            contentDescription = stringResource(R.string.home_devices),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onSend,
                modifier = Modifier.accessibleName(stringResource(R.string.home_fab_send)),
                icon = { Icon(Icons.Filled.Send, contentDescription = null) },
                text = { Text(stringResource(R.string.home_fab_send)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
        ) {
            DeviceHero(state = state, onClick = onConnection, modifier = Modifier.padding(horizontal = 16.dp))

            // FlowRow without weights: four actions share one line, and at a large font size they wrap to two.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickAction(
                    TandemIcons.UploadFile,
                    stringResource(R.string.home_qa_send_file),
                    onSend,
                )
                QuickAction(
                    TandemIcons.ContentPasteGo,
                    stringResource(R.string.home_qa_clipboard),
                    onClipboard,
                )
                QuickAction(
                    TandemIcons.Mouse,
                    stringResource(R.string.home_qa_trackpad),
                    onTrackpad,
                )
                QuickAction(Icons.Filled.Lock, stringResource(R.string.home_qa_lock_pc), onLockPc)
            }

            state.nowPlaying?.let { now ->
                NowPlayingCard(
                    now = now,
                    onOpen = onMedia,
                    onTogglePlay = onTogglePlay,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                )
            }

            SectionHeader(stringResource(R.string.home_today))
            state.today.forEach { transfer -> RecentRow(transfer) }
        }
    }
}

@Composable
private fun DeviceHero(
    state: HomeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.device
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingTile(
                    modifier = Modifier.size(56.dp),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    square = true,
                    icon = TandemIcons.Laptop,
                    contentDescription = null,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device?.name ?: stringResource(R.string.home_no_device),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (device?.reachable == true) {
                            stringResource(R.string.home_connected_status)
                        } else {
                            stringResource(R.string.home_not_connected_status)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (device != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Metric(
                        label = stringResource(R.string.home_metric_battery),
                        value = state.batteryPercent?.let { "$it%" } ?: stringResource(R.string.value_unknown),
                        supporting = if (state.charging) stringResource(R.string.home_charging) else "",
                        modifier = Modifier.weight(1f),
                    )
                    Metric(
                        label = stringResource(R.string.home_metric_latency),
                        value = latencyText(state.latencyMs),
                        supporting = stringResource(R.string.home_latency_route),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun latencyText(ms: Int?): String =
    if (ms == null) stringResource(R.string.value_unknown) else stringResource(R.string.value_ms, ms)

@Composable
private fun Metric(
    label: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (supporting.isNotEmpty()) Text(supporting, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NowPlayingCard(
    now: NowPlaying,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onOpen,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(
                        56.dp,
                    ).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TandemIcons.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    now.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.home_now_playing_on_laptop, now.artist),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (now.playing) TandemIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (now.playing) R.string.media_pause else R.string.media_play),
                )
            }
        }
    }
}

@Composable
private fun RecentRow(transfer: Transfer) {
    TandemListRow(
        headline = transfer.name,
        supporting = transfer.supportingText(),
        leading = {
            LeadingTile(square = true, icon = transfer.kind.icon(), contentDescription = null)
        },
        trailing = { Text(transfer.timeLabel, style = MaterialTheme.typography.labelMedium) },
    )
}
