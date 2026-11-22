// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tandem.transport.StaticKeyMaterial
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.cert.CertificateFactory as JavaCertificateFactory

/**
 * Fallback when the Keystore TLS self-test fails. The EC key is generated in software, then
 * its PKCS#8 form is wrapped with an AES-256-GCM key held in AndroidKeyStore. The blob lives in
 * noBackupFilesDir, so it is never backed up.
 */
object WrappedSoftwareIdentity {
    private const val WRAP_ALIAS = "tandem_wrap_v1"
    private const val KEY_FILE = "key.wrapped"
    private const val CERT_FILE = "cert.der"
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun loadOrCreate(
        context: Context,
        deviceId: String,
    ): LocalIdentity {
        val dir = File(context.noBackupFilesDir, "identity").apply { mkdirs() }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        if (keyFile.exists() && certFile.exists()) {
            loadSaved(keyFile, certFile, deviceId)?.let { return it }
        }

        val pair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val cert = CertificateFactory.softwareSelfSigned(deviceId, pair)
        keyFile.writeBytes(wrap(pair.private.encoded))
        certFile.writeBytes(cert.encoded)
        return LocalIdentity(
            deviceId,
            IdentityBackend.WRAPPED_SOFTWARE,
            StaticKeyMaterial(WRAP_ALIAS, pair.private, cert),
        )
    }

    private fun loadSaved(
        keyFile: File,
        certFile: File,
        deviceId: String,
    ): LocalIdentity? =
        try {
            val cert =
                JavaCertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
            if (!CertificateFactory.isUsable(cert, deviceId)) {
                null
            } else {
                val spec = PKCS8EncodedKeySpec(unwrap(keyFile.readBytes()))
                val key = KeyFactory.getInstance("EC").generatePrivate(spec)
                LocalIdentity(deviceId, IdentityBackend.WRAPPED_SOFTWARE, StaticKeyMaterial(WRAP_ALIAS, key, cert))
            }
        } catch (_: Exception) {
            // Unreadable blob: fall through and generate a new identity.
            null
        }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Output layout: 12-byte IV followed by the ciphertext. */
    internal fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    internal fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "wrapped key too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
    }
}
