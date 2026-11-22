// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.battery

import app.tandem.core.device.PeerStateStore
import app.tandem.plugins.FakeContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.BatteryBody
import app.tandem.protocol.body.BatteryRequestBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPluginTest {
    private class FakeSource(initial: BatteryReading) : BatterySource {
        val flow = MutableStateFlow(initial)
        override val readings: StateFlow<BatteryReading> = flow
        var started = false

        override fun start() {
            started = true
        }
    }

    private val allowed = setOf(PacketTypes.BATTERY, PacketTypes.BATTERY_REQUEST)

    private fun bodies(fake: FakeContext): List<BatteryBody> =
        fake.sent.filter { it.type == PacketTypes.BATTERY }.map { BatteryBody.parse(it.body)!! }

    @Test
    fun connectSendsOwnBatteryAndAsksPeerForItsBattery() {
        val fake = FakeContext(allowed)
        val source = FakeSource(BatteryReading(level = 64, charging = true, low = false))
        val plugin = BatteryPlugin(fake.context, source, PeerStateStore())
        runBlocking { plugin.onCreate() }
        assertTrue(source.started)
        assertEquals(listOf(BatteryBody(64, true, BatteryBody.THRESHOLD_NONE)), bodies(fake))
        assertTrue(fake.sent.any { it.type == PacketTypes.BATTERY_REQUEST })
    }

    @Test
    fun nothingIsSentWhenPeerDoesNotAcceptBattery() {
        val fake = FakeContext(emptySet())
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(50, false, false)), PeerStateStore())
        runBlocking { plugin.onCreate() }
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun changesAreSentAndLowIsReportedOnlyOnTransition() {
        val fake = FakeContext(allowed)
        val source = FakeSource(BatteryReading(40, false, false))
        val plugin = BatteryPlugin(fake.context, source, PeerStateStore())
        runBlocking { plugin.onCreate() }

        source.flow.value = BatteryReading(14, false, true)
        source.flow.value = BatteryReading(14, true, true)
        source.flow.value = BatteryReading(15, true, true)

        val sent = bodies(fake)
        assertEquals(4, sent.size)
        assertEquals(BatteryBody.THRESHOLD_NONE, sent[0].thresholdEvent)
        assertEquals(BatteryBody.THRESHOLD_LOW, sent[1].thresholdEvent)
        assertEquals(14, sent[1].currentCharge)
        assertEquals(BatteryBody.THRESHOLD_NONE, sent[2].thresholdEvent)
        assertTrue(sent[2].isCharging)
        assertEquals(15, sent[3].currentCharge)
    }

    @Test
    fun batteryThatIsAlreadyLowAtConnectDoesNotAlert() {
        val fake = FakeContext(allowed)
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(10, false, true)), PeerStateStore())
        runBlocking { plugin.onCreate() }
        assertEquals(BatteryBody.THRESHOLD_NONE, bodies(fake).single().thresholdEvent)
    }

    @Test
    fun requestGetsCurrentStatusWithoutAlert() {
        val fake = FakeContext(allowed)
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(77, true, true)), PeerStateStore())
        val handled = runBlocking { plugin.onPacket(BatteryRequestBody.toPacket(id = 1)) }
        assertTrue(handled)
        assertEquals(listOf(BatteryBody(77, true, BatteryBody.THRESHOLD_NONE)), bodies(fake))
    }

    @Test
    fun peerBatteryIsStoredForTheDeviceScreen() {
        val fake = FakeContext(allowed)
        val peers = PeerStateStore()
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(1, false, false)), peers)
        val packet = BatteryBody(33, false, BatteryBody.THRESHOLD_LOW).toPacket(id = 5)
        assertTrue(runBlocking { plugin.onPacket(packet) })
        val stored = peers.batteries.value[FakeContext.DEVICE]
        assertEquals(33, stored?.level)
        assertFalse(stored!!.charging)
        assertTrue(stored.low)
    }

    @Test
    fun invalidPeerBatteryIsIgnored() {
        val fake = FakeContext(allowed)
        val peers = PeerStateStore()
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(1, false, false)), peers)
        val bad = NetworkPacket(6, PacketTypes.BATTERY, kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(runBlocking { plugin.onPacket(bad) })
        assertNull(peers.batteries.value[FakeContext.DEVICE])
    }

    @Test
    fun destroyClearsThePeerBattery() {
        val fake = FakeContext(allowed)
        val peers = PeerStateStore()
        val plugin = BatteryPlugin(fake.context, FakeSource(BatteryReading(1, false, false)), peers)
        runBlocking {
            plugin.onPacket(BatteryBody(20, false).toPacket(id = 7))
            plugin.onDestroy()
        }
        assertNull(peers.batteries.value[FakeContext.DEVICE])
    }

    @Test
    fun readingFromSystemIntentsIsClampedAndSkipsUnknownLevel() {
        assertEquals(BatteryReading(50, false, false), BatteryReading.from(50, 100, false, false))
        assertEquals(BatteryReading(100, true, false), BatteryReading.from(200, 200, true, false))
        assertNull(BatteryReading.from(-1, 100, false, false))
        assertNull(BatteryReading.from(10, 0, false, false))
    }
}
