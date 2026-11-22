// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.share.target

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tandem.R
import app.tandem.core.device.DeviceState
import app.tandem.designsystem.component.LeadingTile
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.ui.devices.icon

/** The "Send with Tandem" sheet. Offline devices are shown dimmed and cannot be picked. */
@Composable
fun ShareTargetSheet(
    devices: List<DeviceState>,
    itemCount: Int,
    textOnly: Boolean,
    onSend: (DeviceState) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        if (textOnly) {
                            stringResource(
                                R.string.share_target_text,
                            )
                        } else {
                            pluralStringResource(R.plurals.share_items_count, itemCount, itemCount)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.share_target_subtitle), style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                stringResource(R.string.share_target_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )

            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.share_target_no_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            devices.forEach { device ->
                DeviceTarget(device = device, onClick = { onSend(device) })
            }
        }
    }
}

@Composable
private fun DeviceTarget(
    device: DeviceState,
    onClick: () -> Unit,
) {
    val enabled = device.reachable && device.paired
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LeadingTile(
            icon = device.type.icon(),
            contentDescription = null,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            square = false,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (enabled) R.string.share_target_online else R.string.share_target_offline),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(TandemIcons.SyncAlt, contentDescription = null)
    }
}
