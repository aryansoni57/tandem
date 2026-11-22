// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.trust

import app.tandem.core.data.TrustedDeviceDao
import app.tandem.core.data.TrustedDeviceEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paired devices. Room is the persistent copy. An in-memory map answers the TLS layer's
 * pin lookups without suspending.
 */
@Singleton
class TrustStore
    @Inject
    constructor(
        private val dao: TrustedDeviceDao,
    ) {
        private val cache = ConcurrentHashMap<String, TrustedDeviceEntity>()

        suspend fun load() {
            cache.clear()
            dao.all().forEach { cache[it.id] = it }
        }

        fun get(deviceId: String): TrustedDeviceEntity? = cache[deviceId]

        fun all(): List<TrustedDeviceEntity> = cache.values.toList()

        suspend fun hasAny(): Boolean = dao.count() > 0

        suspend fun store(entity: TrustedDeviceEntity) {
            dao.upsert(entity)
            cache[entity.id] = entity
        }

        suspend fun remove(deviceId: String) {
            dao.delete(deviceId)
            cache.remove(deviceId)
        }

        /** Drops every pin. Used when our own identity is regenerated. */
        suspend fun clear() {
            dao.deleteAll()
            cache.clear()
        }
    }
