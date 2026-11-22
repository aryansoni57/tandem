// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.TandemConfirmDialog
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.CommandIcon
import app.tandem.state.RunCommand

/** Route entry for the run commands list. */
@Composable
fun RunCommandsEntry(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: RunCommandsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { onMessage(it) }
    }
    RunCommandsScreen(
        state = state,
        onBack = onBack,
        onRun = viewModel::request,
        onConfirm = viewModel::confirmPending,
        onCancel = viewModel::cancelPending,
        onSetup = viewModel::setup,
    )
}

/** Commands on the laptop. Destructive ones (shut down) open a confirm dialog first. */
@Composable
fun RunCommandsScreen(
    state: CommandsUiState,
    onBack: () -> Unit,
    onRun: (RunCommand) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSetup: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.commands_title)) },
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
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            state.commands.forEach { command ->
                TandemListRow(
                    headline = command.name,
                    supporting = command.command,
                    onClick = { onRun(command) },
                    leading = {
                        LeadingTile(
                            square = true,
                            icon = command.icon.toIcon(),
                            container =
                                if (command.destructive) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            content =
                                if (command.destructive) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            contentDescription = null,
                        )
                    },
                    trailing = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                )
            }
            if (state.commands.isEmpty()) {
                Text(
                    stringResource(R.string.commands_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            Text(
                stringResource(R.string.commands_setup_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.canAdd) {
                OutlinedButton(onClick = onSetup, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.commands_add_on_laptop))
                }
            }
        }
    }

    if (state.confirm.pending != null) {
        TandemConfirmDialog(
            title = stringResource(R.string.commands_confirm_title, state.confirm.pending.name),
            text = stringResource(R.string.commands_confirm_body),
            confirmLabel = stringResource(R.string.commands_confirm_run),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = onConfirm,
            onDismiss = onCancel,
            icon = TandemIcons.Power,
            destructive = true,
        )
    }
}

private fun CommandIcon.toIcon() =
    when (this) {
        CommandIcon.Lock -> Icons.Filled.Lock
        CommandIcon.Sleep -> TandemIcons.DarkMode
        CommandIcon.Backup -> TandemIcons.Backup
        CommandIcon.Screenshot -> TandemIcons.ScreenshotMonitor
        CommandIcon.Power -> TandemIcons.Power
        CommandIcon.Generic -> Icons.Filled.Build
    }
