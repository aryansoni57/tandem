// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What happens to notifications of one app. */
enum class NotifRule {
    /** Sent in full. */
    SYNC,

    /** Sent with the app name and title only. */
    PREVIEW_ONLY,

    /** Never sent. */
    HIDDEN,
}

/** One app that has posted a notification, with the user's rule for it. [category] is a SyncCategory name. */
@Entity(tableName = "notif_app_rule")
data class NotifAppRuleEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val category: String,
    val rule: NotifRule,
)

@Dao
interface NotifAppRuleDao {
    @Upsert
    suspend fun upsert(entity: NotifAppRuleEntity)

    @Query("SELECT * FROM notif_app_rule WHERE packageName = :packageName")
    suspend fun get(packageName: String): NotifAppRuleEntity?

    @Query("SELECT * FROM notif_app_rule ORDER BY appName COLLATE NOCASE")
    fun observeAll(): Flow<List<NotifAppRuleEntity>>

    @Query("UPDATE notif_app_rule SET rule = :rule WHERE packageName = :packageName")
    suspend fun setRule(
        packageName: String,
        rule: NotifRule,
    )
}
