// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.telephony

import app.tandem.core.device.PeerSender
import app.tandem.plugins.HandledLog
import app.tandem.plugins.HandledType
import app.tandem.protocol.body.CallEvent
import app.tandem.protocol.body.TelephonyBody

/** The phone's call states, as the system's PHONE_STATE broadcast reports them. */
enum class CallState { Idle, Ringing, Offhook }

/**
 * Turns call state changes into the packets the desktop expects. The number is only given with the
 * ringing state, so it is kept for the later states of the same call.
 *
 * - ringing: shows the incoming call.
 * - ringing, then offhook: the call was answered, so the desktop shows "talking" in its place.
 * - ringing, then idle: closes the ringing notice and reports a missed call.
 * - idle, then offhook: an outgoing call, shown as "talking".
 * - offhook, then idle: closes the "talking" notice.
 */
class CallEventMachine {
    private var state = CallState.Idle
    private var number: String? = null

    /** Returns the events to send for this change, most important last. Empty when nothing changed. */
    fun onState(
        next: CallState,
        incomingNumber: String?,
    ): List<Step> {
        if (next == state) {
            // Some phones broadcast ringing twice, the second time with the number. Say it again so the
            // desktop's notice gets the number and the contact name.
            if (next == CallState.Ringing && number == null && incomingNumber != null) {
                number = incomingNumber
                return listOf(Step(CallEvent.Ringing, incomingNumber, cancel = false))
            }
            return emptyList()
        }
        val before = state
        state = next
        if (next == CallState.Ringing) number = incomingNumber
        val current = number
        return when {
            next == CallState.Ringing -> listOf(Step(CallEvent.Ringing, current, cancel = false))
            next == CallState.Offhook -> listOf(Step(CallEvent.Talking, current, cancel = false))
            before == CallState.Ringing ->
                // Never answered. Close the ringing notice, then say it was missed.
                listOf(
                    Step(CallEvent.Ringing, current, cancel = true),
                    Step(CallEvent.MissedCall, current, cancel = false),
                ).also { number = null }
            else ->
                listOf(Step(CallEvent.Talking, current, cancel = true)).also { number = null }
        }
    }

    /** One event to send. [number] can be null when the system hid it. */
    data class Step(
        val event: CallEvent,
        val number: String?,
        val cancel: Boolean,
    )
}

/** A contact found for a number: the display name and a small base64 photo, either of which may be null. */
data class ContactInfo(
    val name: String?,
    val thumbnailBase64: String?,
)

interface ContactLookup {
    /** Returns null when the number is unknown or contacts access is missing. */
    suspend fun find(number: String): ContactInfo?
}

/** The user's choices for calls. */
interface CallSettings {
    suspend fun alertsOn(): Boolean

    suspend fun pauseMediaOn(): Boolean
}

/** Silences the ringer while a call rings, and gives it back afterwards. */
interface CallMuter {
    fun mute()

    fun restore()
}

/**
 * Sends call events to every connected device. [pauseMedia] runs when a call starts ringing and the
 * user wants the laptop's player paused. [connectedDevices] returns the devices to tell.
 */
class CallEvents(
    private val peers: PeerSender,
    private val settings: CallSettings,
    private val contacts: ContactLookup,
    private val muter: CallMuter,
    private val connectedDevices: () -> List<String>,
    private val pauseMedia: suspend () -> Unit,
    private val log: HandledLog = HandledLog(),
    private val machine: CallEventMachine = CallEventMachine(),
) {
    /** Called for every PHONE_STATE broadcast. */
    suspend fun onState(
        state: CallState,
        incomingNumber: String?,
    ) {
        val steps = synchronizedSteps(state, incomingNumber)
        if (state == CallState.Idle) muter.restore()
        if (steps.isEmpty() || !settings.alertsOn()) return
        if (steps.first().event == CallEvent.Ringing && !steps.first().cancel && settings.pauseMediaOn()) pauseMedia()
        for (step in steps) send(step)
    }

    /** The desktop's "mute" button. */
    fun muteRinger() = muter.mute()

    private fun synchronizedSteps(
        state: CallState,
        number: String?,
    ): List<CallEventMachine.Step> = synchronized(machine) { machine.onState(state, number) }

    private suspend fun send(step: CallEventMachine.Step) {
        val contact = if (!step.cancel) step.number?.let { contacts.find(it) } else null
        val body =
            TelephonyBody(
                event = step.event,
                phoneNumber = step.number,
                contactName = contact?.name,
                thumbnailBase64 = contact?.thumbnailBase64,
                cancel = step.cancel,
            )
        if (step.event == CallEvent.MissedCall && !step.cancel) {
            log.add(HandledType.Missed, contact?.name ?: step.number ?: "Unknown number", "Missed call")
        }
        val packet = body.toPacket()
        for (deviceId in connectedDevices()) peers.sendPacket(deviceId, packet)
    }
}
