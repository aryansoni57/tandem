// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.core.data.SettingsStore
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.protocol.PacketTypes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

object ClipboardDescriptor {
    fun create(
        store: ClipboardStore,
        system: SystemClipboard,
        history: ClipHistory,
        endpoint: PayloadEndpoint? = null,
        files: ClipboardFileStore? = null,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "clipboard",
            incoming = setOf(PacketTypes.CLIPBOARD, PacketTypes.CLIPBOARD_CONNECT, PacketTypes.CLIPBOARD_FILE),
            outgoing = setOf(PacketTypes.CLIPBOARD, PacketTypes.CLIPBOARD_CONNECT),
            requiredPermissions = emptyList(),
            factory = { ClipboardPlugin(it, store, system, history, endpoint, files) },
        )
}

@Module
@InstallIn(SingletonComponent::class)
object ClipboardModule {
    @Provides
    @IntoSet
    fun clipboardDescriptor(
        store: ClipboardStore,
        system: AndroidSystemClipboard,
        history: ClipboardHistoryRepository,
        endpoint: PayloadEndpoint,
        files: CacheClipboardFileStore,
    ): PluginDescriptor = ClipboardDescriptor.create(store, system, history, endpoint, files)

    @Provides
    @Singleton
    fun clipboardSync(
        store: ClipboardStore,
        system: AndroidSystemClipboard,
        peers: PeerSender,
        history: ClipboardHistoryRepository,
        settings: SettingsStore,
    ): ClipboardSync =
        ClipboardSync(
            store = store,
            system = system,
            peers = peers,
            history = history,
            skipSensitive = { settings.skipSensitiveClips.first() },
        )
}
