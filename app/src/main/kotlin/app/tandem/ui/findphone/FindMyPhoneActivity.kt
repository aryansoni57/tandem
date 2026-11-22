// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.findphone

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.tandem.designsystem.theme.TandemTheme
import app.tandem.plugins.findmyphone.PhoneRinger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen ring for "find my phone". It shows over the lock screen and turns the display on. The sound,
 * vibration and flashlight come from [PhoneRinger]; this screen stops them and closes when they end.
 */
@AndroidEntryPoint
class FindMyPhoneActivity : ComponentActivity() {
    @Inject lateinit var ringer: PhoneRinger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(
                Context.KEYGUARD_SERVICE,
            )?.let { (it as? KeyguardManager)?.requestDismissKeyguard(this, null) }
        }
        enableEdgeToEdge()
        lifecycleScope.launch {
            // Ringing ended (stopped from the laptop or the notification): nothing left to show.
            ringer.ringing.first { !it }
            finish()
        }
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(app.tandem.R.string.find_default_device)
        setContent {
            TandemTheme {
                FindMyPhoneScreen(deviceName = deviceName, onStop = { ringer.stop() })
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "app.tandem.extra.DEVICE_NAME"
    }
}
