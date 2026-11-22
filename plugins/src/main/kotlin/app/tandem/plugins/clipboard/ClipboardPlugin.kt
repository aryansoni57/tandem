// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.core.plugin.Plugin
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.core.plugin.PluginContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ClipboardBody
import app.tandem.protocol.body.ClipboardFileBody
import app.tandem.protocol.body.ClipboardConnectBody
import app.tandem.protocol.share.FileNames
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferControl
import kotlinx.coroutines.launch

/**
 * Receives clipboard text from the peer and writes it to the local clipboard. Sends our latest
 * clip on connect. Phone to peer sends happen only on a user action, through [ClipboardSync].
 */
class ClipboardPlugin(
    private val context: PluginContext,
    private val store: ClipboardStore,
    private val system: SystemClipboard,
    private val history: ClipHistory,
    private val endpoint: PayloadEndpoint? = null,
    private val files: ClipboardFileStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : Plugin {
    override suspend fun onCreate() {
        val latest = store.latest() ?: return
        context.send(ClipboardConnectBody(latest.text, latest.timestamp).toPacket())
    }

    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        when (packet.type) {
            PacketTypes.CLIPBOARD -> {
                val text = ClipboardBody.parse(packet.body)?.content ?: return true
                apply(text)
            }
            PacketTypes.CLIPBOARD_CONNECT -> {
                val body = ClipboardConnectBody.parse(packet.body) ?: return true
                // Zero means no change is known. An older or equal clip never overwrites ours.
                if (body.timestamp <= 0 || body.timestamp <= (store.latest()?.timestamp ?: 0L)) return true
                apply(body.content)
            }
            PacketTypes.CLIPBOARD_FILE -> receiveFile(packet)
            else -> return false
        }
        return true
    }

    /** Streams the payload into the staging store, then puts it on the clipboard. Runs off the packet loop. */
    private fun receiveFile(packet: NetworkPacket) {
        val endpoint = endpoint ?: return
        val files = files ?: return
        val body = ClipboardFileBody.parse(packet.body) ?: return
        val size = packet.payloadSize ?: return
        val port = packet.payloadPort ?: return
        context.scope.launch {
            val staged = files.create(FileNames.sanitize(body.filename))
            try {
                val progress = PayloadProgress { _, _ -> }
                endpoint.receive(context.deviceId, port, size, staged.stream, TransferControl(), progress)
                staged.stream.close()
                system.writeFile(staged.uri, staged.mime)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                staged.abort()
                throw e
            } catch (_: Exception) {
                staged.abort()
            }
        }
    }

    private suspend fun apply(text: String) {
        if (text.isEmpty()) return
        val at = now()
        store.record(text, at)
        system.writeText(text)
        history.record(text, context.deviceId, at)
    }
}
