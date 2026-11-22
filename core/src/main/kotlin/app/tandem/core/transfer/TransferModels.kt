// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.transfer

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** What a share carries. */
enum class ShareKind { FILE, TEXT, URL }

/** Lifecycle of one item. RUNNING is the only state that changes after it is written. */
enum class ShareState { RUNNING, DONE, FAILED, CANCELLED }

/**
 * One item of a share: a file, a text or a URL, sent or received. Items sent together share a
 * [batchId]. [totalBytes] and [doneBytes] are -1 and 0 for text and URLs.
 *
 * @property contentUri where a received file lives (MediaStore URI as text), or null.
 * @property text the body of a text or URL item.
 */
@Entity(tableName = "transfer")
data class TransferEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val deviceId: String,
    val outgoing: Boolean,
    val kind: ShareKind,
    val name: String,
    val mime: String?,
    val totalBytes: Long,
    val doneBytes: Long,
    val state: ShareState,
    val contentUri: String?,
    val text: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class ShareConverters {
    @TypeConverter
    fun kindToString(kind: ShareKind): String = kind.name

    @TypeConverter
    fun stringToKind(value: String): ShareKind = ShareKind.valueOf(value)

    @TypeConverter
    fun ruleToString(rule: app.tandem.core.data.NotifRule): String = rule.name

    @TypeConverter
    fun stringToRule(value: String): app.tandem.core.data.NotifRule = app.tandem.core.data.NotifRule.valueOf(value)

    @TypeConverter
    fun stateToString(state: ShareState): String = state.name

    @TypeConverter
    fun stringToState(value: String): ShareState = ShareState.valueOf(value)
}
