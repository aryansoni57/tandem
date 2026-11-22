// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mousepad

import app.tandem.core.device.PeerSender
import app.tandem.protocol.body.KeyModifiers
import app.tandem.protocol.body.MousepadBody
import app.tandem.protocol.body.SpecialKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Sends pointer and keyboard input to the connected desktop. Pointer moves arrive faster than the
 * link should carry them, so moves that pile up while one is being sent are added into one packet.
 * [target] returns the device to drive, or null when none is connected.
 */
class MousepadSender(
    private val peers: PeerSender,
    private val target: suspend () -> String?,
    scope: CoroutineScope,
) {
    private val moves = Channel<Pair<Float, Float>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((firstX, firstY) in moves) {
                var dx = firstX
                var dy = firstY
                while (true) {
                    val more = moves.tryReceive().getOrNull() ?: break
                    dx += more.first
                    dy += more.second
                }
                send(MousepadBody.Move(dx, dy))
            }
        }
    }

    /** Queues a relative move. Never blocks. */
    fun move(
        dx: Float,
        dy: Float,
    ) {
        moves.trySend(dx to dy)
    }

    suspend fun scroll(dy: Float) = send(MousepadBody.Scroll(dy))

    suspend fun click(click: MousepadBody.Click) = send(MousepadBody.ClickEvent(click))

    suspend fun special(
        key: SpecialKey,
        modifiers: KeyModifiers = KeyModifiers(),
    ) = send(MousepadBody.Special(key, modifiers))

    /** Types [text]. A newline is sent as the Enter key, because the desktop ignores a raw line break. */
    suspend fun type(
        text: String,
        modifiers: KeyModifiers = KeyModifiers(),
    ): Boolean {
        var sent = true
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            if (line.isNotEmpty()) sent = send(MousepadBody.Text(line, modifiers)) && sent
            if (index < lines.lastIndex) sent = send(MousepadBody.Special(SpecialKey.Enter)) && sent
        }
        return sent
    }

    private suspend fun send(body: MousepadBody): Boolean {
        val deviceId = target() ?: return false
        return peers.sendPacket(deviceId, body.toPacket())
    }
}
