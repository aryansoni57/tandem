// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share.target

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import app.tandem.R
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import app.tandem.designsystem.theme.TandemTheme
import app.tandem.transfer.ShareActions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Direct-share target for ACTION_SEND and ACTION_SEND_MULTIPLE. The sheet lists paired devices.
 * Choosing one sends the files, text or link to it. Content URIs are opened before the activity ends.
 */
@AndroidEntryPoint
class ShareTargetActivity : ComponentActivity() {
    @Inject lateinit var actions: ShareActions

    @Inject lateinit var deviceManager: DeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val payload = sharePayload(intent)
        if (payload is SharePayload.Empty) {
            finish()
            return
        }
        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (shortcutId != null) {
            // Chosen as a direct-share target: skip the sheet and send to that device.
            lifecycleScope.launch {
                val device = ShareShortcuts.target(shortcutId, deviceManager.deviceStates.first())
                if (device != null) send(device, payload) else showSheet(payload)
            }
            return
        }
        showSheet(payload)
    }

    private fun showSheet(payload: SharePayload) {
        setContent {
            TandemTheme {
                ShareTargetRoute(
                    payload = payload,
                    onSend = { device -> send(device, payload) },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun send(
        device: DeviceState,
        payload: SharePayload,
    ) {
        val what: String =
            when (payload) {
                is SharePayload.Files -> {
                    val opened = actions.sendFiles(device.deviceId, payload.uris)
                    if (opened == 0) {
                        Toast.makeText(this, R.string.share_files_unreadable, Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                    resources.getQuantityString(R.plurals.share_items_count, opened, opened)
                }
                is SharePayload.Link -> {
                    actions.sendLink(device.deviceId, payload.url)
                    getString(R.string.share_target_link)
                }
                is SharePayload.Text -> {
                    actions.sendText(device.deviceId, payload.text)
                    getString(R.string.share_target_text)
                }
                SharePayload.Empty -> return
            }
        Toast.makeText(this, getString(R.string.share_target_sending, what, device.name), Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        /** What the share intent carries. A web link sent as text becomes a link. */
        fun sharePayload(intent: Intent): SharePayload {
            val streams: List<Uri> =
                when (intent.action) {
                    Intent.ACTION_SEND_MULTIPLE ->
                        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    Intent.ACTION_SEND ->
                        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    else -> emptyList()
                }
            if (streams.isNotEmpty()) return SharePayload.Files(streams)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            return when {
                text.isEmpty() -> SharePayload.Empty
                isWebLink(text) -> SharePayload.Link(text)
                else -> SharePayload.Text(text)
            }
        }

        /** A single token with an http or https scheme. */
        fun isWebLink(text: String): Boolean {
            if (text.any { it.isWhitespace() }) return false
            val scheme = Uri.parse(text).scheme?.lowercase()
            return scheme == "http" || scheme == "https"
        }
    }
}

sealed interface SharePayload {
    data class Files(val uris: List<Uri>) : SharePayload

    data class Text(val text: String) : SharePayload

    data class Link(val url: String) : SharePayload

    data object Empty : SharePayload
}

@Composable
private fun ShareTargetRoute(
    payload: SharePayload,
    onSend: (DeviceState) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ShareTargetViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val count = (payload as? SharePayload.Files)?.uris?.size ?: 1
    ShareTargetSheet(
        devices = devices,
        itemCount = count,
        textOnly = payload !is SharePayload.Files,
        onSend = onSend,
        onDismiss = onDismiss,
    )
}
