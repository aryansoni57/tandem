// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tandem.core.device.DeviceState
import app.tandem.core.device.PairingUi

/** Bottom sheet with the pairing code, the actions that fit the current state, and the certificate fingerprint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(
    device: DeviceState,
    onRequest: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))

            when (val pairing = device.pairing) {
                is PairingUi.Incoming -> {
                    Text("Pairing request. Check that this code matches the other device:")
                    Text(
                        pairing.verificationKey,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is PairingUi.Requested -> {
                    Text("Waiting for the other device. Its code should show:")
                    Text(
                        pairing.verificationKey.ifEmpty { "--------" },
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is PairingUi.Failed -> Text(pairing.reason, color = MaterialTheme.colorScheme.error)
                PairingUi.Idle ->
                    Text(
                        if (device.paired) "This device is paired." else "This device is not paired.",
                    )
            }

            device.certSha256?.let {
                Spacer(Modifier.height(12.dp))
                Text("Certificate SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            device.tlsVersion?.let {
                Text("Connection: $it", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                val pairing = device.pairing
                when {
                    pairing is PairingUi.Incoming -> {
                        Button(onClick = onAccept) { Text("Accept") }
                        OutlinedButton(onClick = onReject) { Text("Reject") }
                    }
                    device.paired -> OutlinedButton(onClick = onUnpair) { Text("Unpair") }
                    device.reachable && pairing !is PairingUi.Requested -> Button(onClick = onRequest) { Text("Pair") }
                }
            }
        }
    }
}
