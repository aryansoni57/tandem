// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitiesTest {
    private val battery = CapabilitySpec("battery", setOf("kdeconnect.battery"), setOf("kdeconnect.battery"))
    private val ping = CapabilitySpec("ping", setOf("kdeconnect.ping"), setOf("kdeconnect.ping"), minSdk = 99)
    private val disabled = CapabilitySpec("x", setOf("kdeconnect.x"), setOf("kdeconnect.x"), enabled = false)

    @Test
    fun advertisesOnlyEnabledPluginsMeetingMinSdk() {
        val adv = Capabilities.advertised(listOf(battery, ping, disabled), deviceSdk = 34)
        assertEquals(setOf("kdeconnect.battery"), adv.incoming)
        assertEquals(setOf("kdeconnect.battery"), adv.outgoing)
    }

    @Test
    fun loadRuleNeedsOverlapAndPairing() {
        val peerIn = setOf("kdeconnect.battery")
        val peerOut = setOf<String>()
        assertTrue(Capabilities.shouldLoad(battery, peerIn, peerOut, paired = true))
        assertFalse(Capabilities.shouldLoad(battery, peerIn, peerOut, paired = false))
        assertTrue(Capabilities.shouldLoad(battery.copy(listenToUnpaired = true), peerIn, peerOut, paired = false))
        assertFalse(Capabilities.shouldLoad(battery, setOf("kdeconnect.ping"), setOf("kdeconnect.ping"), paired = true))
    }

    @Test
    fun loadRuleAcceptsOverlapInOutgoingDirection() {
        assertTrue(Capabilities.shouldLoad(battery, emptySet(), setOf("kdeconnect.battery"), paired = true))
    }

    @Test
    fun sendGateExemptsIdentityAndPair() {
        assertTrue(Capabilities.canSend("kdeconnect.identity", emptySet()))
        assertTrue(Capabilities.canSend("kdeconnect.pair", emptySet()))
        assertFalse(Capabilities.canSend("kdeconnect.ping", emptySet()))
        assertTrue(Capabilities.canSend("kdeconnect.ping", setOf("kdeconnect.ping")))
    }
}
