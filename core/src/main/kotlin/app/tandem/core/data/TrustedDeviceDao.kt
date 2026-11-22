// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_device")
    suspend fun all(): List<TrustedDeviceEntity>

    @Upsert
    suspend fun upsert(entity: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_device WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trusted_device")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM trusted_device")
    suspend fun count(): Int
}
