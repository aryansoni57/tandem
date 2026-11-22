// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sftp

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Starts the real server on a temporary folder and talks to it with a real SFTP client. */
class SftpServerTest {
    @TempDir lateinit var root: Path

    private val folders = SharedFolders(listOf("DCIM"))
    private lateinit var server: SftpServer
    private lateinit var client: SshClient
    private var port = -1

    @BeforeEach
    fun setUp() {
        Files.createDirectories(root.resolve("DCIM/2024"))
        Files.writeString(root.resolve("DCIM/a.txt"), "photo")
        Files.createDirectories(root.resolve("Secret"))
        Files.writeString(root.resolve("Secret/b.txt"), "private")
        Files.writeString(root.resolve("top.txt"), "at the root")
        // A port range of one hundred keeps parallel test runs from colliding with a real server.
        server = SftpServer(HostKeys.generate(), root, folders, 21739..21839)
        port = server.start()!!
        client = SshClient.setUpDefaultClient().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        client.stop()
        server.stop()
    }

    private fun session(
        password: String,
        user: String = SftpServer.USER,
    ): ClientSession =
        client.connect(user, "127.0.0.1", port).verify(10, TimeUnit.SECONDS).session.apply {
            addPasswordIdentity(password)
            auth().verify(10, TimeUnit.SECONDS)
        }

    private fun <T> withSftp(
        password: String = server.newPassword(),
        block: (SftpClient) -> T,
    ): T =
        session(password).use { s -> SftpClientFactory.instance().createSftpClient(s).use(block) }

    @Test
    fun theRightPasswordLogsInAndAWrongOneDoesNot() {
        val password = server.newPassword()
        session(password).use { assertTrue(it.isAuthenticated) }
        assertThrows(IOException::class.java) { session("wrong") }
        assertThrows(IOException::class.java) { session(password, user = "root") }
    }

    @Test
    fun aNewPasswordReplacesTheOldOne() {
        val first = server.newPassword()
        val second = server.newPassword()
        assertThrows(IOException::class.java) { session(first) }
        session(second).use { assertTrue(it.isAuthenticated) }
    }

    @Test
    fun theRootListsOnlyTheSharedFolders() {
        val names = withSftp { it.readDir("/").map { e -> e.filename }.filter { n -> n != "." && n != ".." } }
        assertEquals(listOf("DCIM"), names)
    }

    @Test
    fun filesInsideASharedFolderCanBeReadWrittenAndDeleted() {
        withSftp { sftp ->
            assertEquals("photo", sftp.read("/DCIM/a.txt").use { String(it.readAllBytes()) })
            sftp.write("/DCIM/new.txt").use { it.write("hello".toByteArray()) }
            assertEquals("hello", Files.readString(root.resolve("DCIM/new.txt")))
            sftp.mkdir("/DCIM/made")
            assertTrue(Files.isDirectory(root.resolve("DCIM/made")))
            sftp.remove("/DCIM/new.txt")
            assertFalse(Files.exists(root.resolve("DCIM/new.txt")))
            assertTrue(sftp.readDir("/DCIM").any { it.filename == "2024" })
        }
    }

    @Test
    fun foldersThatAreNotSharedCannotBeReachedByAnyRoute() {
        withSftp { sftp ->
            // readDir is lazy: the open happens on the first step through the listing.
            assertThrows(java.io.UncheckedIOException::class.java) { sftp.readDir("/Secret").toList() }
            assertThrows(IOException::class.java) { sftp.read("/Secret/b.txt").use { it.readAllBytes() } }
            // Climbing out of a shared folder is judged by where the path ends up.
            assertThrows(IOException::class.java) { sftp.read("/DCIM/../Secret/b.txt").use { it.readAllBytes() } }
            assertThrows(IOException::class.java) { sftp.read("/top.txt").use { it.readAllBytes() } }
            assertThrows(IOException::class.java) { sftp.write("/Secret/x.txt").use { it.write(1) } }
            assertThrows(IOException::class.java) { sftp.remove("/Secret/b.txt") }
            // MINA answers a refused stat with empty attributes, the same as for a name that does not exist. So
            // nothing about a folder that is not shared (type, size, times, permissions) leaks.
            val nothing = sftp.stat("/NoSuchThing")
            for (hidden in listOf("/Secret", "/Secret/b.txt", "/top.txt", "/DCIM/../Secret")) {
                val seen = sftp.stat(hidden)
                assertEquals(nothing.permissions, seen.permissions, hidden)
                assertEquals(nothing.size, seen.size, hidden)
                assertFalse(seen.isDirectory, hidden)
                assertNull(seen.modifyTime, hidden)
            }
            // A shared folder, by contrast, shows its real attributes.
            assertTrue(sftp.stat("/DCIM").isDirectory)
            assertTrue(Files.exists(root.resolve("Secret/b.txt")))
        }
    }

    @Test
    fun theRootItselfCannotBeChanged() {
        withSftp { sftp ->
            assertThrows(IOException::class.java) { sftp.mkdir("/NewTop") }
            assertThrows(IOException::class.java) { sftp.write("/top2.txt").use { it.write(1) } }
            assertFalse(Files.exists(root.resolve("NewTop")))
        }
    }

    @Test
    fun linksAreRefused() {
        withSftp { sftp ->
            assertThrows(IOException::class.java) { sftp.symLink("/DCIM/link", "/Secret") }
        }
    }

    @Test
    fun changingTheSharedFoldersTakesEffectForNewSessions() {
        folders.names = setOf("Secret")
        val names = withSftp { it.readDir("/").map { e -> e.filename }.filter { n -> n != "." && n != ".." } }
        assertEquals(listOf("Secret"), names)
    }

    @Test
    fun serverStopsAndReportsNoPort() {
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)
        assertEquals(-1, server.port)
    }

    @Test
    fun startingTwiceKeepsThePortAndABusyRangeFails() {
        assertEquals(port, server.start())
        val crowded = SftpServer(HostKeys.generate(), root, folders, port..port)
        assertNull(crowded.start())
    }
}

class SharedFoldersTest {
    private val folders = SharedFolders(listOf("DCIM", "Download"))

    @Test
    fun rootAndFoldersAreToldApart() {
        assertTrue(folders.isRoot(Path.of("/")))
        assertFalse(folders.isInside(Path.of("/")))
        assertTrue(folders.isInside(Path.of("/DCIM")))
        assertTrue(folders.isInside(Path.of("/Download/a/b.txt")))
        assertFalse(folders.isInside(Path.of("/Music")))
    }

    @Test
    fun dotDotCannotClimbOut() {
        assertFalse(folders.isInside(Path.of("/DCIM/../Music/a.mp3")))
        assertTrue(folders.isInside(Path.of("/Music/../DCIM/a.jpg")))
        assertTrue(folders.isRoot(Path.of("/DCIM/..")))
    }

    @Test
    fun hostKeysSurviveBeingSavedAndLoaded() {
        val pair = HostKeys.generate()
        val loaded = HostKeys.decode(HostKeys.encodePrivate(pair), HostKeys.encodePublic(pair))
        assertEquals(pair.public, loaded.public)
        assertNotNull(loaded.private)
    }
}
