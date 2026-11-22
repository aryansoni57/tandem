// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import app.tandem.core.device.PeerSender
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.core.transfer.ShareKind
import app.tandem.core.transfer.ShareState
import app.tandem.core.transfer.TransferEntity
import app.tandem.core.transfer.TransferRepository
import app.tandem.protocol.body.ShareRequestBody
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferCancelledException
import app.tandem.transport.TransferControl
import java.io.InputStream
import java.util.UUID

/** One file to send. [size] is -1 when unknown, and the payload then runs until the stream ends. */
class OutgoingFile(
    val name: String,
    val size: Long,
    val mime: String?,
    val stream: InputStream,
)

/**
 * Sends links, text and files to a paired device. Files of one share form a batch and go one after
 * another, so a single payload socket is open at a time.
 */
class ShareSender(
    private val endpoint: PayloadEndpoint,
    private val transfers: TransferRepository,
    private val peers: PeerSender,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Sends a text. Returns false when no live link accepts it. */
    suspend fun sendText(
        deviceId: String,
        text: String,
    ): Boolean =
        sendShort(deviceId, ShareKind.TEXT, text, text.lineSequence().firstOrNull().orEmpty().take(NAME_CHARS)) {
            ShareRequestBody.Text(text).toPacket()
        }

    /** Sends a URL. Returns false when no live link accepts it. */
    suspend fun sendUrl(
        deviceId: String,
        url: String,
    ): Boolean = sendShort(deviceId, ShareKind.URL, url, url) { ShareRequestBody.Url(url).toPacket() }

    /**
     * Sends [files] as one batch and returns the batch id. Every file is listed in the repository
     * before the first one starts, so a batch cancel reaches files that have not started yet.
     */
    // Any failure ends one file and is recorded; the rest of the batch is failed or cancelled.
    @Suppress("TooGenericExceptionCaught")
    suspend fun sendFiles(
        deviceId: String,
        files: List<OutgoingFile>,
    ): String {
        val batchId = newId()
        val total = if (files.any { it.size < 0 }) -1L else files.sumOf { it.size }
        val rows =
            files.map { file ->
                val id = newId()
                val control = TransferControl()
                val at = now()
                val entity =
                    TransferEntity(
                        id = id, batchId = batchId, deviceId = deviceId, outgoing = true, kind = ShareKind.FILE,
                        name = file.name, mime = file.mime, totalBytes = file.size, doneBytes = 0,
                        state = ShareState.RUNNING, contentUri = null, text = null, error = null,
                        createdAt = at, updatedAt = at,
                    )
                transfers.begin(entity, control)
                Row(id, file, control)
            }
        var linkLost = false
        for (row in rows) {
            if (linkLost) {
                row.file.stream.close()
                transfers.fail(row.id, NO_LINK)
                continue
            }
            try {
                val progress = PayloadProgress { done, size -> transfers.progress(row.id, done, size) }
                val sent =
                    endpoint.send(deviceId, row.file.size, row.file.stream, row.control, progress) { port ->
                        peers.sendPacket(
                            deviceId,
                            ShareRequestBody.File(
                                filename = row.file.name,
                                numberOfFiles = files.size,
                                totalPayloadSize = total.takeIf { it >= 0 },
                            ).toPacket(payloadSize = row.file.size, payloadPort = port),
                        )
                    }
                if (sent == null) {
                    linkLost = true
                    transfers.fail(row.id, NO_LINK)
                } else {
                    transfers.complete(row.id)
                }
            } catch (_: TransferCancelledException) {
                transfers.markCancelled(row.id)
            } catch (e: Exception) {
                transfers.fail(row.id, e.message ?: "Transfer failed")
            } finally {
                row.file.stream.close()
            }
        }
        return batchId
    }

    private suspend fun sendShort(
        deviceId: String,
        kind: ShareKind,
        text: String,
        name: String,
        packet: () -> app.tandem.protocol.NetworkPacket,
    ): Boolean {
        val id = newId()
        val at = now()
        val sent = peers.sendPacket(deviceId, packet())
        transfers.recordFinished(
            TransferEntity(
                id = id, batchId = id, deviceId = deviceId, outgoing = true, kind = kind, name = name,
                mime = null, totalBytes = 0, doneBytes = 0, state = ShareState.DONE, contentUri = null,
                text = text, error = null, createdAt = at, updatedAt = at,
            ),
            failed = if (sent) null else NO_LINK,
        )
        return sent
    }

    private class Row(
        val id: String,
        val file: OutgoingFile,
        val control: TransferControl,
    )

    companion object {
        const val NO_LINK = "The device is not connected"
        private const val NAME_CHARS = 60
    }
}
