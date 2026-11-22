// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.designsystem.theme.TandemTheme
import app.tandem.ui.devices.DevicesViewModel
import app.tandem.ui.onboarding.PermissionsScreen
import app.tandem.ui.remote.VolumeKeys
import app.tandem.ui.shell.TandemShell
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startKey = intent.getStringExtra(EXTRA_START_SCREEN)
        setContent {
            TandemTheme {
                TandemRoot(startKey = startKey)
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = VolumeKeys.onKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = VolumeKeys.onKey(event) || super.onKeyUp(keyCode, event)

    companion object {
        /** Debug aid: opens the app on one screen, for example `--es app.tandem.extra.START_SCREEN clipboard`. */
        const val EXTRA_START_SCREEN = "app.tandem.extra.START_SCREEN"
    }
}

@Composable
private fun TandemRoot(
    startKey: String?,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val onboarded by viewModel.onboarded.collectAsState()
    when (onboarded) {
        null -> Unit
        false -> PermissionsScreen(onContinue = viewModel::finishOnboarding)
        true -> TandemShell(startKey = startKey)
    }
}
