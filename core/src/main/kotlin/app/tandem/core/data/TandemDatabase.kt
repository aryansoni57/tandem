// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tandem.core.transfer.ShareConverters
import app.tandem.core.transfer.TransferDao
import app.tandem.core.transfer.TransferEntity

@Database(
    entities = [TrustedDeviceEntity::class, ClipEntity::class, TransferEntity::class, NotifAppRuleEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(ShareConverters::class)
abstract class TandemDatabase : RoomDatabase() {
    abstract fun trustedDevices(): TrustedDeviceDao

    abstract fun clips(): ClipDao

    abstract fun transfers(): TransferDao

    abstract fun notifAppRules(): NotifAppRuleDao

    companion object {
        /** Phase 2 adds the clipboard history table. Trusted devices are kept. */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `clip` (`id` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                            "`deviceId` TEXT, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }

        /** Phase 4 adds the per-app notification rules. */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `notif_app_rule` (`packageName` TEXT NOT NULL, " +
                            "`appName` TEXT NOT NULL, `category` TEXT NOT NULL, `rule` TEXT NOT NULL, " +
                            "PRIMARY KEY(`packageName`))",
                    )
                }
            }

        /** Phase 3 adds the share history table. */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transfer` (`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, " +
                            "`deviceId` TEXT NOT NULL, `outgoing` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, `mime` TEXT, `totalBytes` INTEGER NOT NULL, " +
                            "`doneBytes` INTEGER NOT NULL, `state` TEXT NOT NULL, `contentUri` TEXT, " +
                            "`text` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }
    }
}
