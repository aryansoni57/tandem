// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sftp

import app.tandem.plugins.FakeContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.SftpReply
import app.tandem.protocol.body.SftpRequest
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getString
import app.tandem.sftp.HostKeys
import app.tandem.sftp.SftpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair

class SftpBodyTest {
    @Test
    fun startBrowsingIsReadFromTheRequest() {
        assertTrue(SftpRequest.isStartBrowsing(buildJsonObject { put("startBrowsing", true) }))
        assertFalse(SftpRequest.isStartBrowsing(buildJsonObject { put("startBrowsing", false) }))
        assertFalse(SftpRequest.isStartBrowsing(buildJsonObject {}))
    }

    @Test
    fun readyReplyUsesTheDesktopFieldNames() {
        val reply =
            SftpReply.Ready(
                "192.168.1.5", 1739, "kdeconnect", "pw",
                listOf("/DCIM", "/Download"), listOf("DCIM", "Download"),
            )
        val json = reply.toJson()
        assertEquals("192.168.1.5", json.getString("ip"))
        assertEquals(1739, json.getInt("port"))
        assertEquals("kdeconnect", json.getString("user"))
        assertEquals("pw", json.getString("password"))
        // With several folders the single path is the root; the list carries the real ones.
        assertEquals("/", json.getString("path"))
        assertEquals(listOf("/DCIM", "/Download"), (json["multiPaths"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("DCIM", "Download"), (json["pathNames"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun oneFolderIsAlsoGivenAsTheSinglePath() {
        val json = SftpReply.Ready("10.0.0.2", 1740, "u", "p", listOf("/DCIM"), listOf("DCIM")).toJson()
        assertEquals("/DCIM", json.getString("path"))
    }

    @Test
    fun failedReplyCarriesOnlyTheMessage() {
        val packet = SftpReply.Failed("nope").toPacket()
        assertEquals(PacketTypes.SFTP, packet.type)
        assertEquals("nope", packet.body.getString("errorMessage"))
        assertEquals(null, packet.body.getBoolean("password"))
    }
}

class SftpHostTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Access(
        val rootDir: Path,
        var allowed: Boolean = true,
        var ip: String? = "192.168.1.5",
    ) : StorageAccess {
        override fun hasAccess() = allowed

        override fun root() = rootDir

        override fun topFolders() =
            Files.list(rootDir).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() }

        override fun localAddressToward(peer: InetAddress) = ip
    }

    private class Settings(var on: Boolean = true, var chosen: Set<String> = setOf("DCIM", "Gone")) : SftpSettings {
        override suspend fun enabled() = on

        override suspend fun folders() = chosen
    }

    private val key: KeyPair = HostKeys.generate()
    private val keys = object : HostKeyStore { override fun loadOrCreate() = key }
    private lateinit var access: Access
    private val settings = Settings()
    private lateinit var host: SftpHost
    private val peer: InetAddress = InetAddress.getByName("192.168.1.9")

    private fun setUp() {
        val root = tmp.newFolder("storage").toPath()
        Files.createDirectories(root.resolve("DCIM"))
        Files.createDirectories(root.resolve("Secret"))
        access = Access(root)
        host = SftpHost(access, settings, keys) { k, r, f -> SftpServer(k, r, f, 22739..22839) }
    }

    @Test
    fun sharingOffOrNoAccessOrNoFoldersOrNoAddressEachSayWhy() =
        runBlocking {
            setUp()
            settings.on = false
            assertEquals(SftpReply.Failed(SftpHost.SHARING_OFF), host.start(peer))
            settings.on = true
            access.allowed = false
            assertEquals(SftpReply.Failed(SftpHost.NO_ACCESS), host.start(peer))
            access.allowed = true
            settings.chosen = setOf("Missing")
            assertEquals(SftpReply.Failed(SftpHost.NO_FOLDERS), host.start(peer))
            settings.chosen = setOf("DCIM")
            access.ip = null
            assertEquals(SftpReply.Failed(SftpHost.NO_ADDRESS), host.start(peer))
            assertEquals(SftpReply.Failed(SftpHost.NO_ADDRESS), host.start(null))
            host.stop()
        }

    @Test
    fun readyReplySharesOnlyChosenFoldersThatExistAndStartsARealServer() =
        runBlocking {
            setUp()
            val reply = host.start(peer) as SftpReply.Ready
            assertEquals("192.168.1.5", reply.ip)
            assertEquals(listOf("/DCIM"), reply.paths)
            assertEquals(listOf("DCIM"), reply.names)
            assertEquals(SftpServer.USER, reply.user)
            assertTrue(reply.port in 22739..22839)
            host.stop()
        }

    @Test
    fun everyRequestMakesANewPasswordOnTheSamePort() =
        runBlocking {
            setUp()
            val first = host.start(peer) as SftpReply.Ready
            val second = host.start(peer) as SftpReply.Ready
            assertEquals(first.port, second.port)
            assertNotEquals(first.password, second.password)
            host.stop()
        }
}

class SftpPluginTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeContext(setOf(PacketTypes.SFTP))
    private val settings = object : SftpSettings {
        override suspend fun enabled() = false

        override suspend fun folders() = emptySet<String>()
    }
    private val access = object : StorageAccess {
        override fun hasAccess() = true

        override fun root(): Path = tmp.root.toPath()

        override fun topFolders() = emptyList<String>()

        override fun localAddressToward(peer: InetAddress) = "1.2.3.4"
    }
    private val keys =
        object : HostKeyStore {
            override fun loadOrCreate() = HostKeys.generate()
        }
    private val host = SftpHost(access, settings, keys)
    private val plugin = SftpPlugin(fake.context, host) { InetAddress.getByName("192.168.1.9") }

    @Test
    fun startBrowsingGetsAnAnswerEvenWhenSharingIsOff() =
        runBlocking {
            val request = NetworkPacket(1, PacketTypes.SFTP_REQUEST, buildJsonObject { put("startBrowsing", true) })
            assertTrue(plugin.onPacket(request))
            repeat(50) { if (fake.sent.isEmpty()) delay(20) }
            assertEquals(SftpHost.SHARING_OFF, fake.sent.single().body.getString("errorMessage"))
        }

    @Test
    fun aServerThatCannotStartIsAnsweredWithAnErrorNotACrash() =
        runBlocking {
            val on = object : SftpSettings {
                override suspend fun enabled() = true

                override suspend fun folders() = setOf("DCIM")
            }
            val dir = tmp.newFolder("storage").toPath().also { Files.createDirectories(it.resolve("DCIM")) }
            val storage = object : StorageAccess {
                override fun hasAccess() = true

                override fun root(): Path = dir

                override fun topFolders() = listOf("DCIM")

                override fun localAddressToward(peer: InetAddress) = "1.2.3.4"
            }
            // Throwing an Error, as a library that fails to load does.
            val broken = SftpHost(storage, on, keys) { _, _, _ -> throw ExceptionInInitializerError("no curves") }
            val p = SftpPlugin(fake.context, broken) { InetAddress.getByName("192.168.1.9") }
            p.onPacket(NetworkPacket(1, PacketTypes.SFTP_REQUEST, buildJsonObject { put("startBrowsing", true) }))
            repeat(50) { if (fake.sent.isEmpty()) delay(20) }
            assertEquals(SftpHost.START_FAILED, fake.sent.single().body.getString("errorMessage"))
        }

    @Test
    fun otherTypesAreNotHandledAndOtherRequestsAreIgnored() =
        runBlocking {
            assertFalse(plugin.onPacket(NetworkPacket(1, PacketTypes.PING, buildJsonObject {})))
            val other = NetworkPacket(1, PacketTypes.SFTP_REQUEST, buildJsonObject { put("x", JsonPrimitive(1)) })
            assertTrue(plugin.onPacket(other))
            delay(100)
            assertTrue(fake.sent.isEmpty())
        }
}
