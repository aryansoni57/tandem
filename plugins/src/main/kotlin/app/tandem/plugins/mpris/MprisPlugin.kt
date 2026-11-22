// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mpris

import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.MprisAction
import app.tandem.protocol.body.MprisRequest
import app.tandem.protocol.body.MprisStatus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Asks the desktop for its players when the link opens, and keeps [MprisState] up to date. */
class MprisPlugin(
    private val context: PluginContext,
    private val state: MprisState,
    private val now: () -> Long = System::currentTimeMillis,
) : Plugin {
    override suspend fun onCreate() {
        context.send(MprisRequest.PlayerList.toPacket())
    }

    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.MPRIS) return false
        val status = MprisStatus.parse(packet.body)
        // A new or changed player: ask for its full state.
        state.update(context.deviceId, status, now())?.let { player ->
            context.send(MprisRequest.NowPlaying(player).toPacket())
        }
        return true
    }

    override suspend fun onDestroy() {
        state.clear(context.deviceId)
    }
}

/** Sends player commands to the desktop that last reported media. All return false with no player. */
class MprisController(
    private val peers: PeerSender,
    private val state: MprisState,
) {
    suspend fun action(action: MprisAction): Boolean = request { MprisRequest.Action(it, action) }

    /** Controls [name] from now on and asks the desktop for its state. */
    suspend fun selectPlayer(name: String): Boolean {
        if (!state.select(name)) return false
        val device = state.device ?: return false
        return peers.sendPacket(device, MprisRequest.NowPlaying(name).toPacket())
    }

    suspend fun setVolume(percent: Int): Boolean = request { MprisRequest.SetVolume(it, percent) }

    suspend fun seekTo(positionMs: Long): Boolean = request { MprisRequest.SetPosition(it, positionMs) }

    private suspend fun request(build: (String) -> MprisRequest): Boolean {
        val device = state.device ?: return false
        val player = state.player ?: return false
        return peers.sendPacket(device, build(player).toPacket())
    }
}

object MprisDescriptor {
    fun create(state: MprisState): PluginDescriptor =
        PluginDescriptor(
            id = "mpris",
            incoming = setOf(PacketTypes.MPRIS),
            outgoing = setOf(PacketTypes.MPRIS_REQUEST),
            requiredPermissions = emptyList(),
            factory = { MprisPlugin(it, state) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object MprisModule {
    @Provides
    @IntoSet
    fun descriptor(state: MprisState): PluginDescriptor = MprisDescriptor.create(state)

    @Provides
    @Singleton
    fun controller(
        // Lazy: PeerSender is the DeviceManager, which builds the plugin registry.
        peers: dagger.Lazy<PeerSender>,
        state: MprisState,
    ): MprisController =
        MprisController(
            object : PeerSender {
                override suspend fun sendPacket(
                    deviceId: String,
                    packet: NetworkPacket,
                ): Boolean = peers.get().sendPacket(deviceId, packet)
            },
            state,
        )
}
