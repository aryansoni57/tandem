// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.transfer

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Persistence for share items. The repository writes through this; tests use an in-memory copy. */
interface TransferStore {
    suspend fun put(entity: TransferEntity)

    suspend fun get(id: String): TransferEntity?

    /** Every item, newest first. Emits again after each write. */
    fun observeAll(): Flow<List<TransferEntity>>
}

@Dao
interface TransferDao {
    @Upsert
    suspend fun upsert(entity: TransferEntity)

    @Query("SELECT * FROM transfer WHERE id = :id")
    suspend fun get(id: String): TransferEntity?

    @Query("SELECT * FROM transfer ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<TransferEntity>>
}

/** Room-backed store used by the app. */
class RoomTransferStore(private val dao: TransferDao) : TransferStore {
    override suspend fun put(entity: TransferEntity) = dao.upsert(entity)

    override suspend fun get(id: String): TransferEntity? = dao.get(id)

    override fun observeAll(): Flow<List<TransferEntity>> = dao.observeAll()
}

/** Keeps rows in memory and emits after each write. Used by tests and anywhere no database is wanted. */
class InMemoryTransferStore : TransferStore {
    private val rows = MutableStateFlow<Map<String, TransferEntity>>(emptyMap())

    override suspend fun put(entity: TransferEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun get(id: String): TransferEntity? = rows.value[id]

    override fun observeAll(): Flow<List<TransferEntity>> =
        rows.map { map ->
            map.values.sortedByDescending {
                it.createdAt
            }
        }
}
