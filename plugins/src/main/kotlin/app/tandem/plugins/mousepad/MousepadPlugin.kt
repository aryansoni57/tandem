// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mousepad

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

/**
 * Remote input is send-only: the phone drives the desktop and needs no accessibility service. The
 * desktop's keyboard state and echo packets are accepted and ignored.
 */
class MousepadPlugin : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean =
        packet.type == PacketTypes.MOUSEPAD_KEYBOARDSTATE || packet.type == PacketTypes.MOUSEPAD_ECHO
}

object MousepadDescriptor {
    fun create(): PluginDescriptor =
        PluginDescriptor(
            id = "mousepad",
            incoming = setOf(PacketTypes.MOUSEPAD_KEYBOARDSTATE, PacketTypes.MOUSEPAD_ECHO),
            outgoing = setOf(PacketTypes.MOUSEPAD_REQUEST),
            requiredPermissions = emptyList(),
            factory = { MousepadPlugin() },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object MousepadModule {
    @Provides
    @IntoSet
    fun descriptor(): PluginDescriptor = MousepadDescriptor.create()

    @Provides
    @Singleton
    fun sender(
        // Lazy: PeerSender and DeviceManager are the same object, which builds the plugin registry.
        peers: dagger.Lazy<PeerSender>,
        devices: dagger.Lazy<DeviceManager>,
    ): MousepadSender =
        MousepadSender(
            peers = object : PeerSender {
                override suspend fun sendPacket(
                    deviceId: String,
                    packet: NetworkPacket,
                ): Boolean = peers.get().sendPacket(deviceId, packet)
            },
            target = { devices.get().deviceStates.value.firstOrNull { it.paired && it.reachable }?.deviceId },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
