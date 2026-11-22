// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One reader coroutine and one writer coroutine per socket. Packets therefore never interleave
 * on the wire. Callers send through [send], which queues to the single writer.
 */
class PacketChannel(
    private val reader: LineReader,
    private val output: OutputStream,
    private val socket: Closeable,
    scope: CoroutineScope,
) {
    private val inbox = Channel<NetworkPacket>(INCOMING_BUFFER)

    /** Single consumer. Packets wait in the buffer, so none is lost before the consumer starts. */
    val incoming: ReceiveChannel<NetworkPacket> = inbox

    private val outbox = Channel<NetworkPacket>(OUTBOX_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val closedSignal = CompletableDeferred<Unit>()

    @Volatile
    var failure: Throwable? = null
        private set

    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val packet =
                        try {
                            PacketCodec.decode(line)
                        } catch (_: PacketException) {
                            continue
                        }
                    inbox.send(packet)
                }
            } catch (e: IOException) {
                failure = e
            } finally {
                inbox.close()
                close()
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                for (packet in outbox) {
                    output.write(PacketCodec.encode(packet))
                    output.flush()
                }
            } catch (e: IOException) {
                failure = e
            } finally {
                close()
            }
        }
    }

    val isOpen: Boolean get() = !closed.get()

    /** Queues [packet] for the writer. Suspends when the outbox is full. */
    suspend fun send(packet: NetworkPacket) {
        outbox.send(packet)
    }

    /** Suspends until the channel is closed by either side or by [close]. */
    suspend fun awaitClosed() {
        closedSignal.await()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            outbox.close()
            closedSignal.complete(Unit)
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }

    companion object {
        const val INCOMING_BUFFER = 64
        const val OUTBOX_CAPACITY = 256
    }
}
