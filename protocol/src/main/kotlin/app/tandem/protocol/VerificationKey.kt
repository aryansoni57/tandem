// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import java.security.MessageDigest

/**
 * The 8-character pairing code both users compare. Byte order matters: the larger
 * SubjectPublicKeyInfo DER goes first, then the timestamp as ASCII decimal.
 */
object VerificationKey {
    const val LENGTH = 8

    fun compute(
        ourPublicKeyDer: ByteArray,
        peerPublicKeyDer: ByteArray,
        pairTimestampSec: Long,
    ): String {
        val (first, second) =
            if (unsignedCompare(ourPublicKeyDer, peerPublicKeyDer) < 0) {
                peerPublicKeyDer to ourPublicKeyDer
            } else {
                ourPublicKeyDer to peerPublicKeyDer
            }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(first)
        digest.update(second)
        digest.update(pairTimestampSec.toString().toByteArray(Charsets.US_ASCII))
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.substring(0, LENGTH).uppercase()
    }

    /** Lexicographic comparison of unsigned bytes, shorter array first on a common prefix. */
    internal fun unsignedCompare(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
