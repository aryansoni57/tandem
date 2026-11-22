// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

/**
 * Pure pairing state machine (plan section 2.4). Driven by events; the caller supplies the
 * clock on every call, so timeouts are testable without real time.
 */
class PairingStateMachine(initial: PairingState = PairingState.NotPaired) {
    var state: PairingState = initial
        private set

    /** Handles one event at [nowMs] and returns the side effects the caller must perform. */
    fun onEvent(
        event: PairingEvent,
        nowMs: Long,
    ): List<PairingAction> =
        when (event) {
            PairingEvent.UserRequest -> onUserRequest(nowMs)
            PairingEvent.UserAccept -> onUserAccept()
            PairingEvent.UserReject -> onUserReject()
            PairingEvent.UserUnpair -> onUserUnpair()
            is PairingEvent.PacketReceived -> onPacket(event.pair, event.timestampSec, nowMs)
            PairingEvent.Tick -> onTick(nowMs)
        }

    /** Milliseconds until the next timeout fires, or null when no timer is armed. */
    fun nextDeadlineMs(): Long? =
        when (val s = state) {
            is PairingState.Requested -> s.deadlineMs
            is PairingState.RequestedByPeer -> s.deadlineMs
            else -> null
        }

    private fun onUserRequest(nowMs: Long): List<PairingAction> {
        if (state !is PairingState.NotPaired) return emptyList()
        state = PairingState.Requested(nowMs + REQUEST_TIMEOUT_MS)
        return listOf(PairingAction.SendRequest(nowMs / 1000))
    }

    private fun onUserAccept(): List<PairingAction> {
        if (state !is PairingState.RequestedByPeer) return emptyList()
        state = PairingState.Paired
        return listOf(PairingAction.SendAccept, PairingAction.EnteredPaired)
    }

    private fun onUserReject(): List<PairingAction> =
        when (state) {
            is PairingState.RequestedByPeer, is PairingState.Requested -> {
                state = PairingState.NotPaired
                listOf(PairingAction.SendReject)
            }
            else -> emptyList()
        }

    private fun onUserUnpair(): List<PairingAction> {
        if (state !is PairingState.Paired) return emptyList()
        state = PairingState.NotPaired
        return listOf(PairingAction.SendReject, PairingAction.DeleteTrust, PairingAction.Unpaired)
    }

    private fun onPacket(
        pair: Boolean,
        timestampSec: Long?,
        nowMs: Long,
    ): List<PairingAction> {
        if (!pair) {
            return when (state) {
                is PairingState.Requested -> {
                    state = PairingState.NotPaired
                    listOf(PairingAction.Rejected)
                }
                is PairingState.RequestedByPeer -> {
                    state = PairingState.NotPaired
                    listOf(PairingAction.Cancelled)
                }
                is PairingState.Paired -> {
                    state = PairingState.NotPaired
                    listOf(PairingAction.DeleteTrust, PairingAction.Unpaired)
                }
                PairingState.NotPaired -> emptyList()
            }
        }
        return when (state) {
            // An accept packet carries no timestamp. It completes our outgoing request.
            is PairingState.Requested -> {
                state = PairingState.Paired
                listOf(PairingAction.EnteredPaired)
            }
            is PairingState.RequestedByPeer -> emptyList()
            is PairingState.Paired -> {
                // Reference behaviour: a new request while paired unpairs, then becomes a new request.
                state = PairingState.NotPaired
                listOf(PairingAction.DeleteTrust, PairingAction.Unpaired) + onIncomingRequest(timestampSec, nowMs)
            }
            PairingState.NotPaired -> onIncomingRequest(timestampSec, nowMs)
        }
    }

    private fun onIncomingRequest(
        timestampSec: Long?,
        nowMs: Long,
    ): List<PairingAction> {
        if (timestampSec == null) {
            return listOf(PairingAction.SendReject, PairingAction.Error("pairing request has no timestamp"))
        }
        val skewSec = timestampSec - nowMs / 1000
        if (skewSec > MAX_CLOCK_SKEW_SEC || skewSec < -MAX_CLOCK_SKEW_SEC) {
            return listOf(PairingAction.SendReject, PairingAction.ClockSkew(skewSec))
        }
        state = PairingState.RequestedByPeer(nowMs + PROMPT_WINDOW_MS)
        return listOf(PairingAction.ShowPrompt)
    }

    private fun onTick(nowMs: Long): List<PairingAction> =
        when (val s = state) {
            is PairingState.Requested ->
                if (nowMs >= s.deadlineMs) {
                    state = PairingState.NotPaired
                    listOf(PairingAction.TimedOut)
                } else {
                    emptyList()
                }
            is PairingState.RequestedByPeer ->
                if (nowMs >= s.deadlineMs) {
                    state = PairingState.NotPaired
                    listOf(PairingAction.PromptExpired)
                } else {
                    emptyList()
                }
            else -> emptyList()
        }

    companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val PROMPT_WINDOW_MS = 25_000L
        const val MAX_CLOCK_SKEW_SEC = 1800L
    }
}

sealed interface PairingState {
    data object NotPaired : PairingState

    data class Requested(val deadlineMs: Long) : PairingState

    data class RequestedByPeer(val deadlineMs: Long) : PairingState

    data object Paired : PairingState
}

sealed interface PairingEvent {
    /** The user tapped Pair on our side. */
    data object UserRequest : PairingEvent

    /** The user accepted an incoming request. */
    data object UserAccept : PairingEvent

    /** The user rejected an incoming request or cancelled ours. */
    data object UserReject : PairingEvent

    /** The user unpaired the device. */
    data object UserUnpair : PairingEvent

    /** A `kdeconnect.pair` packet arrived. [timestampSec] is null for accept packets. */
    data class PacketReceived(val pair: Boolean, val timestampSec: Long?) : PairingEvent

    /** Periodic check for expired timers. */
    data object Tick : PairingEvent
}

sealed interface PairingAction {
    data class SendRequest(val timestampSec: Long) : PairingAction

    data object SendAccept : PairingAction

    data object SendReject : PairingAction

    data object ShowPrompt : PairingAction

    data object EnteredPaired : PairingAction

    data object DeleteTrust : PairingAction

    data object Unpaired : PairingAction

    data object Rejected : PairingAction

    data object Cancelled : PairingAction

    data object TimedOut : PairingAction

    data object PromptExpired : PairingAction

    data class ClockSkew(val skewSec: Long) : PairingAction

    data class Error(val message: String) : PairingAction
}
