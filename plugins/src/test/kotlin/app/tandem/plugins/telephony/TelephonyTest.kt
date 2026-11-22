// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.telephony

import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.CallEvent
import app.tandem.protocol.body.TelephonyBody
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyBodyTest {
    @Test
    fun bodyUsesTheDesktopFieldNames() {
        val json = TelephonyBody(CallEvent.Ringing, "+911234", "Maya", "AAAA").toJson()
        assertEquals("ringing", json.getString("event"))
        assertEquals("+911234", json.getString("phoneNumber"))
        assertEquals("Maya", json.getString("contactName"))
        assertEquals("AAAA", json.getString("phoneThumbnail"))
        assertNull(json.getBoolean("isCancel"))
        assertEquals(true, TelephonyBody(CallEvent.Talking, cancel = true).toJson().getBoolean("isCancel"))
        assertEquals("missedCall", TelephonyBody(CallEvent.MissedCall).toJson().getString("event"))
    }
}

class CallEventMachineTest {
    private val machine = CallEventMachine()

    private fun events(steps: List<CallEventMachine.Step>) = steps.map { it.event to it.cancel }

    @Test
    fun answeredIncomingCallRingsThenTalksThenClosesTheNotice() {
        assertEquals(listOf(CallEvent.Ringing to false), events(machine.onState(CallState.Ringing, "+91")))
        assertEquals(listOf(CallEvent.Talking to false), events(machine.onState(CallState.Offhook, null)))
        val end = machine.onState(CallState.Idle, null)
        assertEquals(listOf(CallEvent.Talking to true), events(end))
        // The number survives from the ringing state to the later ones.
        assertEquals("+91", end.single().number)
    }

    @Test
    fun unansweredCallClosesTheRingNoticeAndReportsMissed() {
        machine.onState(CallState.Ringing, "+91")
        val end = machine.onState(CallState.Idle, null)
        assertEquals(listOf(CallEvent.Ringing to true, CallEvent.MissedCall to false), events(end))
        assertEquals("+91", end.last().number)
    }

    @Test
    fun outgoingCallIsJustTalking() {
        assertEquals(listOf(CallEvent.Talking to false), events(machine.onState(CallState.Offhook, null)))
        assertEquals(listOf(CallEvent.Talking to true), events(machine.onState(CallState.Idle, null)))
    }

    @Test
    fun repeatedStatesAreIgnoredButALateNumberIsSentOnce() {
        assertEquals(1, machine.onState(CallState.Ringing, null).size)
        val late = machine.onState(CallState.Ringing, "+91")
        assertEquals("+91", late.single().number)
        assertTrue(machine.onState(CallState.Ringing, "+91").isEmpty())
        assertTrue(machine.onState(CallState.Idle, null).isNotEmpty())
        assertTrue(machine.onState(CallState.Idle, null).isEmpty())
    }
}

class CallEventsTest {
    private class Settings(var alerts: Boolean = true, var pause: Boolean = true) : CallSettings {
        override suspend fun alertsOn() = alerts

        override suspend fun pauseMediaOn() = pause
    }

    private class Contacts : ContactLookup {
        val asked = mutableListOf<String>()

        override suspend fun find(number: String): ContactInfo? {
            asked += number
            return ContactInfo("Maya", "PHOTO")
        }
    }

    private class Muter : CallMuter {
        var muted = 0
        var restored = 0

        override fun mute() {
            muted++
        }

        override fun restore() {
            restored++
        }
    }

    private val peers = FakePeerSender()
    private val settings = Settings()
    private val contacts = Contacts()
    private val muter = Muter()
    private var paused = 0
    private val events =
        CallEvents(peers, settings, contacts, muter, { listOf("dev1", "dev2") }, { paused++ })

    @Test
    fun ringingGoesToEveryDeviceWithNameAndPhotoAndPausesMedia() =
        runBlocking {
            events.onState(CallState.Ringing, "+91")
            assertEquals(listOf("dev1", "dev2"), peers.sent.map { it.first })
            val body = peers.sent.first().second.body
            assertEquals("ringing", body.getString("event"))
            assertEquals("Maya", body.getString("contactName"))
            assertEquals("PHOTO", body.getString("phoneThumbnail"))
            assertEquals(1, paused)
        }

    @Test
    fun alertsOffSendsNothingAndPausesNothing() =
        runBlocking {
            settings.alerts = false
            events.onState(CallState.Ringing, "+91")
            assertTrue(peers.sent.isEmpty())
            assertEquals(0, paused)
        }

    @Test
    fun pauseOffStillSendsTheAlert() =
        runBlocking {
            settings.pause = false
            events.onState(CallState.Ringing, "+91")
            assertEquals(2, peers.sent.size)
            assertEquals(0, paused)
        }

    @Test
    fun cancelPacketsSkipTheContactLookupAndIdleRestoresTheRinger() =
        runBlocking {
            events.onState(CallState.Ringing, "+91")
            contacts.asked.clear()
            peers.sent.clear()
            events.onState(CallState.Idle, null)
            assertTrue(contacts.asked.size <= 1)
            assertEquals(1, muter.restored)
            val types = peers.sent.map { it.second.body.getString("event") }
            assertTrue(types.contains("missedCall"))
        }
}

class TelephonyPluginTest {
    private val peers = FakePeerSender()
    private val muter =
        object : CallMuter {
            var muted = 0

            override fun mute() {
                muted++
            }

            override fun restore() = Unit
        }
    private val events =
        CallEvents(
            peers,
            object : CallSettings {
                override suspend fun alertsOn() = true

                override suspend fun pauseMediaOn() = false
            },
            object : ContactLookup {
                override suspend fun find(number: String): ContactInfo? = null
            },
            muter,
            { emptyList() },
            {},
        )
    private val plugin = TelephonyPlugin(events)

    @Test
    fun muteRequestSilencesTheRinger() =
        runBlocking {
            val mute = NetworkPacket(1, PacketTypes.TELEPHONY_REQUEST_MUTE, buildJsonObject { put("action", "mute") })
            assertTrue(plugin.onPacket(mute))
            assertEquals(1, muter.muted)
        }

    @Test
    fun otherActionsAndTypesAreIgnored() =
        runBlocking {
            val other = NetworkPacket(1, PacketTypes.TELEPHONY_REQUEST_MUTE, buildJsonObject { put("action", "x") })
            assertTrue(plugin.onPacket(other))
            assertEquals(0, muter.muted)
            assertFalse(plugin.onPacket(NetworkPacket(1, PacketTypes.PING, buildJsonObject {})))
        }
}
