// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.SectionHeader
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.SharedStorage
import app.tandem.state.Transfer

/** Route entry for the Share tab. */
@Composable
fun ShareEntry(
    onMessage: (String) -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { onMessage(it) }
    }
    ShareScreen(
        state = state,
        onLinkChange = viewModel::onLinkChange,
        onSendLink = viewModel::sendLink,
        onCancel = viewModel::cancel,
        onStorageToggle = { on ->
            viewModel.setStorageEnabled(on)
            // Turning sharing on without the permission sends the user to grant it.
            if (on && state.storage?.hasAccess == false) context.startActivity(AllFilesAccess.settingsIntent(context))
        },
        onAutoSyncToggle = viewModel::setAutoSyncClipboard,
        onSendClipboard = viewModel::sendClipboard,
        onAllowAccess = { context.startActivity(AllFilesAccess.settingsIntent(context)) },
        onAddFolder = viewModel::addSharedFolder,
        onRemoveFolder = viewModel::removeSharedFolder,
    )
}

/** Share tab: link field, transfers, shared clipboard and phone storage. */
@Composable
fun ShareScreen(
    state: ShareUiState,
    onLinkChange: (String) -> Unit,
    onSendLink: () -> Unit,
    onCancel: (String) -> Unit,
    onStorageToggle: (Boolean) -> Unit,
    onAutoSyncToggle: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onAllowAccess: () -> Unit,
    onAddFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.share_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            OutlinedTextField(
                value = state.link,
                onValueChange = onLinkChange,
                label = { Text(stringResource(R.string.share_link_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendLink() }),
                trailingIcon = {
                    IconButton(onClick = onSendLink, enabled = state.linkValid) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.share_send_link),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            )

            SectionHeader(stringResource(R.string.share_transfers))
            state.transfers.forEach { transfer ->
                TransferRow(transfer = transfer, onCancel = { onCancel(transfer.id) })
            }

            ClipboardCard(
                autoSync = state.autoSyncClipboard,
                onAutoSyncToggle = onAutoSyncToggle,
                onSendClipboard = onSendClipboard,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
            )

            state.storage?.let { storage ->
                SectionHeader(stringResource(R.string.share_storage_header))
                StorageCard(
                    storage = storage,
                    onToggle = onStorageToggle,
                    onAllowAccess = onAllowAccess,
                    onAddFolder = onAddFolder,
                    onRemoveFolder = onRemoveFolder,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun TransferRow(
    transfer: Transfer,
    onCancel: () -> Unit,
) {
    val running = transfer.progress != null
    TandemListRow(
        headline = transfer.name,
        supporting = transfer.supportingText(),
        leading = {
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { transfer.progress ?: 0f },
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 4.dp,
                    )
                }
            } else {
                LeadingTile(icon = transfer.kind.icon(), contentDescription = null)
            }
        },
        trailing = {
            if (running) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.share_cancel_transfer))
                }
            }
        },
    )
    if (running) {
        LinearProgressIndicator(
            progress = { transfer.progress ?: 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun ClipboardCard(
    autoSync: Boolean,
    onAutoSyncToggle: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
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
                Icon(TandemIcons.SyncAlt, contentDescription = null)
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
            FilledTonalButton(onClick = onSendClipboard, modifier = Modifier.fillMaxWidth()) {
                Icon(TandemIcons.ContentPasteGo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clipboard_send_current))
            }
        }
    }
}

@Composable
private fun StorageCard(
    storage: SharedStorage,
    onToggle: (Boolean) -> Unit,
    onAllowAccess: () -> Unit,
    onAddFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TandemIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.storage_browse_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.storage_browse_body), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = storage.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.accessibleName(stringResource(R.string.storage_browse_title)),
                )
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { (storage.usedGb / storage.totalGb).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    stringResource(R.string.storage_used, storage.usedGb.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.storage_total, storage.totalGb.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // FlowRow: with four chips the last one wraps to a new line instead of squeezing its label.
            FlowRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storage.folders.forEach { folder ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveFolder(folder) },
                        label = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.storage_remove_folder, folder),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                AssistChip(
                    onClick = { picking = true },
                    label = { Text(stringResource(R.string.storage_add_folder)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
            if (storage.enabled && !storage.hasAccess) {
                Text(
                    stringResource(R.string.storage_needs_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(onClick = onAllowAccess) { Text(stringResource(R.string.storage_allow_access)) }
            }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.storage_add_title)) },
            text = {
                Column {
                    if (storage.candidates.isEmpty()) {
                        Text(stringResource(R.string.storage_none_to_add))
                    }
                    storage.candidates.forEach { name ->
                        TextButton(
                            onClick = {
                                onAddFolder(name)
                                picking = false
                            },
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
