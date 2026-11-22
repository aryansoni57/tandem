// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.clipboard

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.ClipItem
import app.tandem.state.ClipSource

/** Route entry for the clipboard history screen. */
@Composable
fun ClipboardEntry(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: ClipboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { onMessage(it) }
    }
    ClipboardScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onFilter = viewModel::onFilterSelected,
        onAutoSyncToggle = viewModel::setAutoSync,
        onSendCurrent = viewModel::sendCurrent,
        onCopy = viewModel::copy,
    )
}

/** Search, shared-clipboard card with auto-sync, filter chips and the three-line list. */
@Composable
fun ClipboardScreen(
    state: ClipboardUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilter: (ClipFilter) -> Unit,
    onAutoSyncToggle: (Boolean) -> Unit,
    onSendCurrent: () -> Unit,
    onCopy: (ClipItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clipboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.clipboard_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                )
            }
            item {
                SharedClipboardCard(
                    autoSync = state.autoSync,
                    onAutoSyncToggle = onAutoSyncToggle,
                    onSendCurrent = onSendCurrent,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                )
            }
            item {
                // FlowRow: at a large font size the chips wrap to a second line instead of breaking their words.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClipFilter.entries.forEach { chip ->
                        FilterChip(
                            selected = state.filter == chip,
                            onClick = { onFilter(chip) },
                            label = { Text(stringResource(chip.labelRes())) },
                        )
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                ClipRow(item = item, onCopy = { onCopy(item) })
            }
        }
    }
}

@Composable
private fun SharedClipboardCard(
    autoSync: Boolean,
    onAutoSyncToggle: (Boolean) -> Unit,
    onSendCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TandemIcons.Sync, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.clipboard_shared_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.clipboard_shared_body), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = autoSync,
                    onCheckedChange = onAutoSyncToggle,
                    modifier = Modifier.accessibleName(stringResource(R.string.clipboard_shared_title)),
                )
            }
            FilledTonalButton(onClick = onSendCurrent, modifier = Modifier.fillMaxWidth()) {
                Icon(TandemIcons.ContentPasteGo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clipboard_send_current))
            }
        }
    }
}

@Composable
private fun ClipRow(
    item: ClipItem,
    onCopy: () -> Unit,
) {
    val fromPhone = item.source == ClipSource.Phone
    TandemListRow(
        headline = item.text,
        supporting = item.timeLabel,
        overline = null,
        leading = {
            LeadingTile(
                icon = if (fromPhone) TandemIcons.Smartphone else TandemIcons.Laptop,
                container =
                    if (fromPhone) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                content =
                    if (fromPhone) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                contentDescription = null,
            )
        },
        trailing = {
            IconButton(onClick = onCopy) {
                Icon(TandemIcons.ContentCopy, contentDescription = stringResource(R.string.clipboard_copy_item))
            }
        },
    )
}

private fun ClipFilter.labelRes(): Int =
    when (this) {
        ClipFilter.All -> R.string.clipboard_filter_all
        ClipFilter.FromLaptop -> R.string.clipboard_filter_laptop
        ClipFilter.FromPhone -> R.string.clipboard_filter_phone
    }
