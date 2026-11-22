// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceBindings {
    @Binds
    abstract fun bindPeerSender(manager: DeviceManager): PeerSender
}
