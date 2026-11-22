// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.protocol.DeviceType
import app.tandem.protocol.Identity
import app.tandem.protocol.IdentityPackets
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.testing.mockpeer.MockPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Flow A: the desktop connects to our TCP listener after our UDP announce. We are TLS client. */
class HandshakeFlowATest {
    private val ourId = "0123456789abcdef0123456789abcdef"
    private val peerId = "fedcba9876543210fedcba9876543210"
    private val ourIdentity =
        Identity(
            deviceId = ourId,
            deviceName = "Tandem Phone",
            deviceType = DeviceType.PHONE,
            incomingCapabilities = setOf("kdeconnect.ping"),
            outgoingCapabilities = setOf("kdeconnect.ping"),
        )

    private fun pingPacket(id: Long) = NetworkPacket(id, PacketTypes.IDENTITY, JsonObject(emptyMap()))

    private class Harness(val scope: CoroutineScope, val listener: TcpListener, val coordinator: HandshakeCoordinator)

    private fun harness(
        trust: InMemoryTrustPolicy,
        identity: Identity,
    ): Harness {
        val keys = MockPeer.generateEcKeyPair()
        val cert = MockPeer.selfSignedCertificate(identity.deviceId, keys)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator =
            HandshakeCoordinator(
                localIdentity = { identity },
                tls = TlsUpgrader(StaticKeyMaterial("ours", keys.private, cert), trust),
                scope = scope,
            )
        return Harness(scope, TcpListener.bind(41000..41100), coordinator)
    }

    @Test
    fun desktopConnectsAndBothSidesExchangeIdentityAndPing(): Unit =
        runBlocking {
            withTimeout(20_000) {
                val h = harness(InMemoryTrustPolicy(), ourIdentity)
                val accepted = CompletableDeferred<PeerSession>()
                h.listener.start(h.scope) { socket ->
                    try {
                        accepted.complete(h.coordinator.acceptFromPeer(socket))
                    } catch (e: Exception) {
                        accepted.completeExceptionally(e)
                    }
                }
                MockPeer(peerId).use { mock ->
                    val mockSession =
                        withContext(Dispatchers.IO) {
                            mock.connectTo(InetSocketAddress("127.0.0.1", h.listener.port), ourId)
                        }
                    val ours = accepted.await()

                    assertEquals(peerId, ours.peer.deviceId)
                    assertEquals(DeviceType.DESKTOP, ours.peer.deviceType)
                    assertEquals(setOf("kdeconnect.ping"), ours.peer.incomingCapabilities)
                    assertTrue(ours.weAreTlsClient, "we accepted the TCP connection, so we are TLS client")
                    assertTrue(ours.peerCertDer.contentEquals(mock.certDer))
                    assertEquals("TLSv1.2", ours.tlsVersion)

                    mockSession.channel.send(pingPacket(1))
                    assertEquals(PacketTypes.IDENTITY, ours.channel.incoming.receive().type)
                    ours.channel.send(pingPacket(2))
                    assertEquals(2L, mockSession.channel.incoming.receive().id)

                    ours.channel.close()
                    mockSession.channel.close()
                }
                h.listener.close()
                h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    @Test
    fun pinnedCertificateMismatchIsRefused(): Unit =
        runBlocking {
            withTimeout(20_000) {
                val wrongPin = MockPeer.selfSignedCertificate(peerId, MockPeer.generateEcKeyPair()).encoded
                val trust = InMemoryTrustPolicy().apply { pin(peerId, wrongPin) }
                val h = harness(trust, ourIdentity)
                val result = CompletableDeferred<Throwable?>()
                h.listener.start(h.scope) { socket ->
                    try {
                        h.coordinator.acceptFromPeer(socket).channel.close()
                        result.complete(null)
                    } catch (e: Exception) {
                        result.complete(e)
                    }
                }
                MockPeer(peerId).use { mock ->
                    runCatching {
                        withContext(
                            Dispatchers.IO,
                        ) { mock.connectTo(InetSocketAddress("127.0.0.1", h.listener.port), ourId) }
                    }
                    val failure = result.await()
                    assertTrue(failure is IOException, "expected a TLS failure, got $failure")
                }
                h.listener.close()
                h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    @Test
    fun connectionTargetingAnotherDeviceIsRefused(): Unit =
        runBlocking {
            withTimeout(20_000) {
                val h = harness(InMemoryTrustPolicy(), ourIdentity)
                val result = CompletableDeferred<Throwable?>()
                h.listener.start(h.scope) { socket ->
                    try {
                        h.coordinator.acceptFromPeer(socket)
                        result.complete(null)
                    } catch (e: Exception) {
                        result.complete(e)
                    }
                }
                MockPeer(peerId).use { mock ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            mock.connectTo(
                                InetSocketAddress("127.0.0.1", h.listener.port),
                                "ffffffffffffffffffffffffffffffff",
                            )
                        }
                    }
                    assertFailsWith<HandshakeException> { throw result.await()!! }
                }
                h.listener.close()
                h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    @Test
    fun identityPacketVariantsRoundTrip() {
        val line = IdentityPackets.toSecure(ourIdentity)
        assertEquals(ourId, IdentityPackets.parseSecure(line).deviceId)
    }
}
