// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.clipboard

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.tandem.core.device.DeviceManager
import app.tandem.plugins.clipboard.ClipboardSync
import app.tandem.plugins.clipboard.describe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A transparent activity that reads the clipboard once it has focus, sends it to the connected
 * paired devices, and finishes. Android 10 and later allow clipboard reads only from a focused app,
 * so the quick-settings tile opens this activity instead of reading in the background.
 */
@AndroidEntryPoint
class SendClipboardActivity : ComponentActivity() {
    @Inject lateinit var clipboardSync: ClipboardSync

    @Inject lateinit var deviceManager: DeviceManager

    private var attempted = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || attempted) return
        attempted = true
        lifecycleScope.launch {
            val targets =
                deviceManager.deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
            val outcome = clipboardSync.sendLocal(targets)
            Toast.makeText(this@SendClipboardActivity, outcome.describe(), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
