// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.transfer

import app.tandem.core.device.PeerSender
import app.tandem.core.identity.IdentityLoader
import app.tandem.core.trust.PinnedTrustManager
import app.tandem.transport.PayloadClient
import app.tandem.transport.PayloadProgress
import app.tandem.transport.PayloadServer
import app.tandem.transport.TlsUpgrader
import app.tandem.transport.TransferControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves one payload over its own TLS socket. Plugins and the UI use this; they never open sockets.
 * Both calls suspend and run their blocking I/O on Dispatchers.IO. Cancelling the calling coroutine
 * cancels the [TransferControl], which closes the sockets.
 */
interface PayloadEndpoint {
    /**
     * Sends [size] bytes (or to EOF when -1) from [source]. [announce] receives the port we listen
     * on and must send the packet that advertises it. Returns the bytes sent, or null when [announce]
     * could not send the packet (no live link).
     */
    // Payload API: every parameter is needed and callers name them.
    @Suppress("LongParameterList")
    suspend fun send(
        deviceId: String,
        size: Long,
        source: InputStream,
        control: TransferControl,
        progress: PayloadProgress,
        announce: suspend (port: Int) -> Boolean,
    ): Long?

    /** Receives a payload the peer advertised on [port] into [sink]. Returns the bytes received. */
    // Payload API: every parameter is needed and callers name them.
    @Suppress("LongParameterList")
    suspend fun receive(
        deviceId: String,
        port: Int,
        size: Long,
        sink: OutputStream,
        control: TransferControl,
        progress: PayloadProgress,
    ): Long
}

/**
 * TLS payloads with pinned trust (plan section 2.5). At most [MAX_CONCURRENT] payloads run at once,
 * which keeps the port usage inside the payload range.
 */
@Singleton
class PayloadTransport
    @Inject
    constructor(
        private val identities: IdentityLoader,
        private val pinned: PinnedTrustManager,
        // Lazy: DeviceManager reaches this class through the plugin registry and the share receiver.
        private val peers: dagger.Lazy<PeerSender>,
    ) : PayloadEndpoint {
        private val permits = Semaphore(MAX_CONCURRENT)

        override suspend fun send(
            deviceId: String,
            size: Long,
            source: InputStream,
            control: TransferControl,
            progress: PayloadProgress,
            announce: suspend (port: Int) -> Boolean,
        ): Long? {
            permits.acquire()
            try {
                control.throwIfCancelled()
                val tls = tlsUpgrader()
                val server = withContext(Dispatchers.IO) { PayloadServer.open() }
                try {
                    control.register(server)
                    // The packet goes out before the accept, so the peer learns the port in time.
                    if (!announce(server.port)) return null
                    return withIo(control) {
                        server.serve(tls, deviceId, source, size, control, progress)
                    }
                } finally {
                    server.close()
                }
            } finally {
                permits.release()
            }
        }

        override suspend fun receive(
            deviceId: String,
            port: Int,
            size: Long,
            sink: OutputStream,
            control: TransferControl,
            progress: PayloadProgress,
        ): Long {
            val live = peers.get().livePeer(deviceId) ?: throw IOException("no live link to the device")
            permits.acquire()
            try {
                control.throwIfCancelled()
                val tls = tlsUpgrader()
                return withIo(control) {
                    PayloadClient.receive(tls, live.address, port, deviceId, sink, size, control, progress)
                }
            } finally {
                permits.release()
            }
        }

        private suspend fun tlsUpgrader(): TlsUpgrader = TlsUpgrader(identities.load().material, pinned)

        /**
         * Runs [block] on the IO dispatcher. A watchdog coroutine cancels [control] when the caller is
         * cancelled, which closes the sockets and makes the blocking call throw.
         */
        private suspend fun <T> withIo(
            control: TransferControl,
            block: () -> T,
        ): T =
            coroutineScope {
                val finished = AtomicBoolean(false)
                val watchdog =
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!finished.get()) control.cancel()
                        }
                    }
                try {
                    withContext(Dispatchers.IO) { block() }
                } finally {
                    finished.set(true)
                    watchdog.cancel()
                }
            }

        companion object {
            const val MAX_CONCURRENT = 4
        }
    }
