// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tandem.transport.StaticKeyMaterial
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * The non-extractable EC P-256 identity key in AndroidKeyStore. The Keystore self-signs the
 * certificate, so no BouncyCastle is needed on this path.
 */
object KeystoreIdentity {
    const val ALIAS = "tandem_identity_v1"
    private const val PROVIDER = "AndroidKeyStore"

    /** Returns the existing identity when its CN matches and it is usable, otherwise creates a new one. */
    fun loadOrCreate(deviceId: String): LocalIdentity {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getCertificate(ALIAS) as? X509Certificate
        if (existing != null && CertificateFactory.isUsable(existing, deviceId)) {
            val key = store.getKey(ALIAS, null) as PrivateKey
            return LocalIdentity(deviceId, IdentityBackend.KEYSTORE, StaticKeyMaterial(ALIAS, key, existing))
        }
        // Wrong CN or expired: regenerating also drops all trust, as the reference does.
        if (existing != null) store.deleteEntry(ALIAS)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal(CertificateFactory.subjectFor(deviceId)))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(CertificateFactory.notBefore())
                .setCertificateNotAfter(CertificateFactory.notAfter())
                .build(),
        )
        val pair = generator.generateKeyPair()
        val cert = store.getCertificate(ALIAS) as X509Certificate
        return LocalIdentity(deviceId, IdentityBackend.KEYSTORE, StaticKeyMaterial(ALIAS, pair.private, cert))
    }
}
