// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import android.content.Context
import android.os.PowerManager
import app.tandem.core.data.NotifAppRuleDao
import app.tandem.core.data.NotifAppRuleEntity
import app.tandem.core.data.NotifRule
import app.tandem.core.data.SettingsStore
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.core.transfer.PayloadEndpoint
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Rules and settings from the database and the settings store. A new app starts as SYNC. */
@Singleton
class AndroidNotificationPolicy
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val rules: NotifAppRuleDao,
        private val settings: SettingsStore,
    ) : NotificationPolicy {
        override suspend fun ruleFor(packageName: String): NotifRule = rules.get(packageName)?.rule ?: NotifRule.SYNC

        override suspend fun seen(snapshot: NotificationSnapshot) {
            if (rules.get(snapshot.packageName) != null) return
            rules.upsert(NotifAppRuleEntity(snapshot.packageName, snapshot.appName, snapshot.category, NotifRule.SYNC))
        }

        override suspend fun showOnLaptop(): Boolean = settings.notifShowOnLaptop.first()

        override suspend fun screenOffOnly(): Boolean = settings.notifScreenOffOnly.first()

        override fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive
    }

/** Resolves the real sender on first use. */
private class LazyPeerSender(private val delegate: dagger.Lazy<PeerSender>) : PeerSender {
    override suspend fun sendPacket(
        deviceId: String,
        packet: app.tandem.protocol.NetworkPacket,
    ): Boolean = delegate.get().sendPacket(deviceId, packet)

    override suspend fun livePeer(deviceId: String) = delegate.get().livePeer(deviceId)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsBindings {
    @Binds
    abstract fun policy(impl: AndroidNotificationPolicy): NotificationPolicy

    @Binds
    abstract fun source(impl: AndroidNotificationSource): NotificationSource

    companion object {
        @Provides
        @Singleton
        fun bridge(
            @ApplicationContext context: Context,
            // Lazy: PeerSender is the DeviceManager.
            peers: dagger.Lazy<PeerSender>,
            endpoint: PayloadEndpoint,
            policy: NotificationPolicy,
            // Lazy: DeviceManager builds the plugin registry, which holds this plugin's descriptor.
            devices: dagger.Lazy<DeviceManager>,
        ): NotificationBridge =
            NotificationBridge(LazyPeerSender(peers), endpoint, policy, NotificationMapper(context.packageName)) {
                devices.get().deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
            }

        @Provides
        @IntoSet
        fun descriptor(
            bridge: NotificationBridge,
            source: NotificationSource,
        ): PluginDescriptor = NotificationsDescriptor.create(bridge, source)
    }
}
