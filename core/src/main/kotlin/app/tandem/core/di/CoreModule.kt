// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.di

import android.content.Context
import androidx.room.Room
import app.tandem.core.data.NotifAppRuleDao
import app.tandem.core.data.ClipDao
import app.tandem.core.data.TandemDatabase
import app.tandem.core.data.TrustedDeviceDao
import app.tandem.core.transfer.RoomTransferStore
import app.tandem.core.transfer.TransferDao
import app.tandem.core.transfer.TransferRepository
import app.tandem.core.transfer.TransferStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): TandemDatabase =
        Room.databaseBuilder(context, TandemDatabase::class.java, "tandem.db")
            .addMigrations(TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideTrustedDeviceDao(database: TandemDatabase): TrustedDeviceDao = database.trustedDevices()

    @Provides
    fun provideClipDao(database: TandemDatabase): ClipDao = database.clips()

    @Provides
    fun provideTransferDao(database: TandemDatabase): TransferDao = database.transfers()

    @Provides
    fun provideNotifAppRuleDao(database: TandemDatabase): NotifAppRuleDao = database.notifAppRules()

    @Provides
    @Singleton
    fun provideTransferStore(dao: TransferDao): TransferStore = RoomTransferStore(dao)

    @Provides
    @Singleton
    fun provideTransferRepository(store: TransferStore): TransferRepository = TransferRepository(store)
}
