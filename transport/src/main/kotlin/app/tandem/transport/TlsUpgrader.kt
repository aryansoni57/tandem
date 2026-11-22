// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** Our own key material: the private key and certificate used for every TLS connection. */
interface KeyMaterialProvider {
    val keyManager: X509ExtendedKeyManager
    val certificate: X509Certificate
}

/** Pinned certificates for trusted devices, keyed by deviceId. */
interface TrustPolicy {
    /** DER of the pinned certificate for [deviceId], or null when the device is not trusted. */
    fun pinnedCertificate(deviceId: String): ByteArray?
}

class TlsException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class TlsRole { CLIENT, SERVER }

/** Result of a successful handshake. */
class TlsSession(
    val socket: SSLSocket,
    val peerCertificate: X509Certificate,
    val protocol: String,
    val cipherSuite: String,
) {
    val peerCertDer: ByteArray get() = peerCertificate.encoded
}

/** Simple in-memory pin table. Core replaces it with a Room-backed store. */
class InMemoryTrustPolicy : TrustPolicy {
    private val pins = ConcurrentHashMap<String, ByteArray>()

    fun pin(
        deviceId: String,
        certDer: ByteArray,
    ) {
        pins[deviceId] = certDer
    }

    fun unpin(deviceId: String) {
        pins.remove(deviceId)
    }

    override fun pinnedCertificate(deviceId: String): ByteArray? = pins[deviceId]
}

/** Key material from a single private key and certificate. Used by tests and the mock peer. */
class StaticKeyMaterial(
    alias: String,
    privateKey: PrivateKey,
    override val certificate: X509Certificate,
) : KeyMaterialProvider {
    override val keyManager: X509ExtendedKeyManager = SingleKeyManager(alias, privateKey, arrayOf(certificate))
}

/**
 * Wraps sockets in TLS 1.2 (plan section 2.3).
 *
 * Trust rules:
 * - Untrusted peer: any certificate passes during the handshake. The caller then requires
 *   the certificate CN to equal the deviceId from the plaintext identity.
 * - Trusted peer: the leaf certificate DER must byte-equal the pinned certificate. Validity
 *   dates are ignored for pinned certificates.
 */
class TlsUpgrader(
    private val keys: KeyMaterialProvider,
    private val trust: TrustPolicy,
    private val handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
) {
    fun upgrade(
        socket: Socket,
        role: TlsRole,
        expectedDeviceId: String,
    ): TlsSession {
        val pinned = trust.pinnedCertificate(expectedDeviceId)
        val context = SSLContext.getInstance(PROTOCOL)
        context.init(
            arrayOf(keys.keyManager),
            arrayOf<javax.net.ssl.TrustManager>(PeerTrustManager(pinned)),
            SecureRandom(),
        )

        val ssl =
            context.socketFactory.createSocket(
                socket,
                socket.inetAddress.hostAddress,
                socket.port,
                true,
            ) as SSLSocket
        ssl.enabledProtocols = arrayOf(PROTOCOL)
        ssl.useClientMode = role == TlsRole.CLIENT
        if (role == TlsRole.SERVER) {
            if (pinned != null) ssl.needClientAuth = true else ssl.wantClientAuth = true
        }
        ssl.soTimeout = handshakeTimeoutMs
        try {
            ssl.startHandshake()
            val peer =
                ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw TlsException("peer sent no certificate")
            if (commonName(peer) != expectedDeviceId) {
                throw TlsException("certificate CN does not match the deviceId from the identity packet")
            }
            ssl.soTimeout = 0
            ssl.keepAlive = true
            return TlsSession(ssl, peer, ssl.session.protocol, ssl.session.cipherSuite)
        } catch (e: TlsException) {
            ssl.close()
            throw e
        } catch (e: IOException) {
            ssl.close()
            throw TlsException("TLS handshake failed: ${e.message}", e)
        }
    }

    /** Trust manager for one connection. The pin is captured when the connection starts. */
    private class PeerTrustManager(private val pinned: ByteArray?) : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = verify(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = verify(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun verify(chain: Array<out X509Certificate>) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val pin = pinned ?: return
            if (!MessageDigest.isEqual(chain[0].encoded, pin)) {
                throw CertificateException("certificate mismatch with the pinned device certificate")
            }
        }
    }

    companion object {
        /** TLS 1.3 is off in v1, like the reference. Behind a flag later. */
        const val PROTOCOL = "TLSv1.2"
        const val HANDSHAKE_TIMEOUT_MS = 10_000

        /** Extracts CN from an RFC 2253 subject, without javax.naming (not on Android). */
        fun commonName(cert: X509Certificate): String? {
            val rfc2253 = cert.subjectX500Principal.getName("RFC2253")
            return Regex("""(?:^|,)CN=((?:\\.|[^,\\])+)""").find(rfc2253)?.groupValues?.get(1)
                ?.replace(Regex("""\\(.)"""), "$1")
        }
    }
}

/** Presents one fixed alias and key for both client and server roles. */
private class SingleKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
    ): Array<String> = arrayOf(alias)

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        socket: Socket?,
    ): String = alias

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
    ): Array<String> =
        arrayOf(
            alias,
        )

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        socket: Socket?,
    ): String = alias

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = key

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?,
    ): String = alias

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?,
    ): String = alias
}
