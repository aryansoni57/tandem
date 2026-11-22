// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.findmyphone

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
import kotlinx.coroutines.flow.StateFlow

/** Rings the phone: sound, vibration, flashlight and a full-screen notification. */
interface PhoneRinger {
    val ringing: StateFlow<Boolean>

    /** Starts ringing. [requester] is the name of the device that asked, or null when unknown. */
    fun start(requester: String?)

    fun stop()
}

/**
 * The laptop asks the phone to ring. A request while ringing stops it, so the same button on the laptop
 * both starts and ends the ring.
 */
class FindMyPhonePlugin(
    private val context: PluginContext,
    private val ringer: PhoneRinger,
    private val nameOf: (String) -> String?,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.FINDMYPHONE_REQUEST) return false
        if (ringer.ringing.value) ringer.stop() else ringer.start(nameOf(context.deviceId))
        return true
    }
}

object FindMyPhoneDescriptor {
    fun create(
        ringer: PhoneRinger,
        nameOf: (String) -> String?,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "findmyphone",
            // The same type goes both ways: incoming rings this phone, outgoing rings the laptop.
            incoming = setOf(PacketTypes.FINDMYPHONE_REQUEST),
            outgoing = setOf(PacketTypes.FINDMYPHONE_REQUEST),
            requiredPermissions = emptyList(),
            optionalPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
            factory = { FindMyPhonePlugin(it, ringer, nameOf) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FindMyPhoneBindings {
    @Binds
    abstract fun ringer(impl: AndroidPhoneRinger): PhoneRinger

    companion object {
        @Provides
        @IntoSet
        fun descriptor(
            ringer: PhoneRinger,
            // Lazy: DeviceManager builds the plugin registry, which holds this descriptor.
            devices: dagger.Lazy<app.tandem.core.device.DeviceManager>,
        ): PluginDescriptor =
            FindMyPhoneDescriptor.create(ringer) { id ->
                devices.get().deviceStates.value.firstOrNull { it.deviceId == id }?.name
            }
    }
}
