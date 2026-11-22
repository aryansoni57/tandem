// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.core.device.BatteryState
import app.tandem.core.device.DeviceState
import app.tandem.designsystem.component.accessibleName
import app.tandem.designsystem.component.BatteryBars
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.component.SectionHeader
import app.tandem.designsystem.component.TandemListRow
import app.tandem.designsystem.component.TandemTag
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.protocol.DeviceType
import app.tandem.sample.SampleData
import app.tandem.ui.pairing.PairingSheet

/**
 * Devices and battery. The elevated card shows the first paired device with the laptop's real battery.
 * The bar chart history is sample data until battery history is stored.
 */
@Composable
fun DevicesEntry(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val batteries by viewModel.peerBatteries.collectAsState()
    val pairingTarget by viewModel.pairingTargetId.collectAsState()
    val noneNearby = stringResource(R.string.devices_none_nearby)
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { onMessage(it) }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= LIST_DETAIL_MIN_WIDTH_DP
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val list: @Composable () -> Unit = {
        DevicesScreen(
            devices = devices,
            batteries = batteries,
            pairingTargetId = pairingTarget,
            onBack = onBack,
            onOpenDevice = { device ->
                when {
                    !device.paired -> viewModel.openPairing(device.deviceId)
                    // Wide screens show the detail beside the list instead of opening a new screen.
                    wide -> selected = device.deviceId
                    else -> onOpenDevice(device.deviceId)
                }
            },
            onRing = viewModel::ringLaptop,
            onUnpair = viewModel::unpair,
            onPairNearby = { if (!viewModel.pairNearby()) onMessage(noneNearby) },
            onClosePairing = viewModel::closePairing,
            onRequestPairing = viewModel::requestPairing,
            onAcceptPairing = viewModel::acceptPairing,
            onRejectPairing = viewModel::rejectPairing,
            onRefresh = viewModel::refresh,
        )
    }
    if (wide) {
        ListDetailPanes(
            list = list,
            detail =
                selected?.let { id ->
                    { DeviceDetailEntry(deviceId = id, onBack = { selected = null }, onMessage = onMessage) }
                },
            placeholder = stringResource(R.string.devices_pick_one),
        )
    } else {
        list()
    }
}

/** Stateless Devices screen: elevated device card, other devices, works-on chips and the Pair FAB. */
@Composable
fun DevicesScreen(
    devices: List<DeviceState>,
    batteries: Map<String, BatteryState>,
    pairingTargetId: String?,
    onBack: () -> Unit,
    onOpenDevice: (DeviceState) -> Unit,
    onRing: () -> Unit,
    onUnpair: (String) -> Unit,
    onPairNearby: () -> Unit,
    onClosePairing: () -> Unit,
    onRequestPairing: (String) -> Unit,
    onAcceptPairing: (String) -> Unit,
    onRejectPairing: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val primary = devices.firstOrNull { it.paired && it.reachable } ?: devices.firstOrNull { it.paired }
    val others = devices.filter { it.deviceId != primary?.deviceId }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.devices_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPairNearby,
                modifier = Modifier.accessibleName(stringResource(R.string.devices_pair_fab)),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.devices_pair_fab)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    padding,
                ).verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (primary != null) {
                PrimaryDeviceCard(
                    device = primary,
                    battery = batteries[primary.deviceId],
                    onRing = onRing,
                    onUnpair = { onUnpair(primary.deviceId) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                )
            } else {
                Text(
                    stringResource(R.string.devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            others.forEach { device ->
                TandemListRow(
                    headline = device.name,
                    supporting = deviceStatus(device),
                    onClick = { onOpenDevice(device) },
                    leading = {
                        LeadingTile(square = true, icon = device.type.icon(), contentDescription = null)
                    },
                    trailing = {
                        if (device.paired) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        } else {
                            FilledTonalButton(
                                onClick = { onOpenDevice(device) },
                                modifier = Modifier.height(40.dp),
                            ) { Text(stringResource(R.string.devices_pair)) }
                        }
                    },
                )
            }

            SectionHeader(stringResource(R.string.devices_works_on))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {
                }, label = {
                    Text(stringResource(R.string.devices_os_linux))
                }, leadingIcon = {
                    Icon(
                        TandemIcons.Laptop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                })
                AssistChip(onClick = {
                }, label = {
                    Text(stringResource(R.string.devices_os_windows))
                }, leadingIcon = {
                    Icon(
                        TandemIcons.Desktop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                })
                AssistChip(onClick = {
                }, label = {
                    Text(stringResource(R.string.devices_os_macos))
                }, leadingIcon = {
                    Icon(
                        TandemIcons.Laptop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                })
            }
        }
    }

    val pairing = devices.firstOrNull { it.deviceId == pairingTargetId }
    if (pairing != null) {
        PairingSheet(
            device = pairing,
            onRequest = { onRequestPairing(pairing.deviceId) },
            onAccept = { onAcceptPairing(pairing.deviceId) },
            onReject = { onRejectPairing(pairing.deviceId) },
            onUnpair = { onUnpair(pairing.deviceId) },
            onDismiss = onClosePairing,
        )
    }
}

@Composable
private fun PrimaryDeviceCard(
    device: DeviceState,
    battery: BatteryState?,
    onRing: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingTile(
                    square = true,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = device.type.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.devices_paired_subtitle,
                            device.type.wireName.replaceFirstChar { it.uppercase() },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TandemTag(
                    label = stringResource(if (device.reachable) R.string.devices_online else R.string.devices_offline),
                    icon = TandemIcons.Wifi,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.devices_battery_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    battery?.let {
                        "${it.level}%"
                    } ?: stringResource(R.string.value_unknown),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            BatteryBars(values = SampleData.batteryHistory)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.devices_history_start),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.devices_history_now), style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRing) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.devices_ring))
                }
                OutlinedButton(onClick = onUnpair) {
                    Text(stringResource(R.string.devices_unpair), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun deviceStatus(device: DeviceState): String =
    stringResource(
        when {
            device.reachable && device.paired -> R.string.device_status_paired_reachable
            device.reachable -> R.string.device_status_reachable
            device.paired -> R.string.device_status_paired_offline
            else -> R.string.device_status_nearby
        },
    )

/** Icon for a device class. Phones and tablets use the handset glyphs, desktops and laptops the monitor and laptop. */
fun DeviceType.icon(): ImageVector =
    when (this) {
        DeviceType.PHONE -> TandemIcons.Smartphone
        DeviceType.TABLET -> TandemIcons.Tablet
        DeviceType.DESKTOP, DeviceType.TV -> TandemIcons.Desktop
        DeviceType.LAPTOP -> TandemIcons.Laptop
    }
