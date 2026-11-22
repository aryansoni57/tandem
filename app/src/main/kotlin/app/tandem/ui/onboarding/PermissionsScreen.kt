// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * First-run permissions: notifications, battery optimization settings, and local network.
 * Nothing here is mandatory, but each item explains what breaks without it.
 */
@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val notificationsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* the result is read from the system when needed */ }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pairing requests and the connection status use notifications. On Android 13 and newer, " +
                "the connection still runs when they are denied, but its notification is hidden.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(
                onClick = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow notifications") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
        Text(
            "Some manufacturers stop background apps. Turning off battery optimization for Tandem " +
                "keeps devices connected while the screen is off.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open battery settings") }

        Spacer(Modifier.height(16.dp))
        Text("Local network", style = MaterialTheme.typography.titleMedium)
        Text(
            "Discovery uses the local network. On Android 17 and newer, Tandem asks for local network " +
                "access when it starts. Without it, nearby devices cannot be found.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}
