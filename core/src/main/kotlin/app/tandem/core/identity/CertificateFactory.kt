// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import app.tandem.transport.TlsUpgrader
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.security.cert.CertificateFactory as JavaCertificateFactory

/**
 * Certificate subject, validity and fingerprint rules (plan section 2.3). The subject CN must
 * equal the deviceId, because the desktop verifies the peer name against it.
 */
object CertificateFactory {
    private const val DAY_MS = 86_400_000L
    private const val BACKDATE_DAYS = 365L
    private const val VALID_YEARS = 10L

    fun subjectFor(deviceId: String): String = "CN=$deviceId, OU=KDE Connect, O=KDE"

    /** notBefore is backdated a year to tolerate clock skew, as the reference does. */
    fun notBefore(nowMs: Long = System.currentTimeMillis()): Date = Date(nowMs - BACKDATE_DAYS * DAY_MS)

    fun notAfter(nowMs: Long = System.currentTimeMillis()): Date = Date(nowMs + VALID_YEARS * 365 * DAY_MS)

    /** Software path only. The Keystore signs its own certificate, so BouncyCastle is not needed there. */
    fun softwareSelfSigned(
        deviceId: String,
        keyPair: KeyPair,
    ): X509Certificate {
        val subject = X500Name(subjectFor(deviceId))
        val builder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger.ONE,
                notBefore(),
                notAfter(),
                subject,
                keyPair.public,
            )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Usable when the CN matches [deviceId] and the certificate has not expired. */
    fun isUsable(
        cert: X509Certificate,
        deviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = TlsUpgrader.commonName(cert) == deviceId && cert.notAfter.time > nowMs

    /** Colon-separated uppercase SHA-256 over the certificate DER, as `kdeconnect-cli --encryption-info` shows. */
    fun sha256Fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    /** SubjectPublicKeyInfo DER of a peer certificate, used by the verification key. */
    fun spkiOf(certDer: ByteArray): ByteArray {
        val cert =
            JavaCertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        return cert.publicKey.encoded
    }
}
