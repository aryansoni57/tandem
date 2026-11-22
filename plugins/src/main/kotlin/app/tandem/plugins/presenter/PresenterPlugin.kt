// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.presenter

import app.tandem.core.device.DeviceManager
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Send-only: the desktop never replies. The plugin exists so the capability is advertised. */
class PresenterPlugin : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean = false
}

object PresenterDescriptor {
    fun create(): PluginDescriptor =
        PluginDescriptor(
            id = "presenter",
            incoming = emptySet(),
            outgoing = setOf(PacketTypes.PRESENTER),
            requiredPermissions = emptyList(),
            factory = { PresenterPlugin() },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object PresenterModule {
    @Provides
    @IntoSet
    fun descriptor(): PluginDescriptor = PresenterDescriptor.create()

    @Provides
    @Singleton
    fun sender(
        // Lazy: PeerSender and DeviceManager are the same object, which builds the plugin registry.
        peers: dagger.Lazy<PeerSender>,
        devices: dagger.Lazy<DeviceManager>,
    ): PresenterSender =
        PresenterSender(
            peers =
                object : PeerSender {
                    override suspend fun sendPacket(
                        deviceId: String,
                        packet: NetworkPacket,
                    ): Boolean = peers.get().sendPacket(deviceId, packet)
                },
            target = { devices.get().deviceStates.value.firstOrNull { it.paired && it.reachable }?.deviceId },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
