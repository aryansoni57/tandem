// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.data.NotifRule
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.plugins.FakeContext
import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.NotificationBody
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getString
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferControl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

private fun snap(
    key: String = "k1",
    pkg: String = "com.chat",
    icon: ByteArray? = null,
    ongoing: Boolean = false,
    summary: Boolean = false,
    secret: Boolean = false,
    fgs: Boolean = false,
) = NotificationSnapshot(
    key = key, packageName = pkg, appName = "Chat", title = "Maya", text = "hi there", ticker = "Maya: hi",
    timeMs = 1_700_000_000_000, isClearable = true, silent = false, ongoing = ongoing, foregroundService = fgs,
    groupSummary = summary, secret = secret, actionTitles = listOf("Mark read"), replyId = "rid-1",
    groupName = null, conversation = true, iconPng = icon,
)

class NotificationMapperTest {
    private val mapper = NotificationMapper("app.tandem")

    @Test
    fun syncedNotificationKeepsEverything() {
        val body = mapper.map(snap(), NotifRule.SYNC)!!
        assertEquals("hi there", body.text)
        assertEquals("rid-1", body.requestReplyId)
        assertEquals(listOf("Mark read"), body.actions)
        assertEquals("1700000000000", body.toJson().getString("time"))
    }

    @Test
    fun previewOnlyDropsTextRepliesAndActions() {
        val body = mapper.map(snap(), NotifRule.PREVIEW_ONLY)!!
        assertEquals("Maya", body.title)
        assertEquals("", body.text)
        assertNull(body.requestReplyId)
        assertTrue(body.actions.isEmpty())
    }

    @Test
    fun skipsHiddenOwnOngoingSummarySecretAndForegroundService() {
        assertNull(mapper.map(snap(), NotifRule.HIDDEN))
        assertNull(mapper.map(snap(pkg = "app.tandem"), NotifRule.SYNC))
        assertNull(mapper.map(snap(ongoing = true), NotifRule.SYNC))
        assertNull(mapper.map(snap(summary = true), NotifRule.SYNC))
        assertNull(mapper.map(snap(secret = true), NotifRule.SYNC))
        assertNull(mapper.map(snap(fgs = true), NotifRule.SYNC))
    }

    @Test
    fun iconHashIsUppercaseMd5() {
        assertEquals("D41D8CD98F00B204E9800998ECF8427E", NotificationMapper.md5Hex(ByteArray(0)))
        val body = mapper.map(snap(icon = ByteArray(0)), NotifRule.SYNC)!!
        assertEquals("D41D8CD98F00B204E9800998ECF8427E", body.payloadHash)
    }
}

class NotificationBridgeTest {
    private class Policy(
        var rule: NotifRule = NotifRule.SYNC,
        var show: Boolean = true,
        var screenOffOnly: Boolean = false,
        var screenOn: Boolean = false,
    ) : NotificationPolicy {
        val seenApps = mutableListOf<String>()

        override suspend fun ruleFor(packageName: String) = rule

        override suspend fun seen(snapshot: NotificationSnapshot) {
            seenApps += snapshot.packageName
        }

        override suspend fun showOnLaptop() = show

        override suspend fun screenOffOnly() = screenOffOnly

        override fun isScreenOn() = screenOn
    }

    private class Endpoint : PayloadEndpoint {
        var sends = 0

        override suspend fun send(
            deviceId: String,
            size: Long,
            source: InputStream,
            control: TransferControl,
            progress: PayloadProgress,
            announce: suspend (port: Int) -> Boolean,
        ): Long? {
            sends++
            return if (announce(1745)) size else null
        }

        override suspend fun receive(
            deviceId: String,
            port: Int,
            size: Long,
            sink: OutputStream,
            control: TransferControl,
            progress: PayloadProgress,
        ) = 0L
    }

    private val peers = FakePeerSender()
    private val endpoint = Endpoint()
    private val policy = Policy()
    private val bridge =
        NotificationBridge(peers, endpoint, policy, NotificationMapper("app.tandem")) { listOf("dev1", "dev2") }

    @Test
    fun postedGoesToEveryConnectedDevice() =
        runBlocking {
            bridge.onPosted(snap())
            assertEquals(listOf("dev1", "dev2"), peers.sent.map { it.first })
            assertEquals(PacketTypes.NOTIFICATION, peers.sent.first().second.type)
            assertEquals(listOf("com.chat"), policy.seenApps)
        }

    @Test
    fun switchedOffOrHiddenSendsNothing() =
        runBlocking {
            policy.show = false
            bridge.onPosted(snap())
            policy.show = true
            policy.rule = NotifRule.HIDDEN
            bridge.onPosted(snap())
            assertTrue(peers.sent.isEmpty())
        }

    @Test
    fun screenOffOnlyHoldsBackWhileScreenIsOn() =
        runBlocking {
            policy.screenOffOnly = true
            policy.screenOn = true
            bridge.onPosted(snap())
            assertTrue(peers.sent.isEmpty())
            policy.screenOn = false
            bridge.onPosted(snap())
            assertEquals(2, peers.sent.size)
        }

    @Test
    fun iconTravelsOnceThenOnlyWhenItChanges() =
        runBlocking {
            val icon = byteArrayOf(1, 2, 3)
            bridge.onPosted(snap(icon = icon))
            assertEquals(2, endpoint.sends)
            val first = peers.sent.first().second
            assertEquals(3L, first.payloadSize)
            assertEquals(1745, first.payloadPort)
            bridge.onPosted(snap(icon = icon))
            assertEquals(2, endpoint.sends)
            bridge.onPosted(snap(icon = byteArrayOf(9)))
            assertEquals(4, endpoint.sends)
        }

    @Test
    fun removedSendsCancelAndForgetsIcon() =
        runBlocking {
            bridge.onRemoved("k1")
            val packet = peers.sent.first().second
            assertEquals("k1", packet.body.getString("id"))
            assertEquals(true, packet.body.getBoolean("isCancel"))
        }

    @Test
    fun sendAllResendsEachNotificationToOneDevice() =
        runBlocking {
            bridge.sendAll("dev1", listOf(snap("a"), snap("b"), snap("c", ongoing = true)))
            assertEquals(listOf("dev1", "dev1"), peers.sent.map { it.first })
        }
}

class NotificationsPluginTest {
    private class Source : NotificationSource {
        val cancelled = mutableListOf<String>()
        val replies = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<Pair<String, String>>()

        override fun current() = listOf(snap("a"))

        override fun cancel(key: String) {
            cancelled += key
        }

        override fun reply(replyId: String, message: String): Boolean {
            replies += replyId to message
            return true
        }

        override fun invokeAction(key: String, title: String): Boolean {
            actions += key to title
            return true
        }
    }

    private val fake = FakeContext(setOf(PacketTypes.NOTIFICATION))
    private val peers = FakePeerSender()
    private val source = Source()
    private val bridge =
        NotificationBridge(peers, object : PayloadEndpoint {
            override suspend fun send(
                deviceId: String, size: Long, source: InputStream, control: TransferControl,
                progress: PayloadProgress, announce: suspend (port: Int) -> Boolean,
            ): Long? = null

            override suspend fun receive(
                deviceId: String, port: Int, size: Long, sink: OutputStream, control: TransferControl,
                progress: PayloadProgress,
            ) = 0L
        }, object : NotificationPolicy {
            override suspend fun ruleFor(packageName: String) = NotifRule.SYNC

            override suspend fun seen(snapshot: NotificationSnapshot) = Unit

            override suspend fun showOnLaptop() = true

            override suspend fun screenOffOnly() = false

            override fun isScreenOn() = false
        }, NotificationMapper("app.tandem")) { emptyList() }
    private val plugin = NotificationsPlugin(fake.context, bridge, source)

    private fun packet(
        type: String,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): app.tandem.protocol.NetworkPacket =
        app.tandem.protocol.NetworkPacket(1, type, buildJsonObject(build))

    @Test
    fun requestTrueSendsCurrentList() =
        runBlocking {
            assertTrue(plugin.onPacket(packet(PacketTypes.NOTIFICATION_REQUEST) { put("request", true) }))
            assertEquals(1, peers.sent.size)
        }

    @Test
    fun cancelReplyAndActionReachTheSource() =
        runBlocking {
            plugin.onPacket(packet(PacketTypes.NOTIFICATION_REQUEST) { put("cancel", "k9") })
            plugin.onPacket(
                packet(PacketTypes.NOTIFICATION_REPLY) {
                    put("requestReplyId", "r1")
                    put("message", "ok")
                },
            )
            plugin.onPacket(packet(PacketTypes.NOTIFICATION_ACTION) { put("key", "k2"); put("action", "Mark read") })
            assertEquals(listOf("k9"), source.cancelled)
            assertEquals(listOf("r1" to "ok"), source.replies)
            assertEquals(listOf("k2" to "Mark read"), source.actions)
        }

    @Test
    fun malformedAndUnknownPackets() =
        runBlocking {
            assertTrue(plugin.onPacket(packet(PacketTypes.NOTIFICATION_REPLY) { put("message", "no id") }))
            assertTrue(source.replies.isEmpty())
            assertFalse(plugin.onPacket(packet(PacketTypes.PING) {}))
        }
}
