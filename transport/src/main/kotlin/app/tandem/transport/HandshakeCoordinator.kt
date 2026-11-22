// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.protocol.Identity
import app.tandem.protocol.IdentityException
import app.tandem.protocol.IdentityPackets
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketException
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class HandshakeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A connection that finished the TLS handshake and the secure identity exchange.
 *
 * @property weAreTlsClient true for flow A (we accepted the TCP connection), false for flow B.
 */
class PeerSession(
    val peer: Identity,
    val peerCertDer: ByteArray,
    val tlsVersion: String,
    val cipherSuite: String,
    val channel: PacketChannel,
    val remoteAddress: InetAddress,
    val weAreTlsClient: Boolean,
)

/**
 * Both connection flows of plan section 2.2, through the identity exchange of section 2.3.
 *
 * Flow A (we accept): the peer's plaintext connection packet comes first. We are the TLS
 * client, because we are the TCP server.
 * Flow B (we connect): we send our plaintext connection packet first. We are the TLS server.
 *
 * Both flows finish with the secure identity, which must match the plaintext deviceId and
 * must not be a protocol downgrade. Blocking calls: run on Dispatchers.IO.
 */
class HandshakeCoordinator(
    private val localIdentity: () -> Identity,
    private val tls: TlsUpgrader,
    private val scope: CoroutineScope,
    private val timeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
) {
    /** Flow A. The peer connected to our TCP listener after receiving our UDP announce. */
    fun acceptFromPeer(socket: Socket): PeerSession {
        socket.soTimeout = timeoutMs
        val local = localIdentity()
        val plainLine =
            LineReader(socket.getInputStream(), PacketCodec.MAX_IDENTITY_PACKET_SIZE).readLine()
                ?: throw HandshakeException("peer closed before sending its identity")
        val plain =
            try {
                IdentityPackets.parseConnection(decode(plainLine), local.deviceId)
            } catch (e: IdentityException) {
                throw HandshakeException("bad connection packet: ${e.message}", e)
            }
        requireSupported(plain.protocolVersion)

        val session = tls.upgrade(socket, TlsRole.CLIENT, plain.deviceId)
        return finish(session, plain, local, weAreTlsClient = true)
    }

    /** Flow B. We connect to a TCP port the peer announced over UDP or mDNS. */
    fun connectToPeer(
        address: InetSocketAddress,
        peerDeviceId: String,
    ): PeerSession {
        val local = localIdentity()
        val socket = Socket()
        socket.connect(address, timeoutMs)
        socket.soTimeout = timeoutMs
        try {
            val out = socket.getOutputStream()
            out.write(PacketCodec.encode(IdentityPackets.toConnection(local, peerDeviceId)))
            out.flush()
            val session = tls.upgrade(socket, TlsRole.SERVER, peerDeviceId)
            return finish(session, Identity(deviceId = peerDeviceId), local, weAreTlsClient = false)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private fun finish(
        session: TlsSession,
        plain: Identity,
        local: Identity,
        weAreTlsClient: Boolean,
    ): PeerSession {
        val ssl = session.socket
        val output = ssl.outputStream
        output.write(PacketCodec.encode(IdentityPackets.toSecure(local)))
        output.flush()

        // The same buffered reader stays with the channel, so bytes read ahead are not lost.
        val reader = LineReader(ssl.inputStream, PacketCodec.MAX_IDENTITY_PACKET_SIZE, buffered = true)
        ssl.soTimeout = SECURE_IDENTITY_TIMEOUT_MS
        val secureLine = reader.readLine() ?: throw HandshakeException("peer closed before its secure identity")
        ssl.soTimeout = 0

        val secure =
            try {
                IdentityPackets.parseSecure(decode(secureLine))
            } catch (e: IdentityException) {
                throw HandshakeException("bad secure identity: ${e.message}", e)
            }
        if (secure.deviceId != plain.deviceId) {
            throw HandshakeException("secure deviceId differs from the plaintext identity")
        }
        if (plain.protocolVersion != null && plain.protocolVersion != secure.protocolVersion) {
            throw HandshakeException("protocolVersion differs from the plaintext identity")
        }
        requireSupported(secure.protocolVersion)

        val channel = PacketChannel(reader, output, ssl, scope)
        return PeerSession(
            peer = secure,
            peerCertDer = session.peerCertDer,
            tlsVersion = session.protocol,
            cipherSuite = session.cipherSuite,
            channel = channel,
            remoteAddress = ssl.inetAddress,
            weAreTlsClient = weAreTlsClient,
        )
    }

    private fun decode(line: String): NetworkPacket =
        try {
            PacketCodec.decode(line)
        } catch (e: PacketException) {
            throw HandshakeException("malformed identity line: ${e.message}", e)
        }

    private fun requireSupported(version: Int?) {
        if (!IdentityPackets.isSupported(version)) {
            throw HandshakeException("peer protocol version $version is below ${IdentityPackets.PROTOCOL_VERSION}")
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val SECURE_IDENTITY_TIMEOUT_MS = 5_000
    }
}

enum class LinkDecision { ACCEPT, REPLACE_EXISTING, REJECT }

/**
 * Duplicate connections (plan section 2.2): both sides may connect at once. A new socket
 * replaces the old one only when its certificate equals the stored one.
 */
object DuplicateLinkPolicy {
    fun decide(
        existingCertDer: ByteArray?,
        newCertDer: ByteArray,
    ): LinkDecision =
        when {
            existingCertDer == null -> LinkDecision.ACCEPT
            existingCertDer.contentEquals(newCertDer) -> LinkDecision.REPLACE_EXISTING
            else -> LinkDecision.REJECT
        }
}
