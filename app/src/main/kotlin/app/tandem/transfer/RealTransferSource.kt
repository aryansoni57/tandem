// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transfer

import android.text.format.DateUtils
import app.tandem.core.data.SettingsStore
import app.tandem.core.transfer.BatchSummary
import app.tandem.core.transfer.ShareKind
import app.tandem.core.transfer.ShareState
import app.tandem.core.transfer.TransferRepository
import app.tandem.core.transfer.groupBatches
import app.tandem.plugins.sftp.AndroidStorageAccess
import app.tandem.state.SharedStorage
import app.tandem.state.Transfer
import app.tandem.state.TransferDirection
import app.tandem.state.TransferKind
import app.tandem.state.TransferSource
import app.tandem.state.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Share history from [TransferRepository], one row per batch. Storage stays sample data until the
 * SFTP plugin (phase 13) lands.
 */
@Singleton
class RealTransferSource
    @Inject
    constructor(
        private val repository: TransferRepository,
        private val actions: ShareActions,
        private val settings: SettingsStore,
        private val access: AndroidStorageAccess,
    ) : TransferSource {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override val transfers: StateFlow<List<Transfer>> =
            repository.observe()
                .map { rows -> groupBatches(rows).take(HISTORY_LIMIT).map(::toUi) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        override val today: StateFlow<List<Transfer>> =
            transfers.map { it.take(RECENT_LIMIT) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        // The access check and the free space are read again every few seconds while the Share tab is open, so
        // granting "all files access" in settings shows up when the user comes back.
        private val ticker: Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(STORAGE_REFRESH_MS)
                }
            }

        override val storage: StateFlow<SharedStorage> =
            combine(settings.sftpEnabled, settings.sftpFolders, ticker) { enabled, chosen, _ ->
                val (used, total) = access.usage()
                SharedStorage(
                    enabled = enabled,
                    usedGb = used / BYTES_PER_GB,
                    totalGb = total / BYTES_PER_GB,
                    folders = chosen.sorted(),
                    candidates = access.topFolders().filter { it !in chosen }.sorted(),
                    hasAccess = access.hasAccess(),
                )
            }.flowOn(Dispatchers.IO)
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY_STORAGE)

        init {
            // Rows left RUNNING by a killed process can never finish: mark them failed.
            scope.launch { repository.recoverInterrupted() }
        }

        override suspend fun sendLink(url: String): Boolean = actions.sendLinkToConnected(url)

        override fun cancel(id: String) {
            repository.cancelBatch(id)
        }

        override fun setStorageEnabled(enabled: Boolean) {
            scope.launch { settings.setSftpEnabled(enabled) }
        }

        override fun addSharedFolder(name: String) {
            scope.launch { settings.setSftpFolders(settings.sftpFolders.first() + name) }
        }

        override fun removeSharedFolder(name: String) {
            scope.launch { settings.setSftpFolders(settings.sftpFolders.first() - name) }
        }

        private fun toUi(batch: BatchSummary): Transfer {
            val running = batch.state == ShareState.RUNNING
            val size = batch.totalBytes.takeIf { batch.kind == ShareKind.FILE && it > 0 }
            return Transfer(
                id = batch.batchId,
                name = batch.name,
                kind = kindOf(batch),
                direction = if (batch.outgoing) TransferDirection.ToLaptop else TransferDirection.FromLaptop,
                sizeMb = size?.let { it / BYTES_PER_MB },
                progress = if (running) batch.fraction ?: 0f else null,
                timeLabel = relativeTime(batch.createdAt),
                status =
                    when (batch.state) {
                        ShareState.FAILED -> TransferStatus.Failed
                        ShareState.CANCELLED -> TransferStatus.Cancelled
                        else -> null
                    },
            )
        }

        private fun kindOf(batch: BatchSummary): TransferKind =
            when (batch.kind) {
                ShareKind.URL -> TransferKind.Link
                ShareKind.TEXT -> TransferKind.Document
                ShareKind.FILE -> {
                    val ext = batch.items.first().name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in IMAGE_EXT && batch.items.size == 1 -> TransferKind.Image
                        ext in VIDEO_EXT && batch.items.size == 1 -> TransferKind.Video
                        else -> TransferKind.Document
                    }
                }
            }

        private fun relativeTime(at: Long): String =
            DateUtils.getRelativeTimeSpanString(
                at,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()

        companion object {
            private const val BYTES_PER_MB = 1024f * 1024f
            private const val BYTES_PER_GB = 1024f * 1024f * 1024f
            private const val STORAGE_REFRESH_MS = 3_000L
            private const val STOP_TIMEOUT_MS = 5_000L
            private val EMPTY_STORAGE =
                SharedStorage(enabled = false, usedGb = 0f, totalGb = 1f, folders = emptyList(), hasAccess = false)
            private const val HISTORY_LIMIT = 50
            private const val RECENT_LIMIT = 5
            private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
            private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "3gp")
        }
    }
