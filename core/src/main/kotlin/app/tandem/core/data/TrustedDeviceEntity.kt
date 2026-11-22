// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A paired device. The certificate is public, so storing it here is safe. */
@Entity(tableName = "trusted_device")
data class TrustedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val certDer: ByteArray,
    val certSha256: String,
    val protocolVersion: Int,
    val pairedAt: Long,
    val lastSeen: Long,
    val lastAddress: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is TrustedDeviceEntity &&
            other.id == id &&
            other.certDer.contentEquals(certDer) &&
            other.lastSeen == lastSeen

    override fun hashCode(): Int = id.hashCode()
}
