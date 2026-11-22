// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateLinkTest {
    private val cert = byteArrayOf(1, 2, 3)
    private val otherCert = byteArrayOf(9, 9, 9)

    @Test
    fun firstLinkIsAccepted() {
        assertEquals(LinkDecision.ACCEPT, DuplicateLinkPolicy.decide(existingCertDer = null, newCertDer = cert))
    }

    @Test
    fun sameCertificateReplacesTheOldSocket() {
        assertEquals(LinkDecision.REPLACE_EXISTING, DuplicateLinkPolicy.decide(cert, cert.copyOf()))
    }

    @Test
    fun differentCertificateIsRejected() {
        assertEquals(LinkDecision.REJECT, DuplicateLinkPolicy.decide(cert, otherCert))
    }

    @Test
    fun rateLimiterAllowsOnePerIntervalPerKey() {
        var now = 0L
        val limiter = RateLimiter(minIntervalMs = 1000, clock = { now })
        assertTrue(limiter.tryAcquire("10.0.0.2"))
        assertFalse(limiter.tryAcquire("10.0.0.2"))
        assertTrue(limiter.tryAcquire("10.0.0.3"), "other keys are independent")
        now = 999
        assertFalse(limiter.tryAcquire("10.0.0.2"))
        now = 1000
        assertTrue(limiter.tryAcquire("10.0.0.2"))
    }
}
