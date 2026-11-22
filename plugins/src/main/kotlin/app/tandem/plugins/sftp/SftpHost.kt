// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sftp

import app.tandem.protocol.body.SftpReply
import app.tandem.sftp.SftpServer
import app.tandem.sftp.SharedFolders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyPair

/** What the phone knows about its storage. */
interface StorageAccess {
    /** True when the app may read and write the shared storage (Android 11 "all files access"). */
    fun hasAccess(): Boolean

    /** The shared storage directory the server is rooted at. */
    fun root(): Path

    /** The names of the folders directly inside [root] that exist. */
    fun topFolders(): List<String>

    /** Our address on the network that reaches [peer], or null when there is none. */
    fun localAddressToward(peer: InetAddress): String?
}

/** The user's sharing choices. */
interface SftpSettings {
    suspend fun enabled(): Boolean

    suspend fun folders(): Set<String>
}

/** The SSH host key, made once and kept. */
interface HostKeyStore {
    fun loadOrCreate(): KeyPair
}

/**
 * Opens the phone's storage to the laptop on request. The server stays up while the laptop is connected, and
 * every request makes a new password. Only folders the user chose, and that exist, are shared.
 */
class SftpHost(
    private val access: StorageAccess,
    private val settings: SftpSettings,
    private val keys: HostKeyStore,
    private val newServer: (KeyPair, Path, SharedFolders) -> SftpServer = { key, root, folders ->
        SftpServer(key, root, folders)
    },
) {
    private var server: SftpServer? = null
    private val folders = SharedFolders()

    private val lock = Mutex()

    /** Starts the server if needed and returns how to reach it, or why it cannot be reached. Blocks on disk. */
    suspend fun start(peer: InetAddress?): SftpReply =
        lock.withLock {
            if (!settings.enabled()) return@withLock SftpReply.Failed(SHARING_OFF)
            if (!access.hasAccess()) return@withLock SftpReply.Failed(NO_ACCESS)
            val chosen = settings.folders()
            val shared = access.topFolders().filter { it in chosen }.sorted()
            if (shared.isEmpty()) return@withLock SftpReply.Failed(NO_FOLDERS)
            val ip = peer?.let(access::localAddressToward) ?: return@withLock SftpReply.Failed(NO_ADDRESS)
            folders.names = shared.toSet()
            val running = server ?: newServer(keys.loadOrCreate(), access.root(), folders).also { server = it }
            val port = running.start() ?: return@withLock SftpReply.Failed(NO_PORT)
            SftpReply.Ready(
                ip = ip,
                port = port,
                user = SftpServer.USER,
                password = running.newPassword(),
                paths = shared.map { "/$it" },
                names = shared,
            )
        }

    fun stop() {
        server?.stop()
    }

    companion object {
        const val SHARING_OFF = "Sharing is off. Turn on \"Let laptop browse files\" in Tandem."
        const val NO_ACCESS = "Tandem needs \"All files access\". Allow it in the phone's settings for Tandem."
        const val NO_FOLDERS = "No folders are shared. Add one in Tandem, under Share."
        const val NO_ADDRESS = "Could not work out the phone's address on this network."
        const val NO_PORT = "No free port to share files on."
        const val START_FAILED = "File sharing could not start on this phone."
    }
}
