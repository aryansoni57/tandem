// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sftp

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The server's SSH host key. It is made once and kept, so the laptop sees the same key every time and does
 * not warn about a changed host. Stored as the standard PKCS8 (private) and X.509 (public) encodings.
 */
object HostKeys {
    private const val ALGORITHM = "RSA"
    private const val BITS = 3072

    fun generate(): KeyPair =
        KeyPairGenerator.getInstance(ALGORITHM).apply { initialize(BITS) }.generateKeyPair()

    fun encodePrivate(pair: KeyPair): ByteArray = pair.private.encoded

    fun encodePublic(pair: KeyPair): ByteArray = pair.public.encoded

    fun decode(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): KeyPair {
        val factory = KeyFactory.getInstance(ALGORITHM)
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(publicKey)),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateKey)),
        )
    }
}
