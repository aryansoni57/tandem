// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.presenter

import app.tandem.core.device.PeerSender
import app.tandem.protocol.body.PresenterBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Moves the red dot on the desktop. Sensor samples arrive faster than the link should carry them, so
 * moves that queue up while one is being sent are added into one packet. [target] returns the device
 * to drive, or null when none is connected.
 */
class PresenterSender(
    private val peers: PeerSender,
    private val target: suspend () -> String?,
    scope: CoroutineScope,
) {
    private sealed interface Command {
        data class Move(val dx: Float, val dy: Float) : Command

        data object Stop : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            var carry: Command? = null
            while (true) {
                val first = carry ?: commands.receive()
                carry = null
                if (first is Command.Stop) {
                    send(PresenterBody.Stop)
                    continue
                }
                var dx = (first as Command.Move).dx
                var dy = first.dy
                while (true) {
                    val more = commands.tryReceive().getOrNull() ?: break
                    if (more is Command.Move) {
                        dx += more.dx
                        dy += more.dy
                    } else {
                        carry = more
                        break
                    }
                }
                send(PresenterBody.Move(dx, dy))
            }
        }
    }

    /** Queues a move in screen fractions. Never blocks. */
    fun move(
        dx: Float,
        dy: Float,
    ) {
        commands.trySend(Command.Move(dx, dy))
    }

    /** Hides the dot. */
    fun stop() {
        commands.trySend(Command.Stop)
    }

    private suspend fun send(body: PresenterBody): Boolean {
        val deviceId = target() ?: return false
        return peers.sendPacket(deviceId, body.toPacket())
    }
}
