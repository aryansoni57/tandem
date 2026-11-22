// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tandem.core.device.BatteryState
import app.tandem.core.device.DeviceState

/** Device detail: battery, ping, send clipboard, ring the laptop, and unpair. Shown for paired devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    device: DeviceState,
    battery: BatteryState?,
    message: String?,
    onPing: () -> Unit,
    onSendClipboard: () -> Unit,
    onRingLaptop: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (device.reachable) "Connected" else "Not connected",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(batteryLine(battery), style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPing, enabled = device.reachable) { Text("Ping") }
                Button(onClick = onSendClipboard, enabled = device.reachable) { Text("Send clipboard") }
            }
            Button(onClick = onRingLaptop, enabled = device.reachable) { Text("Ring laptop") }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onUnpair) {
                Text("Unpair", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun batteryLine(battery: BatteryState?): String =
    when {
        battery == null -> "Battery: unknown"
        battery.low -> "Battery: ${battery.level}% (low)"
        battery.charging -> "Battery: ${battery.level}%, charging"
        else -> "Battery: ${battery.level}%"
    }
