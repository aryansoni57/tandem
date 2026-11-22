// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.core.transfer.ShareKind
import app.tandem.core.transfer.ShareState
import app.tandem.core.transfer.TransferEntity
import app.tandem.core.transfer.TransferRepository
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ShareRequestBody
import app.tandem.protocol.body.ShareUpdateBody
import app.tandem.protocol.share.FileNames
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferCancelledException
import app.tandem.transport.TransferControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

/** Where received files go. The Android implementation writes to MediaStore Downloads/Tandem. */
interface IncomingStorage {
    /** Creates a file for [name] (made unique by the implementation). Its bytes go to [IncomingFile.stream]. */
    fun create(name: String): IncomingFile
}

interface IncomingFile {
    /** The name the file was given after making it unique. */
    val name: String

    /** MIME type from the name, for opening the file. */
    val mime: String

    val stream: OutputStream

    /** A URI string that opens the file, or null when there is none. */
    val uri: String?

    /** Publishes the file, with its modification time when known. Called once, after the stream is closed. */
    fun commit(lastModifiedMs: Long?)

    /** Removes the partial file. Safe to call after [commit] (then it does nothing). */
    fun abort()
}

/** Shows the user what arrived. Tapping actions happen in the notification, never automatically. */
interface ShareNotifier {
    fun receiving(
        id: String,
        deviceId: String,
        name: String,
        done: Long,
        total: Long,
    )

    fun fileReceived(
        id: String,
        deviceId: String,
        name: String,
        uri: String?,
        mime: String,
    )

    fun linkReceived(
        id: String,
        deviceId: String,
        url: String,
    )

    fun textReceived(
        id: String,
        deviceId: String,
        text: String,
    )

    fun failed(
        id: String,
        deviceId: String,
        name: String,
        reason: String,
    )
}

/**
 * Handles incoming `share.request` and `share.request.update` packets (plan section 3, Phase 3).
 *
 * A file is streamed from the payload socket straight into [IncomingStorage]. Files that arrive
 * together form a batch: the first packet of a batch sets its size, and an update packet can raise it.
 */
class ShareReceiver(
    private val endpoint: PayloadEndpoint,
    private val transfers: TransferRepository,
    private val storage: IncomingStorage,
    private val notifier: ShareNotifier,
    // Not the device's link scope: a link can be replaced mid-transfer (duplicate links), and the payload
    // socket does not depend on it. The transfer ends by itself, or by cancellation.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val batches = HashMap<String, Batch>()

    /**
     * One batch of files from one device. [expected] comes from the packets (and update packets raise it).
     * [claimed] counts the files that have started. A new file joins the open batch until all expected
     * files are claimed.
     */
    private class Batch(val id: String, var expected: Int) {
        var claimed = 0
    }

    /**
     * Returns true when [packet] is a share packet. Work runs on [scope], because the caller holds the
     * device manager's lock and a transfer can take minutes.
     */
    fun handle(
        deviceId: String,
        packet: NetworkPacket,
    ): Boolean =
        when (packet.type) {
            PacketTypes.SHARE_REQUEST -> {
                val body = ShareRequestBody.parse(packet.body)
                if (body != null) {
                    // The batch is claimed here, before the transfer starts, so an update packet that follows
                    // always finds it.
                    val batch = if (body is ShareRequestBody.File) claim(deviceId, body.numberOfFiles) else null
                    scope.launch { process(deviceId, packet, body, batch) }
                }
                true
            }
            PacketTypes.SHARE_REQUEST_UPDATE -> {
                ShareUpdateBody.parse(packet.body)?.let { update ->
                    synchronized(lock) {
                        batches[deviceId]?.let { it.expected = maxOf(update.numberOfFiles, it.expected) }
                    }
                }
                true
            }
            else -> false
        }

    /** Runs one request to the end: payload, storage, history and notification. */
    suspend fun onRequest(
        deviceId: String,
        packet: NetworkPacket,
        body: ShareRequestBody,
    ) {
        val batch = if (body is ShareRequestBody.File) claim(deviceId, body.numberOfFiles) else null
        process(deviceId, packet, body, batch)
    }

    private suspend fun process(
        deviceId: String,
        packet: NetworkPacket,
        body: ShareRequestBody,
        batch: Batch?,
    ) {
        when (body) {
            is ShareRequestBody.Url -> recordLink(deviceId, body.url)
            is ShareRequestBody.Text -> recordText(deviceId, body.text)
            is ShareRequestBody.File -> receiveFile(deviceId, packet, body, checkNotNull(batch))
        }
    }

    private suspend fun recordLink(
        deviceId: String,
        url: String,
    ) {
        val id = newId()
        val at = now()
        transfers.recordFinished(
            TransferEntity(
                id = id, batchId = id, deviceId = deviceId, outgoing = false, kind = ShareKind.URL,
                name = url, mime = null, totalBytes = 0, doneBytes = 0, state = ShareState.DONE,
                contentUri = null, text = url, error = null, createdAt = at, updatedAt = at,
            ),
        )
        notifier.linkReceived(id, deviceId, url)
    }

    private suspend fun recordText(
        deviceId: String,
        text: String,
    ) {
        val id = newId()
        val at = now()
        transfers.recordFinished(
            TransferEntity(
                id = id, batchId = id, deviceId = deviceId, outgoing = false, kind = ShareKind.TEXT,
                name = text.lineSequence().firstOrNull().orEmpty().take(TEXT_NAME_CHARS), mime = null,
                totalBytes = 0, doneBytes = 0, state = ShareState.DONE,
                contentUri = null, text = text, error = null, createdAt = at, updatedAt = at,
            ),
        )
        notifier.textReceived(id, deviceId, text)
    }

    // Any failure ends this one transfer and is shown to the user; it must not crash the scope.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun receiveFile(
        deviceId: String,
        packet: NetworkPacket,
        body: ShareRequestBody.File,
        batch: Batch,
    ) {
        val size = packet.payloadSize
        val port = packet.payloadPort
        if (size == null || port == null) return // A file without a payload is not something we can receive.
        val id = newId()
        val name = FileNames.sanitize(body.filename)
        val control = TransferControl()
        val at = now()
        transfers.begin(
            TransferEntity(
                id = id, batchId = batch.id, deviceId = deviceId, outgoing = false, kind = ShareKind.FILE,
                name = name, mime = null, totalBytes = size, doneBytes = 0, state = ShareState.RUNNING,
                contentUri = null, text = null, error = null, createdAt = at, updatedAt = at,
            ),
            control,
        )
        notifier.receiving(id, deviceId, name, 0, size)
        var target: IncomingFile? = null
        try {
            val file = storage.create(name)
            target = file
            val progress = PayloadProgress { done, total -> transfers.progress(id, done, total) }
            endpoint.receive(deviceId, port, size, file.stream, control, progress)
            file.stream.close()
            file.commit(body.lastModified)
            transfers.complete(id, file.uri)
            notifier.fileReceived(id, deviceId, file.name, file.uri, file.mime)
        } catch (_: TransferCancelledException) {
            target?.abort()
            transfers.markCancelled(id)
        } catch (e: CancellationException) {
            // The device link closed and took the plugin scope with it.
            target?.abort()
            control.cancel()
            transfers.fail(id, "Connection closed")
            throw e
        } catch (e: Exception) {
            target?.abort()
            val reason = e.message ?: "Transfer failed"
            transfers.fail(id, reason)
            notifier.failed(id, deviceId, name, reason)
        }
    }

    /** The batch this file joins: the open batch of the device, or a new one once every expected file is claimed. */
    private fun claim(
        deviceId: String,
        count: Int,
    ): Batch =
        synchronized(lock) {
            val open = batches[deviceId]?.takeIf { it.claimed < it.expected }
            val batch = open ?: Batch(newId(), count).also { batches[deviceId] = it }
            if (count > batch.expected) batch.expected = count
            batch.claimed++
            batch
        }

    companion object {
        private const val TEXT_NAME_CHARS = 60
    }
}
