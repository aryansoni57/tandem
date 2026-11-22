// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sms

import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.plugins.HandledLog
import app.tandem.plugins.HandledType
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.SmsConversationRequest
import app.tandem.protocol.body.SmsMessage
import app.tandem.protocol.body.SmsMessagesBody
import app.tandem.protocol.body.SmsSendRequest

/** Reads the phone's text messages. Needs READ_SMS; without it every call returns nothing. */
interface SmsStore {
    /** The newest message of each thread, newest thread first. */
    suspend fun latestPerThread(): List<SmsMessage>

    /** Messages of one thread, newest first. */
    suspend fun thread(request: SmsConversationRequest): List<SmsMessage>

    /** The thread that holds messages to or from [addresses], creating it when new. */
    suspend fun threadIdFor(addresses: List<String>): Long
}

/** Sends texts. Needs SEND_SMS. */
interface SmsSender {
    /** Returns false when the text could not be handed to the radio (no permission, no address, no SIM). */
    suspend fun send(
        addresses: List<String>,
        body: String,
        subId: Int?,
    ): Boolean
}

/** The user's choice to read and send texts from the laptop. */
interface SmsSettings {
    suspend fun enabled(): Boolean
}

/**
 * Answers the desktop's text requests: the conversation list, one conversation, and "send this text". Every
 * request is refused while the setting is off. New incoming texts are pushed by [SmsPush], not here.
 */
class SmsPlugin(
    private val context: PluginContext,
    private val store: SmsStore,
    private val sender: SmsSender,
    private val settings: SmsSettings,
    private val log: HandledLog,
    private val now: () -> Long = System::currentTimeMillis,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        when (packet.type) {
            PacketTypes.SMS_REQUEST_CONVERSATIONS -> if (settings.enabled()) pushMessages(store.latestPerThread())
            PacketTypes.SMS_REQUEST_CONVERSATION ->
                SmsConversationRequest.parse(packet.body)?.takeIf { settings.enabled() }?.let {
                    pushMessages(store.thread(it))
                }
            PacketTypes.SMS_REQUEST ->
                SmsSendRequest.parse(packet.body)?.takeIf { settings.enabled() }?.let { send(it) }
            // MMS attachments are not supported, so there is nothing to answer.
            PacketTypes.SMS_REQUEST_ATTACHMENT -> Unit
            else -> return false
        }
        return true
    }

    private suspend fun send(request: SmsSendRequest) {
        if (request.body.isBlank()) return
        if (!sender.send(request.addresses, request.body, request.subId)) return
        log.add(HandledType.Sms, request.addresses.first(), "You replied “${request.body.take(DETAIL_CHARS)}”")
        // The system does not store texts sent by an app that is not the default messaging app, so tell the
        // desktop about the message it just asked for, or it would never appear in its conversation.
        val sent =
            SmsMessage(
                id = now(),
                threadId = store.threadIdFor(request.addresses),
                addresses = request.addresses,
                body = request.body,
                date = now(),
                type = SmsMessage.TYPE_SENT,
                read = true,
                subId = request.subId,
            )
        context.send(SmsMessagesBody(listOf(sent)).toPacket())
    }

    /** Sends in batches, because a phone with years of texts would otherwise build one huge packet. */
    private suspend fun pushMessages(messages: List<SmsMessage>) {
        if (messages.isEmpty()) {
            context.send(SmsMessagesBody(emptyList()).toPacket())
            return
        }
        messages.chunked(BATCH).forEach { context.send(SmsMessagesBody(it).toPacket()) }
    }

    private companion object {
        const val BATCH = 50
        const val DETAIL_CHARS = 60
    }
}

/** Pushes a text that just arrived to every connected laptop. */
class SmsPush(
    private val peers: PeerSender,
    private val settings: SmsSettings,
    private val store: SmsStore,
    private val connectedDevices: () -> List<String>,
) {
    suspend fun onReceived(
        sender: String,
        body: String,
        dateMs: Long,
        subId: Int?,
    ) {
        if (!settings.enabled()) return
        val message =
            SmsMessage(
                id = dateMs,
                threadId = store.threadIdFor(listOf(sender)),
                addresses = listOf(sender),
                body = body,
                date = dateMs,
                type = SmsMessage.TYPE_RECEIVED,
                read = false,
                subId = subId,
            )
        val packet = SmsMessagesBody(listOf(message)).toPacket()
        for (deviceId in connectedDevices()) peers.sendPacket(deviceId, packet)
    }
}

object SmsDescriptor {
    val INCOMING =
        setOf(
            PacketTypes.SMS_REQUEST,
            PacketTypes.SMS_REQUEST_CONVERSATIONS,
            PacketTypes.SMS_REQUEST_CONVERSATION,
            PacketTypes.SMS_REQUEST_ATTACHMENT,
        )

    fun create(
        store: SmsStore,
        sender: SmsSender,
        settings: SmsSettings,
        log: HandledLog,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "sms",
            incoming = INCOMING,
            outgoing = setOf(PacketTypes.SMS_MESSAGES),
            requiredPermissions = emptyList(),
            optionalPermissions =
                listOf(
                    "android.permission.READ_SMS",
                    "android.permission.SEND_SMS",
                    "android.permission.RECEIVE_SMS",
                ),
            factory = { SmsPlugin(it, store, sender, settings, log) },
        )
}
