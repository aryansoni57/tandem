// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sftp

import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * A small SFTP server for the laptop to mount the phone's storage. Password login only, with a new random
 * password each time it is started. The laptop reaches only [folders] of [root]. Nothing else is offered:
 * no shell, no commands, no key login.
 */
class SftpServer(
    private val hostKey: KeyPair,
    private val root: Path,
    val folders: SharedFolders,
    private val ports: IntRange = DEFAULT_PORTS,
    private val random: SecureRandom = SecureRandom(),
) {
    private val passwordHash = AtomicReference(ByteArray(0))
    private var server: SshServer? = null

    val isRunning: Boolean get() = server?.isStarted == true

    /** The port the server listens on, or -1 when it is not running. */
    val port: Int get() = server?.takeIf { it.isStarted }?.port ?: -1

    /**
     * Starts on the first free port of the range and returns it, or null when none is free. A server that is
     * already running keeps its port. Every start makes a new password; read it with [newPassword].
     */
    @Synchronized
    fun start(): Int? {
        if (isRunning) return port
        for (candidate in ports) {
            val attempt = build()
            attempt.port = candidate
            try {
                attempt.start()
                server = attempt
                return candidate
            } catch (_: IOException) {
                runCatching { attempt.stop(true) }
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop(true) } }
        server = null
    }

    /** Makes a new random password, replacing the old one, and returns it. Sessions already open stay open. */
    fun newPassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES).also(random::nextBytes)
        // The modulo bias over 62 symbols is under one percent per character, which a 28 character password absorbs.
        val password =
            bytes.joinToString("") { PASSWORD_ALPHABET[(it.toInt() and 0xff) % PASSWORD_ALPHABET.length].toString() }
        passwordHash.set(sha256(password))
        return password
    }

    private fun build(): SshServer =
        SshServer.setUpDefaultServer().apply {
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = Authenticator()
            publickeyAuthenticator = null
            // Files only: no shell and no remote commands.
            shellFactory = null
            commandFactory = null
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory =
                object : FileSystemFactory {
                    override fun getUserHomeDir(session: SessionContext?): Path = root

                    override fun createFileSystem(session: SessionContext?): FileSystem =
                        SharedFolderProvider(folders).newFileSystem(root, emptyMap<String, Any>())
                }
        }

    private inner class Authenticator : PasswordAuthenticator {
        override fun authenticate(
            username: String?,
            password: String?,
            session: ServerSession?,
        ): Boolean {
            if (username != USER || password == null) return false
            // Compare hashes in constant time, so the check does not leak how much of the password matched.
            return MessageDigest.isEqual(passwordHash.get(), sha256(password))
        }
    }

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))

    companion object {
        const val USER = "kdeconnect"

        init {
            // MINA registers its own BouncyCastle when it finds one. On Android that clashes with the platform's
            // stripped-down "BC", and the elliptic curve tables fail to build, so the server cannot even be
            // created. The platform's own providers cover RSA, which is all this server needs.
            System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "")
            // Android has no user.home; MINA asks for one, so give it the root.
            PathUtils.setUserHomeFolderResolver { Path.of("/") }
        }

        /** The range the desktop's KDE Connect expects for SFTP. */
        val DEFAULT_PORTS = 1739..1764

        private const val PASSWORD_BYTES = 28
        private const val PASSWORD_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
