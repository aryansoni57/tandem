// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.testing.mockpeer.MockPeer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Payload sockets over loopback. The sender is the TLS server (the peer that announced the port),
 * the receiver is the TLS client, as in plan section 2.5. Both use pinned certificates.
 */
class PayloadTransferTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val ourId = "0123456789abcdef0123456789abcdef"
    private val peerId = "fedcba9876543210fedcba9876543210"

    private val ourKeys = MockPeer.generateEcKeyPair()
    private val ourCert = MockPeer.selfSignedCertificate(ourId, ourKeys)
    private val peerKeys = MockPeer.generateEcKeyPair()
    private val peerCert = MockPeer.selfSignedCertificate(peerId, peerKeys)

    private val pool = Executors.newCachedThreadPool()

    /** Sender side: trusts the receiver (peerId) only. */
    private lateinit var senderTls: TlsUpgrader

    /** Receiver side: trusts the sender (ourId) only. */
    private lateinit var receiverTls: TlsUpgrader

    @BeforeTest
    fun setUp() {
        val senderTrust = InMemoryTrustPolicy().apply { pin(peerId, peerCert.encoded) }
        senderTls = TlsUpgrader(StaticKeyMaterial("ours", ourKeys.private, ourCert), senderTrust)
        val receiverTrust = InMemoryTrustPolicy().apply { pin(ourId, ourCert.encoded) }
        receiverTls = TlsUpgrader(StaticKeyMaterial("peer", peerKeys.private, peerCert), receiverTrust)
    }

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Starts the sender on its own thread. Returns the server (for its port) and the future. */
    private fun startSender(
        source: InputStream,
        size: Long,
        control: TransferControl = TransferControl(),
        progress: PayloadProgress = PayloadProgress { _, _ -> },
        peerIdForSender: String = peerId,
    ): Pair<PayloadServer, Future<Long>> {
        val server = PayloadServer.open(range = FREE_RANGE, bindAddress = loopback)
        val future =
            pool.submit<Long> {
                server.serve(senderTls, peerIdForSender, source, size, control, progress)
            }
        return server to future
    }

    @Test
    fun largeFileStreamsWithMatchingBytes() {
        val data = randomBytes(24 * 1024 * 1024 + 123)
        val progressCalls = mutableListOf<Long>()
        val (server, sender) =
            startSender(
                ByteArrayInputStream(data),
                data.size.toLong(),
                progress = { done, _ -> synchronized(progressCalls) { progressCalls += done } },
            )
        val sink = ByteArrayOutputStream()
        val received =
            PayloadClient.receive(
                receiverTls,
                loopback,
                server.port,
                ourId,
                sink,
                data.size.toLong(),
                TransferControl(),
                PayloadProgress { _, _ -> },
            )
        assertEquals(data.size.toLong(), sender.get(20, TimeUnit.SECONDS))
        assertEquals(data.size.toLong(), received)
        assertEquals(sha256(data), sha256(sink.toByteArray()))
        assertContentEquals(data, sink.toByteArray())
        server.close()
        assertEquals(data.size.toLong(), synchronized(progressCalls) { progressCalls.last() })
    }

    @Test
    fun zeroBytePayloadCompletes() {
        val (server, sender) = startSender(ByteArrayInputStream(ByteArray(0)), 0)
        val sink = ByteArrayOutputStream()
        val received =
            PayloadClient.receive(
                receiverTls,
                loopback,
                server.port,
                ourId,
                sink,
                0,
                TransferControl(),
                PayloadProgress {
                        _,
                        _,
                    ->
                },
            )
        assertEquals(0L, received)
        assertEquals(0L, sender.get(10, TimeUnit.SECONDS))
        assertEquals(0, sink.size())
        server.close()
    }

    @Test
    fun unknownSizeReadsUntilEof() {
        val data = randomBytes(700_000)
        val (server, sender) = startSender(ByteArrayInputStream(data), -1)
        val sink = ByteArrayOutputStream()
        val received =
            PayloadClient.receive(
                receiverTls,
                loopback,
                server.port,
                ourId,
                sink,
                -1,
                TransferControl(),
                PayloadProgress {
                        _,
                        _,
                    ->
                },
            )
        assertEquals(data.size.toLong(), received)
        assertEquals(data.size.toLong(), sender.get(10, TimeUnit.SECONDS))
        assertContentEquals(data, sink.toByteArray())
        server.close()
    }

    @Test
    fun truncatedSourceFailsTheSenderAndTheReceiver() {
        val data = randomBytes(1000)
        val (server, sender) = startSender(ByteArrayInputStream(data), 5000)
        val sink = ByteArrayOutputStream()
        try {
            PayloadClient.receive(
                receiverTls,
                loopback,
                server.port,
                ourId,
                sink,
                5000,
                TransferControl(),
                PayloadProgress {
                        _,
                        _,
                    ->
                },
            )
            fail("receiver must report the truncated stream")
        } catch (e: PayloadTruncatedException) {
            assertTrue(e.message.orEmpty().contains("1000"))
        }
        try {
            sender.get(10, TimeUnit.SECONDS)
            fail("sender must report that its source ended early")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is PayloadTruncatedException)
        }
        server.close()
    }

    @Test
    fun receiverTruncatedByPeerDropIsReported() {
        // A sender that closes after 3 bytes while it promised 100: the receiver sees EOF early.
        val (server, sender) = startSender(ByteArrayInputStream(randomBytes(3)), 100)
        try {
            PayloadClient.receive(
                receiverTls,
                loopback,
                server.port,
                ourId,
                ByteArrayOutputStream(),
                100,
                TransferControl(),
                PayloadProgress { _, _ -> },
            )
            fail("expected truncation")
        } catch (_: PayloadTruncatedException) {
            // expected
        }
        runCatching { sender.get(10, TimeUnit.SECONDS) }
        server.close()
    }

    @Test
    fun cancelStopsBothSidesWithoutHanging() {
        val endless = EndlessInput()
        val control = TransferControl()
        val (server, sender) = startSender(endless, -1, control = control)
        val receiverControl = TransferControl()
        val sink = ByteArrayOutputStream()
        val receiverDone = CountDownLatch(1)
        val receiver =
            pool.submit<Long> {
                try {
                    PayloadClient.receive(
                        receiverTls,
                        loopback,
                        server.port,
                        ourId,
                        sink,
                        -1,
                        receiverControl,
                        PayloadProgress { _, _ -> },
                    )
                } finally {
                    receiverDone.countDown()
                }
            }
        // Wait until bytes flow, then cancel from this thread.
        while (sink.size() < 1_000_000) Thread.sleep(5)
        control.cancel()
        receiverControl.cancel()
        assertTrue(receiverDone.await(10, TimeUnit.SECONDS))
        try {
            sender.get(10, TimeUnit.SECONDS)
            fail("cancelled sender must fail")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is IOException, "was \${e.cause}")
        }
        try {
            receiver.get(10, TimeUnit.SECONDS)
            fail("cancelled receiver must fail")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is IOException, "was \${e.cause}")
        }
        assertTrue(endless.produced > 0)
        server.close()
    }

    @Test
    fun cancelBeforeStartFailsFast() {
        val control = TransferControl()
        control.cancel()
        val server = PayloadServer.open(range = FREE_RANGE, bindAddress = loopback)
        try {
            server.serve(
                senderTls,
                peerId,
                ByteArrayInputStream(ByteArray(10)),
                10,
                control,
                PayloadProgress {
                        _,
                        _,
                    ->
                },
            )
            fail("expected cancel")
        } catch (_: TransferCancelledException) {
            // expected
        }
        server.close()
    }

    @Test
    fun receiverRejectsAPeerWithAnotherCertificate() {
        // The receiver pins a different certificate for the sender's deviceId: the handshake must fail.
        val impostorKeys = MockPeer.generateEcKeyPair()
        val impostor = MockPeer.selfSignedCertificate(ourId, impostorKeys)
        val wrongPins = InMemoryTrustPolicy().apply { pin(ourId, impostor.encoded) }
        val wrongReceiver = TlsUpgrader(StaticKeyMaterial("peer", peerKeys.private, peerCert), wrongPins)

        val (server, sender) = startSender(ByteArrayInputStream(randomBytes(4096)), 4096)
        val sink = ByteArrayOutputStream()
        try {
            PayloadClient.receive(
                wrongReceiver,
                loopback,
                server.port,
                ourId,
                sink,
                4096,
                TransferControl(),
                PayloadProgress {
                        _,
                        _,
                    ->
                },
            )
            fail("pinned mismatch must be rejected")
        } catch (_: TlsException) {
            // expected
        }
        assertEquals(0, sink.size())
        runCatching { sender.get(10, TimeUnit.SECONDS) }
        server.close()
    }

    @Test
    fun senderRejectsAReceiverWithAnotherCertificate() {
        // The sender pins a different certificate for the receiver's deviceId.
        val impostor = MockPeer.selfSignedCertificate(peerId, MockPeer.generateEcKeyPair())
        val wrongPins = InMemoryTrustPolicy().apply { pin(peerId, impostor.encoded) }
        val wrongSender = TlsUpgrader(StaticKeyMaterial("ours", ourKeys.private, ourCert), wrongPins)
        val server = PayloadServer.open(range = FREE_RANGE, bindAddress = loopback)
        val sender =
            pool.submit<Long> {
                server.serve(
                    wrongSender,
                    peerId,
                    ByteArrayInputStream(ByteArray(10)),
                    10,
                    TransferControl(),
                    PayloadProgress {
                            _,
                            _,
                        ->
                    },
                )
            }
        val receiverResult =
            runCatching {
                PayloadClient.receive(
                    receiverTls,
                    loopback,
                    server.port,
                    ourId,
                    ByteArrayOutputStream(),
                    10,
                    TransferControl(),
                    PayloadProgress {
                            _,
                            _,
                        ->
                    },
                )
            }
        assertTrue(receiverResult.isFailure)
        try {
            sender.get(10, TimeUnit.SECONDS)
            fail("sender must refuse the unpinned receiver")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is TlsException, "was \${e.cause}")
        }
        server.close()
    }

    @Test
    fun acceptTimeoutFailsWhenNobodyConnects() {
        val server = PayloadServer.open(range = FREE_RANGE, bindAddress = loopback)
        val started = AtomicLong(System.currentTimeMillis())
        try {
            server.serve(
                senderTls,
                peerId,
                ByteArrayInputStream(ByteArray(0)),
                0,
                TransferControl(),
                PayloadProgress {
                        _,
                        _,
                    ->
                },
                acceptTimeoutMs = 300,
            )
            fail("expected accept timeout")
        } catch (_: IOException) {
            assertTrue(System.currentTimeMillis() - started.get() < 5_000)
        }
        server.close()
    }

    @Test
    fun portIsTakenFromTheRequestedRange() {
        val server = PayloadServer.open(range = FREE_RANGE, bindAddress = loopback)
        assertTrue(server.port in FREE_RANGE)
        server.close()
    }

    /** Produces bytes until closed. Counts what it produced. */
    private class EndlessInput : InputStream() {
        var produced = 0L

        override fun read(): Int = 0

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            java.util.Arrays.fill(b, off, off + len, 7)
            produced += len
            return len
        }
    }

    companion object {
        /** Ports used by these tests, so they do not collide with a running desktop on 1739-1764. */
        private val FREE_RANGE = 21_000..21_200
    }
}
