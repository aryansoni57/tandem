// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import app.tandem.testing.mockpeer.MockPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateFactoryTest {
    private val deviceId = "0123456789abcdef0123456789abcdef"

    @Test
    fun subjectHasCnEqualToDeviceIdAndTheReferenceOuTerms() {
        assertEquals("CN=$deviceId, OU=KDE Connect, O=KDE", CertificateFactory.subjectFor(deviceId))
    }

    @Test
    fun softwareCertificateIsUsableForItsDeviceOnly() {
        val cert = CertificateFactory.softwareSelfSigned(deviceId, MockPeer.generateEcKeyPair())
        assertTrue(CertificateFactory.isUsable(cert, deviceId))
        assertFalse(CertificateFactory.isUsable(cert, "ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun fingerprintIsColonSeparatedUppercaseSha256() {
        val fp = CertificateFactory.sha256Fingerprint(byteArrayOf(1, 2, 3))
        assertEquals(32 * 2 + 31, fp.length)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
