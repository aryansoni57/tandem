// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R

/** Route entry for a paired device's detail page. Pops itself if the device disappears. */
@Composable
fun DeviceDetailEntry(
    deviceId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val batteries by viewModel.peerBatteries.collectAsState()
    val message by viewModel.message.collectAsState()
    val device = devices.firstOrNull { it.deviceId == deviceId }
    val gone = stringResource(R.string.devices_gone)
    LaunchedEffect(device == null) {
        if (device == null) onMessage(gone)
    }
    if (device == null) {
        LaunchedEffect(deviceId) { onBack() }
        return
    }
    DeviceDetailScreen(
        device = device,
        battery = batteries[device.deviceId],
        message = message,
        onPing = { viewModel.ping(device.deviceId) },
        onSendClipboard = { viewModel.sendClipboard(device.deviceId) },
        onRingLaptop = { viewModel.ringLaptop(device.deviceId) },
        onUnpair = { viewModel.unpair(device.deviceId) },
        onBack = onBack,
    )
}
