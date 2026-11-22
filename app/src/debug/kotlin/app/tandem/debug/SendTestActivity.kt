// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import app.tandem.transfer.ShareActions
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Debug builds only. Sends files from the app's external files dir through the same path as the
 * share sheet. Extras: "paths" (absolute paths, comma-separated), optional "deviceId" (default: first
 * connected device). Start with: am start -n app.tandem/app.tandem.debug.SendTestActivity
 * --es paths /a,/b
 */
@AndroidEntryPoint
class SendTestActivity : ComponentActivity() {
    @Inject lateinit var actions: ShareActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = getExternalFilesDir(null)?.canonicalFile
        val paths = intent.getStringExtra("paths").orEmpty().split(",").filter { it.isNotBlank() }
        val files =
            paths.map { File(it).canonicalFile }
                .filter { root != null && it.isFile && it.path.startsWith(root.path + File.separator) }
        val deviceId = intent.getStringExtra("deviceId") ?: actions.connectedDeviceId()
        Log.d("Tandem:debug", "send test: device=$deviceId files=${files.size} of ${paths.size}")
        if (deviceId != null && files.isNotEmpty()) actions.sendLocalFiles(deviceId, files)
        finish()
    }
}
