// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One clipboard history entry. [deviceId] is null for text the phone copied itself. */
@Entity(tableName = "clip")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val deviceId: String?,
    val timestamp: Long,
)
