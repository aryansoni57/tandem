// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Share plugin: receives links, text and files, and sends them (see [ShareSender]). */
object ShareDescriptor {
    val TYPES: Set<String> = setOf(PacketTypes.SHARE_REQUEST, PacketTypes.SHARE_REQUEST_UPDATE)

    fun create(receiver: ShareReceiver): PluginDescriptor =
        PluginDescriptor(
            id = "share",
            incoming = TYPES,
            outgoing = TYPES,
            requiredPermissions = emptyList(),
            optionalPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
            factory = { ShareFilesPlugin(it, receiver) },
        )
}

/** Hands share packets to [ShareReceiver] with the device's scope, so transfers outlive the packet. */
class ShareFilesPlugin(
    private val context: PluginContext,
    private val receiver: ShareReceiver,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean = receiver.handle(context.deviceId, packet)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ShareBindings {
    @Binds
    abstract fun incomingStorage(impl: DownloadsWriter): IncomingStorage

    @Binds
    abstract fun shareNotifier(impl: ShareNotifications): ShareNotifier

    companion object {
        @Provides
        @Singleton
        fun shareReceiver(
            endpoint: app.tandem.core.transfer.PayloadEndpoint,
            transfers: app.tandem.core.transfer.TransferRepository,
            storage: IncomingStorage,
            notifier: ShareNotifier,
        ): ShareReceiver = ShareReceiver(endpoint, transfers, storage, notifier)

        @Provides
        @Singleton
        fun shareSender(
            endpoint: app.tandem.core.transfer.PayloadEndpoint,
            transfers: app.tandem.core.transfer.TransferRepository,
            peers: app.tandem.core.device.PeerSender,
        ): ShareSender = ShareSender(endpoint, transfers, peers)

        @Provides
        @IntoSet
        fun shareDescriptor(receiver: ShareReceiver): PluginDescriptor = ShareDescriptor.create(receiver)
    }
}
