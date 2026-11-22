// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.core.device.PeerSender
import app.tandem.protocol.body.ClipboardBody

/** Result of a user-triggered send. */
sealed interface SendOutcome {
    /** The clipboard had no readable text. */
    data object NothingToSend : SendOutcome

    /** Text was read, but no target device accepted it. */
    data object NoDevice : SendOutcome

    data class Sent(val devices: Int) : SendOutcome
}

/** A short line for a toast or a status text. */
fun SendOutcome.describe(): String =
    when (this) {
        SendOutcome.NothingToSend -> "Nothing to send. Copy text first, then try again."
        SendOutcome.NoDevice -> "Not connected to a paired device."
        is SendOutcome.Sent -> "Clipboard sent to $devices device${if (devices == 1) "" else "s"}."
    }

/**
 * Phone to peer clipboard. Background reads are blocked on Android 10 and later, so this runs only
 * from a user action (the device screen, the quick-settings tile or the send activity).
 */
class ClipboardSync(
    private val store: ClipboardStore,
    private val system: SystemClipboard,
    private val peers: PeerSender,
    private val history: ClipHistory,
    private val skipSensitive: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Reads the clipboard and sends it to [deviceIds]. */
    suspend fun sendLocal(deviceIds: Collection<String>): SendOutcome {
        val text = system.readText(skipSensitive = skipSensitive()) ?: return SendOutcome.NothingToSend
        return sendText(text, deviceIds, now())
    }

    /** Records [text] as our latest clip, then sends it to each device. Packets carry [at] as their id. */
    internal suspend fun sendText(
        text: String,
        deviceIds: Collection<String>,
        at: Long,
    ): SendOutcome {
        store.record(text, at)
        history.record(text, null, at)
        var accepted = 0
        for (id in deviceIds) {
            if (peers.sendPacket(id, ClipboardBody(text).toPacket(at))) accepted++
        }
        return if (accepted == 0) SendOutcome.NoDevice else SendOutcome.Sent(accepted)
    }
}
