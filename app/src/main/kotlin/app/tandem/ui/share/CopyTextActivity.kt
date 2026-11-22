// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.tandem.R
import app.tandem.plugins.share.ShareNotifications

/**
 * Copies received text to the clipboard. The notification action opens this activity, so the write
 * happens while the app has focus, which Android 10 and later require.
 */
class CopyTextActivity : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(ShareNotifications.EXTRA_TEXT) == null) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val text = intent.getStringExtra(ShareNotifications.EXTRA_TEXT).orEmpty()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Tandem", text))
        Toast.makeText(this, R.string.share_text_copied, Toast.LENGTH_SHORT).show()
        finish()
    }
}
