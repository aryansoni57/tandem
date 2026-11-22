// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.transfer

import app.tandem.transport.TransferControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Share items: the persistent history plus the live progress of running items.
 *
 * Progress arrives many times a second, so it lives in memory and is written to the store only when
 * an item finishes. Cancellation goes through the [TransferControl] registered by [begin].
 */
class TransferRepository(
    private val store: TransferStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    private val controls = ConcurrentHashMap<String, Active>()

    /** Bytes done and the total (-1 when unknown) of a running item. */
    data class LiveProgress(val done: Long, val total: Long)

    private class Active(val batchId: String, val control: TransferControl)

    /** Every item, newest first, with live progress applied to running items. */
    fun observe(): Flow<List<TransferEntity>> =
        combine(store.observeAll(), live) { list, progress ->
            list.map { entity ->
                val p = progress[entity.id]
                if (entity.state == ShareState.RUNNING && p != null) {
                    entity.copy(doneBytes = p.done, totalBytes = p.total)
                } else {
                    entity
                }
            }
        }

    /** Creates a running item and registers its cancel handle. */
    suspend fun begin(
        entity: TransferEntity,
        control: TransferControl,
    ) {
        require(entity.state == ShareState.RUNNING) { "begin needs a RUNNING item" }
        store.put(entity)
        controls[entity.id] = Active(entity.batchId, control)
    }

    /** Records a finished text or URL item without a payload. */
    suspend fun recordFinished(
        entity: TransferEntity,
        failed: String? = null,
    ) {
        store.put(entity.copy(state = if (failed == null) ShareState.DONE else ShareState.FAILED, error = failed))
    }

    /** Called from the transfer thread. Cheap: no storage write. */
    fun progress(
        id: String,
        done: Long,
        total: Long,
    ) {
        live.update { it + (id to LiveProgress(done, total)) }
    }

    suspend fun complete(
        id: String,
        contentUri: String? = null,
    ) = finish(id, ShareState.DONE, error = null) { entity ->
        entity.copy(
            contentUri = contentUri ?: entity.contentUri,
            doneBytes = if (entity.totalBytes >= 0) entity.totalBytes else entity.doneBytes,
        )
    }

    suspend fun fail(
        id: String,
        error: String,
    ) = finish(id, ShareState.FAILED, error) { it }

    suspend fun markCancelled(id: String) = finish(id, ShareState.CANCELLED, error = null) { it }

    /** Asks one running item to stop. Its coroutine then calls [markCancelled]. */
    fun cancel(id: String) {
        controls[id]?.control?.cancel()
    }

    /** Asks every running item of [batchId] to stop. */
    fun cancelBatch(batchId: String) {
        controls.values.filter { it.batchId == batchId }.forEach { it.control.cancel() }
    }

    /**
     * Items still marked RUNNING with no live coroutine (the process was killed) become FAILED.
     * Call once at start-up, before any transfer begins.
     */
    suspend fun recoverInterrupted(): Int {
        val stale = store.observeAll().first().filter { it.state == ShareState.RUNNING && !controls.containsKey(it.id) }
        stale.forEach { fail(it.id, INTERRUPTED) }
        return stale.size
    }

    private suspend fun finish(
        id: String,
        state: ShareState,
        error: String?,
        transform: (TransferEntity) -> TransferEntity,
    ) {
        val current = store.get(id) ?: return
        controls.remove(id)
        live.update { it - id }
        store.put(transform(current).copy(state = state, error = error, updatedAt = now()))
    }

    companion object {
        const val INTERRUPTED = "Interrupted when Tandem stopped"
    }
}

/** One row of the UI: the items of a batch (one file, or a group sent or received together). */
data class BatchSummary(
    val batchId: String,
    val deviceId: String,
    val outgoing: Boolean,
    val items: List<TransferEntity>,
) {
    val createdAt: Long get() = items.minOf { it.createdAt }

    val kind: ShareKind get() = items.first().kind

    /** The single item's name, or "N files" for a batch. */
    val name: String
        get() = if (items.size == 1) items.first().name else "${items.size} files"

    /** Text or URL body of a single-item batch, else null. */
    val text: String? get() = items.singleOrNull()?.text

    val state: ShareState
        get() =
            when {
                items.any { it.state == ShareState.RUNNING } -> ShareState.RUNNING
                items.all { it.state == ShareState.DONE } -> ShareState.DONE
                items.any { it.state == ShareState.CANCELLED } -> ShareState.CANCELLED
                else -> ShareState.FAILED
            }

    /** Sum of file sizes, or -1 when any file size is unknown. Text and URLs do not count. */
    val totalBytes: Long
        get() {
            val files = items.filter { it.kind == ShareKind.FILE }
            if (files.isEmpty()) return 0
            return if (files.any { it.totalBytes < 0 }) -1 else files.sumOf { it.totalBytes }
        }

    val doneBytes: Long get() = items.filter { it.kind == ShareKind.FILE }.sumOf { it.doneBytes }

    /** Combined progress 0..1 while running with a known total, else null. */
    val fraction: Float?
        get() {
            if (state != ShareState.RUNNING || totalBytes <= 0) return null
            return (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        }

    /** The contentUri of the newest completed file, for the Open action. */
    val contentUri: String? get() = items.lastOrNull { it.contentUri != null }?.contentUri
}

/** Groups items by batch, newest batch first. */
fun groupBatches(items: List<TransferEntity>): List<BatchSummary> =
    items.groupBy { it.batchId }
        .map { (batchId, group) ->
            val first = group.first()
            BatchSummary(
                batchId = batchId,
                deviceId = first.deviceId,
                outgoing = first.outgoing,
                items = group.sortedBy { it.name },
            )
        }
        .sortedByDescending { it.createdAt }
