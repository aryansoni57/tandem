// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sample

import app.tandem.state.ClipboardHistorySource
import app.tandem.state.ConnectionSource
import app.tandem.state.RemoteSource
import app.tandem.state.SyncSettingsSource
import app.tandem.state.TransferSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the sample implementations. TODO(phase11..phase13): remove each binding when its plugin
 * provides a real source. Until then the screens show the sample content from the mockup.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SampleModule {
    @Binds
    @Singleton
    abstract fun transfers(impl: app.tandem.transfer.RealTransferSource): TransferSource

    @Binds
    @Singleton
    abstract fun clipboardHistory(impl: FakeClipboardHistorySource): ClipboardHistorySource

    @Binds
    @Singleton
    abstract fun syncSettings(impl: RealSyncSettingsSource): SyncSettingsSource

    @Binds
    @Singleton
    abstract fun remote(impl: RealRemoteSource): RemoteSource

    @Binds
    @Singleton
    abstract fun connection(impl: FakeConnectionSource): ConnectionSource
}
