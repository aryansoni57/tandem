// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sftp

import android.content.Context
import android.os.Build
import android.os.Environment
import app.tandem.core.data.SettingsStore
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.sftp.HostKeys
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

/** Shared storage. Sharing needs Android 11 or later, where "all files access" is a setting the user grants. */
@Singleton
class AndroidStorageAccess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : StorageAccess {
        override fun hasAccess(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

        override fun root(): Path = Environment.getExternalStorageDirectory().toPath()

        override fun topFolders(): List<String> =
            Environment.getExternalStorageDirectory()
                .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                ?.map { it.name }
                .orEmpty()

        /** Connecting a datagram socket sends nothing, but makes the system pick the address it would send from. */
        override fun localAddressToward(peer: InetAddress): String? =
            runCatching { DatagramSocket().use { it.connect(peer, PROBE_PORT); it.localAddress.hostAddress } }
                .getOrNull()
                ?.takeIf { it != "0.0.0.0" }

        /** Used and total bytes of the shared storage. */
        fun usage(): Pair<Long, Long> {
            val dir = Environment.getExternalStorageDirectory()
            return (dir.totalSpace - dir.freeSpace) to dir.totalSpace
        }

        private companion object {
            const val PROBE_PORT = 9
        }
    }

@Singleton
class StoreSftpSettings
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) : SftpSettings {
        override suspend fun enabled(): Boolean = settings.sftpEnabled.first()

        override suspend fun folders(): Set<String> = settings.sftpFolders.first()
    }

/** Keeps the host key in the app's private files, so the laptop sees the same key every time. */
@Singleton
class FileHostKeyStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : HostKeyStore {
        @Synchronized
        override fun loadOrCreate(): KeyPair {
            val priv = File(context.filesDir, "sftp_host.pk8")
            val pub = File(context.filesDir, "sftp_host.x509")
            if (priv.exists() && pub.exists()) {
                runCatching { return HostKeys.decode(priv.readBytes(), pub.readBytes()) }
            }
            return HostKeys.generate().also {
                priv.writeBytes(HostKeys.encodePrivate(it))
                pub.writeBytes(HostKeys.encodePublic(it))
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SftpBindings {
    @Binds
    abstract fun storage(impl: AndroidStorageAccess): StorageAccess

    @Binds
    abstract fun settings(impl: StoreSftpSettings): SftpSettings

    @Binds
    abstract fun keys(impl: FileHostKeyStore): HostKeyStore

    companion object {
        @Provides
        @Singleton
        fun host(
            storage: StorageAccess,
            settings: SftpSettings,
            keys: HostKeyStore,
        ): SftpHost = SftpHost(storage, settings, keys)

        @Provides
        @IntoSet
        fun descriptor(
            host: SftpHost,
            // Lazy: PeerSender is the DeviceManager, which builds the plugin registry.
            peers: dagger.Lazy<PeerSender>,
        ): PluginDescriptor = SftpDescriptor.create(host) { id -> peers.get().livePeer(id)?.address }
    }
}
