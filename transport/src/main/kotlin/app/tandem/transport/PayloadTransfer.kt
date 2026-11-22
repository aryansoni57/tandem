// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Raised when a transfer is cancelled by [TransferControl.cancel]. */
class TransferCancelledException : IOException("transfer cancelled")

/** Raised when the stream ends before the announced payload size. The receiver must discard the data. */
class PayloadTruncatedException(expected: Long, received: Long) :
    IOException("payload ended after $received of $expected bytes")

/**
 * Cancels one transfer. Every socket and stream registered with it is closed at once, which
 * unblocks a read or write that is waiting on the network.
 */
class TransferControl {
    private val cancelled = AtomicBoolean(false)
    private val closeables = CopyOnWriteArrayList<Closeable>()

    val isCancelled: Boolean get() = cancelled.get()

    fun register(closeable: Closeable) {
        closeables += closeable
        if (cancelled.get()) closeQuietly(closeable)
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) closeables.forEach { closeQuietly(it) }
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw TransferCancelledException()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}

/** Receives (bytes done, total bytes). Total is -1 when unknown. Called at most every 500 ms, and once at the end. */
fun interface PayloadProgress {
    fun onProgress(
        done: Long,
        total: Long,
    )
}

/** Rate-limits progress callbacks. Not thread-safe: one transfer uses one instance. */
internal class ProgressThrottle(
    private val progress: PayloadProgress,
    private val total: Long,
    private val intervalMs: Long = PROGRESS_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmit = 0L

    fun update(done: Long) {
        val now = clock()
        if (now - lastEmit >= intervalMs) {
            lastEmit = now
            progress.onProgress(done, total)
        }
    }

    fun finish(done: Long) = progress.onProgress(done, total)
}

/**
 * Sender side of a payload (plan section 2.5). [open] reserves a port in the payload range, the
 * caller advertises it in the packet, then [serve] accepts one TLS connection and streams the data.
 */
class PayloadServer private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    /**
     * Accepts one peer within [acceptTimeoutMs], wraps the socket in TLS as server with the pinned
     * trust of [peerDeviceId], and writes [size] bytes from [source]. A size of -1 streams until EOF.
     * Returns the number of bytes written. Throws [PayloadTruncatedException] when [source] ends early.
     */
    // Socket timeouts and progress are separate knobs; callers name them.
    @Suppress("LongParameterList")
    fun serve(
        tls: TlsUpgrader,
        peerDeviceId: String,
        source: InputStream,
        size: Long,
        control: TransferControl,
        progress: PayloadProgress,
        acceptTimeoutMs: Int = ACCEPT_TIMEOUT_MS,
        idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
    ): Long {
        control.register(this)
        control.throwIfCancelled()
        server.soTimeout = acceptTimeoutMs
        val accepted = server.accept()
        control.register(accepted)
        val session = tls.upgrade(accepted, TlsRole.SERVER, peerDeviceId)
        try {
            control.register(session.socket)
            control.throwIfCancelled()
            session.socket.soTimeout = idleTimeoutMs
            return streamOut(session.socket.outputStream, source, size, control, progress)
        } finally {
            session.socket.close()
        }
    }

    override fun close() {
        server.close()
    }

    companion object {
        const val ACCEPT_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 30_000

        /** Binds the first free port in [range]. [bindAddress] null means all interfaces. */
        fun open(
            range: IntRange = PortRange.PAYLOAD,
            bindAddress: InetAddress? = null,
        ): PayloadServer =
            PayloadServer(
                PortRange.firstFree(range) { port ->
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(bindAddress, port))
                    }
                },
            )
    }
}

/**
 * Receiver side of a payload. Connects to the port the peer advertised, wraps the socket in TLS as
 * client with pinned trust, and copies the payload to [sink] in a streaming loop.
 */
object PayloadClient {
    /**
     * Returns the number of bytes received. A size of -1 reads until EOF. A stream that ends before
     * [size] bytes throws [PayloadTruncatedException]. A certificate that is not the pinned one fails
     * the TLS handshake with [TlsException].
     */
    // Socket timeouts and progress are separate knobs; callers name them.
    @Suppress("LongParameterList")
    fun receive(
        tls: TlsUpgrader,
        remote: InetAddress,
        port: Int,
        peerDeviceId: String,
        sink: OutputStream,
        size: Long,
        control: TransferControl,
        progress: PayloadProgress,
        connectTimeoutMs: Int = PayloadServer.ACCEPT_TIMEOUT_MS,
        idleTimeoutMs: Int = PayloadServer.IDLE_TIMEOUT_MS,
    ): Long {
        val socket = Socket()
        control.register(socket)
        control.throwIfCancelled()
        socket.connect(InetSocketAddress(remote, port), connectTimeoutMs)
        val session = tls.upgrade(socket, TlsRole.CLIENT, peerDeviceId)
        try {
            control.register(session.socket)
            control.throwIfCancelled()
            session.socket.soTimeout = idleTimeoutMs
            return streamIn(session.socket.inputStream, sink, size, control, progress)
        } finally {
            session.socket.close()
        }
    }
}

/** Copies [size] bytes (or to EOF when -1) from [source] to [out] in 256 KiB chunks. Never buffers the file. */
internal fun streamOut(
    out: OutputStream,
    source: InputStream,
    size: Long,
    control: TransferControl,
    progress: PayloadProgress,
): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    val throttle = ProgressThrottle(progress, size)
    var sent = 0L
    while (size < 0 || sent < size) {
        control.throwIfCancelled()
        val want = if (size < 0) buffer.size else minOf(buffer.size.toLong(), size - sent).toInt()
        val n = source.read(buffer, 0, want)
        if (n < 0) {
            if (size < 0) break
            throw PayloadTruncatedException(size, sent)
        }
        out.write(buffer, 0, n)
        sent += n
        throttle.update(sent)
    }
    out.flush()
    throttle.finish(sent)
    return sent
}

/** Copies from [input] to [sink]. Same rules as [streamOut], seen from the receiving side. */
internal fun streamIn(
    input: InputStream,
    sink: OutputStream,
    size: Long,
    control: TransferControl,
    progress: PayloadProgress,
): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    val throttle = ProgressThrottle(progress, size)
    var received = 0L
    while (size < 0 || received < size) {
        control.throwIfCancelled()
        val want = if (size < 0) buffer.size else minOf(buffer.size.toLong(), size - received).toInt()
        val n = input.read(buffer, 0, want)
        if (n < 0) {
            if (size < 0) break
            throw PayloadTruncatedException(size, received)
        }
        sink.write(buffer, 0, n)
        received += n
        throttle.update(received)
    }
    sink.flush()
    throttle.finish(received)
    return received
}

private const val BUFFER_BYTES = 256 * 1024
private const val PROGRESS_INTERVAL_MS = 500L
