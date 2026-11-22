// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipDao {
    @Insert
    suspend fun insert(entity: ClipEntity)

    /** Keeps the newest [keep] rows and drops the rest. */
    @Query(
        "DELETE FROM clip WHERE id NOT IN (SELECT id FROM clip ORDER BY timestamp DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM clip")
    suspend fun count(): Int
}
