// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.sync

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.PermissionBanner
import app.tandem.designsystem.component.SectionHeader
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.component.TandemTag
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.CallsSmsSettings
import app.tandem.state.HandledItem
import app.tandem.state.HandledKind
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.SyncApp
import app.tandem.state.SyncCategory

/** Route entry for the Sync tab. */
@Composable
fun SyncEntry(viewModel: SyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    SyncScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onShowOnLaptop = viewModel::setShowOnLaptop,
        onScreenOffOnly = viewModel::setScreenOffOnly,
        onToggleCategory = viewModel::toggleCategory,
        onAppEnabled = viewModel::setAppEnabled,
        onTextFromLaptop = viewModel::setTextFromLaptop,
        onCallAlerts = viewModel::setCallAlerts,
        onPauseMedia = viewModel::setPauseMediaOnCall,
        onDismissSmsBanner = viewModel::dismissSmsBanner,
    )
}

/** Sync tab: segmented control, then notifications (apps and category chips) or calls and SMS. */
@Composable
fun SyncScreen(
    state: SyncUiState,
    onSelectTab: (SyncTab) -> Unit,
    onShowOnLaptop: (Boolean) -> Unit,
    onScreenOffOnly: (Boolean) -> Unit,
    onToggleCategory: (SyncCategory) -> Unit,
    onAppEnabled: (String, Boolean) -> Unit,
    onTextFromLaptop: (Boolean) -> Unit,
    onCallAlerts: (Boolean) -> Unit,
    onPauseMedia: (Boolean) -> Unit,
    onDismissSmsBanner: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
            ) {
                SyncTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = state.tab == tab,
                        onClick = { onSelectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SyncTab.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    if (tab == SyncTab.Notifications) {
                                        R.string.sync_tab_notifications
                                    } else {
                                        R.string.sync_tab_calls
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            when (state.tab) {
                SyncTab.Notifications ->
                    NotificationsContent(
                        settings = state.notifications,
                        apps = state.apps,
                        selected = state.categories,
                        onShowOnLaptop = onShowOnLaptop,
                        onScreenOffOnly = onScreenOffOnly,
                        onToggleCategory = onToggleCategory,
                        onAppEnabled = onAppEnabled,
                    )
                SyncTab.CallsAndSms ->
                    CallsContent(
                        state = state,
                        onTextFromLaptop = onTextFromLaptop,
                        onCallAlerts = onCallAlerts,
                        onPauseMedia = onPauseMedia,
                        onDismissSmsBanner = onDismissSmsBanner,
                    )
            }
        }
    }
}

@Composable
private fun NotificationsContent(
    settings: NotificationSyncSettings,
    apps: List<SyncApp>,
    selected: Set<SyncCategory>,
    onShowOnLaptop: (Boolean) -> Unit,
    onScreenOffOnly: (Boolean) -> Unit,
    onToggleCategory: (SyncCategory) -> Unit,
    onAppEnabled: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer =
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        accessGranted = NotificationAccess.isGranted(context)
                    }
                }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        if (!accessGranted) {
            PermissionBanner(
                icon = Icons.Filled.Info,
                message = stringResource(R.string.sync_access_message),
                primaryLabel = stringResource(R.string.sync_access_allow),
                onPrimary = { context.startActivity(NotificationAccess.settingsIntent()) },
            )
        }
        SwitchRow(
            icon = TandemIcons.Sync,
            title = stringResource(R.string.sync_show_on_laptop),
            body = stringResource(R.string.sync_show_on_laptop_body),
            checked = settings.showOnLaptop,
            onCheckedChange = onShowOnLaptop,
        )
        SwitchRow(
            icon = TandemIcons.DoNotDisturb,
            title = stringResource(R.string.sync_screen_off_only),
            body = stringResource(R.string.sync_screen_off_only_body),
            checked = settings.screenOffOnly,
            onCheckedChange = onScreenOffOnly,
        )

        SectionHeader(stringResource(R.string.sync_apps_header))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncCategory.entries.forEach { category ->
                FilterChip(
                    selected = category in selected,
                    onClick = { onToggleCategory(category) },
                    label = { Text(stringResource(category.labelRes())) },
                )
            }
        }
        apps.forEach { app ->
            TandemListRow(
                headline = app.name,
                supporting = app.note,
                leading = {
                    LeadingTile(
                        container = app.tileColor(),
                        content = MaterialTheme.colorScheme.onPrimary,
                        initials = app.initial,
                        contentDescription = null,
                    )
                },
                trailing = {
                    Switch(
                        checked = app.enabled,
                        onCheckedChange = { onAppEnabled(app.id, it) },
                        modifier = Modifier.accessibleName(app.name),
                    )
                },
            )
        }
    }
}

@Composable
private fun CallsContent(
    state: SyncUiState,
    onTextFromLaptop: (Boolean) -> Unit,
    onCallAlerts: (Boolean) -> Unit,
    onPauseMedia: (Boolean) -> Unit,
    onDismissSmsBanner: () -> Unit,
) {
    val calls: CallsSmsSettings = state.calls
    val context = androidx.compose.ui.platform.LocalContext.current
    var callsGranted by remember { mutableStateOf(CallPermissions.isGranted(context)) }
    val askForCalls =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        ) { callsGranted = CallPermissions.isGranted(context) }
    var smsGranted by remember { mutableStateOf(SmsPermissions.isGranted(context)) }
    val askForSms =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        ) { smsGranted = SmsPermissions.isGranted(context) }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (calls.callAlerts && !callsGranted) {
            PermissionBanner(
                icon = Icons.Filled.Call,
                message = stringResource(R.string.calls_phone_permission_message),
                primaryLabel = stringResource(R.string.calls_phone_allow),
                onPrimary = { askForCalls.launch(CallPermissions.ALL) },
            )
        }
        if (calls.textFromLaptop && !smsGranted && !state.smsBannerHidden) {
            PermissionBanner(
                icon = Icons.Filled.Info,
                message = stringResource(R.string.calls_sms_permission_message),
                primaryLabel = stringResource(R.string.calls_sms_allow),
                onPrimary = { askForSms.launch(SmsPermissions.ALL) },
                secondaryLabel = stringResource(R.string.calls_sms_not_now),
                onSecondary = onDismissSmsBanner,
            )
        }
        SwitchRow(
            icon = TandemIcons.ChatBubble,
            title = stringResource(R.string.calls_text_from_laptop),
            body = stringResource(R.string.calls_text_from_laptop_body),
            checked = calls.textFromLaptop,
            onCheckedChange = onTextFromLaptop,
        )
        SwitchRow(
            icon = Icons.Filled.Call,
            title = stringResource(R.string.calls_incoming_alerts),
            body = stringResource(R.string.calls_incoming_alerts_body),
            checked = calls.callAlerts,
            onCheckedChange = onCallAlerts,
        )
        SwitchRow(
            icon = TandemIcons.PauseCircle,
            title = stringResource(R.string.calls_pause_media),
            body = stringResource(R.string.calls_pause_media_body),
            checked = calls.pauseMediaOnCall,
            onCheckedChange = onPauseMedia,
        )

        SectionHeader(stringResource(R.string.calls_handled_header))
        state.handled.forEach { item -> HandledRow(item) }
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    TandemListRow(
        headline = title,
        supporting = body,
        leading = { LeadingTile(icon = icon, contentDescription = null) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.accessibleName(title))
        },
    )
}

@Composable
private fun HandledRow(item: HandledItem) {
    val (tagLabel, tagIcon) =
        when (item.kind) {
            HandledKind.Sms -> stringResource(R.string.calls_tag_sms) to TandemIcons.ChatBubble
            HandledKind.Missed -> stringResource(R.string.calls_tag_missed) to Icons.Filled.Call
            HandledKind.Messages -> stringResource(R.string.calls_tag_messages) to TandemIcons.ChatBubble
        }
    val missed = item.kind == HandledKind.Missed
    TandemListRow(
        headline = item.title,
        supporting = item.detail,
        overline = null,
        leading = {
            if (item.initials != null) {
                LeadingTile(initials = item.initials, contentDescription = null)
            } else {
                LeadingTile(
                    icon = if (missed) Icons.Filled.Call else TandemIcons.Smartphone,
                    container =
                        if (missed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                    content =
                        if (missed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                    contentDescription = null,
                )
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(item.timeLabel, style = MaterialTheme.typography.labelMedium)
                TandemTag(
                    label = tagLabel,
                    icon = tagIcon,
                    container =
                        if (missed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    content =
                        if (missed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                )
            }
        },
    )
}

@Composable
private fun SyncApp.tileColor(): androidx.compose.ui.graphics.Color =
    when (category) {
        SyncCategory.Messaging -> MaterialTheme.colorScheme.primary
        SyncCategory.Work -> MaterialTheme.colorScheme.tertiary
        SyncCategory.Shopping -> MaterialTheme.colorScheme.secondary
    }

private fun SyncCategory.labelRes(): Int =
    when (this) {
        SyncCategory.Messaging -> R.string.sync_category_messaging
        SyncCategory.Work -> R.string.sync_category_work
        SyncCategory.Shopping -> R.string.sync_category_shopping
    }
