// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.testing.mockpeer

import app.tandem.protocol.DeviceType
import app.tandem.protocol.Identity
import app.tandem.protocol.IdentityPackets
import app.tandem.transport.HandshakeCoordinator
import app.tandem.transport.InMemoryTrustPolicy
import app.tandem.transport.PeerSession
import app.tandem.transport.StaticKeyMaterial
import app.tandem.transport.TlsUpgrader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * A scriptable fake KDE Connect desktop for tests. It reuses the production handshake code
 * on the peer side, so the roles are: [connectTo] is the desktop as TCP client (flow A from
 * our side), and [acceptFrom] is the desktop as TCP server (flow B from our side).
 */
class MockPeer(
    val deviceId: String,
    val name: String = "Mock Desktop",
    val trust: InMemoryTrustPolicy = InMemoryTrustPolicy(),
) : AutoCloseable {
    private val keyPair: KeyPair = generateEcKeyPair()
    val certificate: X509Certificate = selfSignedCertificate(deviceId, keyPair)
    val certDer: ByteArray get() = certificate.encoded

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity =
        Identity(
            deviceId = deviceId,
            deviceName = name,
            deviceType = DeviceType.DESKTOP,
            protocolVersion = IdentityPackets.PROTOCOL_VERSION,
            incomingCapabilities = setOf("kdeconnect.ping"),
            outgoingCapabilities = setOf("kdeconnect.ping"),
        )
    private val coordinator =
        HandshakeCoordinator(
            localIdentity = { identity },
            tls = TlsUpgrader(StaticKeyMaterial("mock", keyPair.private, certificate), trust),
            scope = scope,
        )

    /** Desktop as TCP client: connects to our listener and sends its plaintext identity first. */
    fun connectTo(
        address: InetSocketAddress,
        ourDeviceId: String,
    ): PeerSession = coordinator.connectToPeer(address, ourDeviceId)

    /** Desktop as TCP server: accepts the connection we opened. */
    fun acceptFrom(server: ServerSocket): PeerSession = coordinator.acceptFromPeer(server.accept())

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        /** EC P-256 key pair, as used for the device identity. */
        fun generateEcKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        /** Self-signed certificate with CN = deviceId, the same subject shape as the app's own. */
        fun selfSignedCertificate(
            deviceId: String,
            keyPair: KeyPair,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=$deviceId, OU=KDE Connect, O=KDE")
            val builder =
                JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.ONE,
                    Date(now - ONE_YEAR_MS),
                    Date(now + TEN_YEARS_MS),
                    subject,
                    keyPair.public,
                )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }

        private const val ONE_YEAR_MS = 365L * 24 * 3600 * 1000
        private const val TEN_YEARS_MS = 10 * ONE_YEAR_MS
    }
}
