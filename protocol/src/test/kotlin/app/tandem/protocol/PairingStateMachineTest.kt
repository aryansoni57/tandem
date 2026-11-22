// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingStateMachineTest {
    private val now = 1_700_000_000_000L
    private val nowSec = now / 1000

    private fun machine(state: PairingState = PairingState.NotPaired) = PairingStateMachine(state)

    @Test
    fun userRequestSendsRequestWithTimestampAndArmsTimeout() {
        val m = machine()
        val actions = m.onEvent(PairingEvent.UserRequest, now)
        assertEquals(listOf(PairingAction.SendRequest(nowSec)), actions)
        assertEquals(PairingState.Requested(now + 30_000), m.state)
        assertEquals(now + 30_000, m.nextDeadlineMs())
    }

    @Test
    fun requestTimesOutAfter30Seconds() {
        val m = machine()
        m.onEvent(PairingEvent.UserRequest, now)
        assertEquals(emptyList(), m.onEvent(PairingEvent.Tick, now + 29_999))
        assertEquals(listOf(PairingAction.TimedOut), m.onEvent(PairingEvent.Tick, now + 30_000))
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun acceptPacketInRequestedMovesToPaired() {
        val m = machine()
        m.onEvent(PairingEvent.UserRequest, now)
        val actions = m.onEvent(PairingEvent.PacketReceived(pair = true, timestampSec = null), now + 1000)
        assertEquals(listOf(PairingAction.EnteredPaired), actions)
        assertEquals(PairingState.Paired, m.state)
    }

    @Test
    fun rejectPacketInRequestedReturnsToNotPaired() {
        val m = machine()
        m.onEvent(PairingEvent.UserRequest, now)
        assertEquals(listOf(PairingAction.Rejected), m.onEvent(PairingEvent.PacketReceived(false, null), now))
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun incomingRequestShowsPromptThenExpires() {
        val m = machine()
        val actions = m.onEvent(PairingEvent.PacketReceived(true, nowSec), now)
        assertEquals(listOf(PairingAction.ShowPrompt), actions)
        assertIs<PairingState.RequestedByPeer>(m.state)
        assertEquals(listOf(PairingAction.PromptExpired), m.onEvent(PairingEvent.Tick, now + 25_000))
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun userAcceptSendsAcceptAndPairs() {
        val m = machine()
        m.onEvent(PairingEvent.PacketReceived(true, nowSec), now)
        assertEquals(
            listOf(PairingAction.SendAccept, PairingAction.EnteredPaired),
            m.onEvent(PairingEvent.UserAccept, now + 1),
        )
        assertEquals(PairingState.Paired, m.state)
    }

    @Test
    fun userRejectSendsRejectWhenPrompted() {
        val m = machine()
        m.onEvent(PairingEvent.PacketReceived(true, nowSec), now)
        assertEquals(listOf(PairingAction.SendReject), m.onEvent(PairingEvent.UserReject, now + 1))
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun cancelFromPeerWhilePromptedIsCancelled() {
        val m = machine()
        m.onEvent(PairingEvent.PacketReceived(true, nowSec), now)
        assertEquals(listOf(PairingAction.Cancelled), m.onEvent(PairingEvent.PacketReceived(false, null), now))
    }

    @Test
    fun requestWithMissingTimestampIsRejected() {
        val m = machine()
        val actions = m.onEvent(PairingEvent.PacketReceived(true, null), now)
        assertTrue(actions.first() == PairingAction.SendReject)
        assertIs<PairingAction.Error>(actions.last())
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun clockSkewBoundaryIsInclusiveAt1800Seconds() {
        val inside = machine()
        assertEquals(
            listOf(PairingAction.ShowPrompt),
            inside.onEvent(PairingEvent.PacketReceived(true, nowSec + 1799), now),
        )

        val outside = machine()
        val actions = outside.onEvent(PairingEvent.PacketReceived(true, nowSec + 1801), now)
        assertEquals(listOf(PairingAction.SendReject, PairingAction.ClockSkew(1801)), actions)
        assertEquals(PairingState.NotPaired, outside.state)
    }

    @Test
    fun clockSkewNegativeSideIsAlsoRejected() {
        val m = machine()
        val actions = m.onEvent(PairingEvent.PacketReceived(true, nowSec - 1801), now)
        assertEquals(PairingAction.ClockSkew(-1801), actions[1])
    }

    @Test
    fun falsePacketWhilePairedUnpairs() {
        val m = machine(PairingState.Paired)
        assertEquals(
            listOf(PairingAction.DeleteTrust, PairingAction.Unpaired),
            m.onEvent(PairingEvent.PacketReceived(false, null), now),
        )
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun requestWhilePairedUnpairsThenPromptsAsNewRequest() {
        val m = machine(PairingState.Paired)
        val actions = m.onEvent(PairingEvent.PacketReceived(true, nowSec), now)
        assertEquals(
            listOf(PairingAction.DeleteTrust, PairingAction.Unpaired, PairingAction.ShowPrompt),
            actions,
        )
        assertIs<PairingState.RequestedByPeer>(m.state)
    }

    @Test
    fun userRequestWhilePairedIsIgnored() {
        val m = machine(PairingState.Paired)
        assertEquals(emptyList(), m.onEvent(PairingEvent.UserRequest, now))
        assertEquals(PairingState.Paired, m.state)
    }

    @Test
    fun userUnpairFromPairedSendsFalseAndDeletesTrust() {
        val m = machine(PairingState.Paired)
        assertEquals(
            listOf(PairingAction.SendReject, PairingAction.DeleteTrust, PairingAction.Unpaired),
            m.onEvent(PairingEvent.UserUnpair, now),
        )
        assertEquals(PairingState.NotPaired, m.state)
    }

    @Test
    fun notPairedIgnoresFalsePacketsAndTicks() {
        val m = machine()
        assertEquals(emptyList(), m.onEvent(PairingEvent.PacketReceived(false, null), now))
        assertEquals(emptyList(), m.onEvent(PairingEvent.Tick, now))
    }
}
