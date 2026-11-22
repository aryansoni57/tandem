// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.di

import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.core.transfer.PayloadTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferBindings {
    @Binds
    abstract fun bindPayloadEndpoint(impl: PayloadTransport): PayloadEndpoint
}
