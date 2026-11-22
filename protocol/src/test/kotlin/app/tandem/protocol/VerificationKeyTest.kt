// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden vectors computed independently with Python's hashlib using the same byte-order
 * rules. Both sides of a pairing must compute the same key regardless of argument order.
 */
class VerificationKeyTest {
    private val ours = ByteArray(64) { it.toByte() }
    private val peer = ByteArray(33) { (0xff).toByte() }.also { it[32] = 1 }

    @Test
    fun matchesPythonGoldenVector() {
        assertEquals("9D9B41AD", VerificationKey.compute(ours, peer, 1732030000L))
    }

    @Test
    fun orderOfKeysDoesNotMatter() {
        assertEquals(
            VerificationKey.compute(ours, peer, 1732030000L),
            VerificationKey.compute(peer, ours, 1732030000L),
        )
    }

    @Test
    fun timestampChangesTheKey() {
        assertEquals(8, VerificationKey.compute(ours, peer, 1L).length)
        kotlin.test.assertNotEquals(VerificationKey.compute(ours, peer, 1L), VerificationKey.compute(ours, peer, 2L))
    }

    @Test
    fun prefixOrderingUsesLengthAsTieBreak() {
        val a = byteArrayOf(0x30, 0x59)
        val b = byteArrayOf(0x30, 0x59, 0x00)
        assertEquals(VerificationKey.compute(a, b, 5L), VerificationKey.compute(b, a, 5L))
    }
}
