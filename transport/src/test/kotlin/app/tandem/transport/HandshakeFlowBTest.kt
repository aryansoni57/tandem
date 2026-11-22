// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.protocol.DeviceType
import app.tandem.protocol.Identity
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.testing.mockpeer.MockPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Flow B: we connect to the peer's announced TCP port. We are TLS server. */
class HandshakeFlowBTest {
    private val ourId = "0123456789abcdef0123456789abcdef"
    private val peerId = "fedcba9876543210fedcba9876543210"

    @Test
    fun weConnectAndBothSidesExchangeIdentityAndPing(): Unit =
        runBlocking {
            withTimeout(20_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val keys = MockPeer.generateEcKeyPair()
                val cert = MockPeer.selfSignedCertificate(ourId, keys)
                val identity =
                    Identity(
                        deviceId = ourId,
                        deviceName = "Tandem Phone",
                        deviceType = DeviceType.PHONE,
                        incomingCapabilities = setOf("kdeconnect.ping"),
                    )
                val coordinator =
                    HandshakeCoordinator(
                        localIdentity = { identity },
                        tls = TlsUpgrader(StaticKeyMaterial("ours", keys.private, cert), InMemoryTrustPolicy()),
                        scope = scope,
                    )

                ServerSocket(0).use { server ->
                    MockPeer(peerId).use { mock ->
                        val mockSide = async(Dispatchers.IO) { mock.acceptFrom(server) }
                        val ours =
                            withContext(Dispatchers.IO) {
                                coordinator.connectToPeer(InetSocketAddress("127.0.0.1", server.localPort), peerId)
                            }
                        val theirs = mockSide.await()

                        assertEquals(peerId, ours.peer.deviceId)
                        assertEquals(DeviceType.DESKTOP, ours.peer.deviceType)
                        assertFalse(ours.weAreTlsClient, "we connected, so we are TLS server")
                        assertEquals("TLSv1.2", ours.tlsVersion)
                        assertEquals(ourId, theirs.peer.deviceId)

                        ours.channel.send(NetworkPacket(10, PacketTypes.IDENTITY, JsonObject(emptyMap())))
                        assertEquals(10L, theirs.channel.incoming.receive().id)
                        theirs.channel.send(NetworkPacket(11, PacketTypes.IDENTITY, JsonObject(emptyMap())))
                        assertEquals(11L, ours.channel.incoming.receive().id)

                        ours.channel.close()
                        theirs.channel.close()
                    }
                }
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
}
