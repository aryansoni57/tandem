// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.ping

import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.PacketTypes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

object PingDescriptor {
    fun create(sink: PingSink): PluginDescriptor =
        PluginDescriptor(
            id = "ping",
            incoming = setOf(PacketTypes.PING),
            outgoing = setOf(PacketTypes.PING),
            requiredPermissions = emptyList(),
            optionalPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
            factory = { PingPlugin(it, sink) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object PingModule {
    @Provides
    @IntoSet
    fun pingDescriptor(sink: AndroidPingSink): PluginDescriptor = PingDescriptor.create(sink)
}
