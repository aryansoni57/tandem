// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.battery

import app.tandem.core.device.PeerStateStore
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.PacketTypes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

object BatteryDescriptor {
    fun create(
        source: BatterySource,
        peers: PeerStateStore,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "battery",
            incoming = setOf(PacketTypes.BATTERY, PacketTypes.BATTERY_REQUEST),
            outgoing = setOf(PacketTypes.BATTERY, PacketTypes.BATTERY_REQUEST),
            requiredPermissions = emptyList(),
            factory = { BatteryPlugin(it, source, peers) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object BatteryModule {
    @Provides
    @IntoSet
    fun batteryDescriptor(
        monitor: BatteryMonitor,
        peers: PeerStateStore,
    ): PluginDescriptor = BatteryDescriptor.create(monitor, peers)
}
