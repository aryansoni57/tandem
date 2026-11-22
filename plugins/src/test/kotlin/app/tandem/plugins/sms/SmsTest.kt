// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sms

import app.tandem.plugins.FakeContext
import app.tandem.plugins.FakePeerSender
import app.tandem.plugins.HandledLog
import app.tandem.plugins.HandledType
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.SMS_PACKET_VERSION
import app.tandem.protocol.body.SmsConversationRequest
import app.tandem.protocol.body.SmsMessage
import app.tandem.protocol.body.SmsMessagesBody
import app.tandem.protocol.body.SmsSendRequest
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getLong
import app.tandem.protocol.getString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(
    id: Long,
    thread: Long = 7,
    body: String = "hi",
    to: List<String> = listOf("+91"),
    type: Int = SmsMessage.TYPE_RECEIVED,
) = SmsMessage(id, thread, to, body, date = 1_700_000_000_000L + id, type = type, read = false)

class SmsBodyTest {
    @Test
    fun messageUsesTheDesktopFieldNamesAndStringDates() {
        val json = msg(5, to = listOf("+91", "+92")).toJson()
        assertEquals(SmsMessage.EVENT_TEXT or SmsMessage.EVENT_MULTI_TARGET, json.getInt("event"))
        assertEquals("hi", json.getString("body"))
        assertEquals("1700000000005", json.getString("date"))
        assertEquals("1", json.getString("type"))
        assertEquals(7L, json.getLong("thread_id"))
        assertEquals(5L, json.getLong("_id"))
        assertEquals(false, json.getBoolean("read"))
        val addresses = json["addresses"] as JsonArray
        assertEquals(2, addresses.size)
        assertEquals("+91", (addresses[0] as JsonObject).getString("address"))
        assertNull(json.getInt("sub_id"))
    }

    @Test
    fun singleTargetHasOnlyTheTextFlag() = assertEquals(1, msg(1).event)

    @Test
    fun batchCarriesTheVersion() {
        val packet = SmsMessagesBody(listOf(msg(1), msg(2))).toPacket()
        assertEquals(PacketTypes.SMS_MESSAGES, packet.type)
        assertEquals(SMS_PACKET_VERSION, packet.body.getInt("version"))
        assertEquals(2, (packet.body["messages"] as JsonArray).size)
    }

    @Test
    fun conversationRequestNeedsAThreadAndKeepsTheOptionalRange() {
        val full = buildJsonObject {
            put("threadID", 7L)
            put("rangeStartTimestamp", 1000L)
            put("numberToRequest", 20)
        }
        assertEquals(SmsConversationRequest(7L, 1000L, 20), SmsConversationRequest.parse(full))
        assertNull(SmsConversationRequest.parse(buildJsonObject { put("numberToRequest", 5) }))
        assertNull(SmsConversationRequest.parse(buildJsonObject { put("threadID", 3L) })!!.limit)
    }

    @Test
    fun sendRequestReadsAddressesOrTheOldPhoneNumberField() {
        val modern = buildJsonObject {
            put("addresses", buildJsonArray { add(buildJsonObject { put("address", "+91") }) })
            put("messageBody", "hello")
            put("subID", 3)
        }
        val parsed = SmsSendRequest.parse(modern)!!
        assertEquals(listOf("+91"), parsed.addresses)
        assertEquals("hello", parsed.body)
        assertEquals(3, parsed.subId)
        assertFalse(parsed.hasAttachments)
        val legacy = SmsSendRequest.parse(buildJsonObject { put("phoneNumber", "+92"); put("messageBody", "x") })!!
        assertEquals(listOf("+92"), legacy.addresses)
        assertNull(SmsSendRequest.parse(buildJsonObject { put("messageBody", "no one") }))
    }

    @Test
    fun attachmentsAreFlagged() {
        val body = buildJsonObject {
            put("phoneNumber", "+91")
            put("attachments", buildJsonArray { add(buildJsonObject { put("fileName", "a.jpg") }) })
        }
        assertTrue(SmsSendRequest.parse(body)!!.hasAttachments)
    }
}

class SmsPluginTest {
    private class Store(
        val latest: List<SmsMessage> = emptyList(),
        val byThread: List<SmsMessage> = emptyList(),
    ) : SmsStore {
        var asked: SmsConversationRequest? = null

        override suspend fun latestPerThread() = latest

        override suspend fun thread(request: SmsConversationRequest): List<SmsMessage> {
            asked = request
            return byThread
        }

        override suspend fun threadIdFor(addresses: List<String>) = 42L
    }

    private class Sender(var ok: Boolean = true) : SmsSender {
        val sent = mutableListOf<Triple<List<String>, String, Int?>>()

        override suspend fun send(
            addresses: List<String>,
            body: String,
            subId: Int?,
        ): Boolean {
            sent += Triple(addresses, body, subId)
            return ok
        }
    }

    private class Settings(var on: Boolean = true) : SmsSettings {
        override suspend fun enabled() = on
    }

    private val fake = FakeContext(setOf(PacketTypes.SMS_MESSAGES))
    private val log = HandledLog()
    private val settings = Settings()
    private val sender = Sender()

    private fun plugin(store: SmsStore) = SmsPlugin(fake.context, store, sender, settings, log) { 5_000L }

    private fun packet(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        NetworkPacket(1, type, buildJsonObject(build))

    @Test
    fun conversationListIsPushedInBatches() =
        runBlocking {
            val many = (1L..120L).map { msg(it, thread = it) }
            plugin(Store(latest = many)).onPacket(packet(PacketTypes.SMS_REQUEST_CONVERSATIONS))
            assertEquals(listOf(50, 50, 20), fake.sent.map { (it.body["messages"] as JsonArray).size })
        }

    @Test
    fun emptyPhoneStillAnswers() =
        runBlocking {
            plugin(Store()).onPacket(packet(PacketTypes.SMS_REQUEST_CONVERSATIONS))
            assertEquals(0, (fake.sent.single().body["messages"] as JsonArray).size)
        }

    @Test
    fun oneConversationPassesItsRangeToTheStore() =
        runBlocking {
            val store = Store(byThread = listOf(msg(1), msg(2)))
            plugin(store).onPacket(
                packet(PacketTypes.SMS_REQUEST_CONVERSATION) {
                    put("threadID", 7L)
                    put("numberToRequest", 2)
                },
            )
            assertEquals(SmsConversationRequest(7L, null, 2), store.asked)
            assertEquals(2, (fake.sent.single().body["messages"] as JsonArray).size)
        }

    @Test
    fun sendRequestSendsRecordsAndEchoesTheSentMessage() =
        runBlocking {
            val request = packet(PacketTypes.SMS_REQUEST) {
                put("addresses", buildJsonArray { add(buildJsonObject { put("address", "+91") }) })
                put("messageBody", "On my way")
            }
            plugin(Store()).onPacket(request)
            assertEquals(listOf(Triple(listOf("+91"), "On my way", null)), sender.sent)
            assertEquals(HandledType.Sms, log.entries.value.single().type)
            val echoed = (fake.sent.single().body["messages"] as JsonArray).single() as JsonObject
            assertEquals(SmsMessage.TYPE_SENT.toString(), echoed.getString("type"))
            assertEquals(42L, echoed.getLong("thread_id"))
        }

    @Test
    fun failedSendIsNeitherLoggedNorEchoed() =
        runBlocking {
            sender.ok = false
            val request = packet(PacketTypes.SMS_REQUEST) {
                put("phoneNumber", "+91")
                put("messageBody", "x")
            }
            plugin(Store()).onPacket(request)
            assertTrue(log.entries.value.isEmpty())
            assertTrue(fake.sent.isEmpty())
        }

    @Test
    fun everythingIsRefusedWhileTheSettingIsOff() =
        runBlocking {
            settings.on = false
            val p = plugin(Store(latest = listOf(msg(1))))
            p.onPacket(packet(PacketTypes.SMS_REQUEST_CONVERSATIONS))
            p.onPacket(packet(PacketTypes.SMS_REQUEST) { put("phoneNumber", "+91"); put("messageBody", "x") })
            assertTrue(fake.sent.isEmpty())
            assertTrue(sender.sent.isEmpty())
        }

    @Test
    fun attachmentRequestsAndBlankTextsAreIgnoredAndOtherTypesNotHandled() =
        runBlocking {
            val p = plugin(Store())
            assertTrue(p.onPacket(packet(PacketTypes.SMS_REQUEST_ATTACHMENT)))
            p.onPacket(packet(PacketTypes.SMS_REQUEST) { put("phoneNumber", "+91"); put("messageBody", "  ") })
            assertTrue(sender.sent.isEmpty())
            assertFalse(p.onPacket(packet(PacketTypes.PING)))
        }
}

class SmsPushTest {
    private val peers = FakePeerSender()
    private var enabled = true
    private val store =
        object : SmsStore {
            override suspend fun latestPerThread() = emptyList<SmsMessage>()

            override suspend fun thread(request: SmsConversationRequest) = emptyList<SmsMessage>()

            override suspend fun threadIdFor(addresses: List<String>) = 9L
        }
    private val push = SmsPush(peers, object : SmsSettings { override suspend fun enabled() = enabled }, store) {
        listOf("dev1", "dev2")
    }

    @Test
    fun anIncomingTextGoesToEveryConnectedLaptop() =
        runBlocking {
            push.onReceived("+91", "Are you there?", 1_700_000_000_000L, null)
            assertEquals(listOf("dev1", "dev2"), peers.sent.map { it.first })
            val message = (peers.sent.first().second.body["messages"] as JsonArray).single() as JsonObject
            assertEquals("Are you there?", message.getString("body"))
            assertEquals(9L, message.getLong("thread_id"))
            assertEquals(false, message.getBoolean("read"))
        }

    @Test
    fun nothingIsPushedWhileTheSettingIsOff() =
        runBlocking {
            enabled = false
            push.onReceived("+91", "x", 1L, null)
            assertTrue(peers.sent.isEmpty())
        }
}

class HandledLogTest {
    @Test
    fun keepsTheNewestTwentyFirst() {
        val log = HandledLog()
        repeat(25) { log.add(HandledType.Sms, "n$it", "d", nowMs = it.toLong()) }
        assertEquals(20, log.entries.value.size)
        assertEquals("n24", log.entries.value.first().title)
        assertEquals("n5", log.entries.value.last().title)
    }
}
