// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.runcommand

import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.DesktopCommand
import app.tandem.protocol.body.RunCommandList
import app.tandem.protocol.body.RunCommandRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The commands of the connected desktop. Cleared when that desktop disconnects. */
@Singleton
class RunCommandState
    @Inject
    constructor() {
        private val lock = Any()
        private val commandFlow = MutableStateFlow<List<DesktopCommand>>(emptyList())
        private val canAddFlow = MutableStateFlow(false)

        val commands: StateFlow<List<DesktopCommand>> = commandFlow.asStateFlow()
        val canAdd: StateFlow<Boolean> = canAddFlow.asStateFlow()

        /** The device the list came from, or null before any list arrived. */
        var device: String? = null
            get() = synchronized(lock) { field }
            private set

        fun update(
            deviceId: String,
            list: RunCommandList,
        ) {
            synchronized(lock) {
                device = deviceId
                list.commands?.let { commandFlow.value = it }
                list.canAddCommand?.let { canAddFlow.value = it }
            }
        }

        fun clear(deviceId: String) {
            synchronized(lock) {
                if (device == deviceId) {
                    device = null
                    commandFlow.value = emptyList()
                    canAddFlow.value = false
                }
            }
        }
    }

/** Asks for the command list when the link opens and keeps [RunCommandState] current. */
class RunCommandPlugin(
    private val context: PluginContext,
    private val state: RunCommandState,
) : Plugin {
    override suspend fun onCreate() {
        context.send(RunCommandRequest.List.toPacket())
    }

    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.RUNCOMMAND) return false
        state.update(context.deviceId, RunCommandList.parse(packet.body))
        return true
    }

    override suspend fun onDestroy() {
        state.clear(context.deviceId)
    }
}

/** Sends run, refresh and setup requests to the desktop that sent the list. False when there is none. */
class RunCommandController(
    private val peers: PeerSender,
    private val state: RunCommandState,
) {
    suspend fun run(key: String): Boolean = send(RunCommandRequest.Run(key))

    suspend fun refresh(): Boolean = send(RunCommandRequest.List)

    suspend fun setup(): Boolean = send(RunCommandRequest.Setup)

    private suspend fun send(request: RunCommandRequest): Boolean {
        val device = state.device ?: return false
        return peers.sendPacket(device, request.toPacket())
    }
}

object RunCommandDescriptor {
    fun create(state: RunCommandState): PluginDescriptor =
        PluginDescriptor(
            id = "runcommand",
            incoming = setOf(PacketTypes.RUNCOMMAND),
            outgoing = setOf(PacketTypes.RUNCOMMAND_REQUEST),
            requiredPermissions = emptyList(),
            factory = { RunCommandPlugin(it, state) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object RunCommandModule {
    @Provides
    @IntoSet
    fun descriptor(state: RunCommandState): PluginDescriptor = RunCommandDescriptor.create(state)

    @Provides
    @Singleton
    fun controller(
        // Lazy: PeerSender is the DeviceManager, which builds the plugin registry.
        peers: dagger.Lazy<PeerSender>,
        state: RunCommandState,
    ): RunCommandController =
        RunCommandController(
            object : PeerSender {
                override suspend fun sendPacket(
                    deviceId: String,
                    packet: NetworkPacket,
                ): Boolean = peers.get().sendPacket(deviceId, packet)
            },
            state,
        )
}
