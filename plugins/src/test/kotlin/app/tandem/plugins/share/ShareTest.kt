// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import app.tandem.core.transfer.InMemoryTransferStore
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.core.transfer.ShareKind
import app.tandem.core.transfer.ShareState
import app.tandem.core.transfer.TransferEntity
import app.tandem.core.transfer.TransferRepository
import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ShareRequestBody
import app.tandem.protocol.body.ShareUpdateBody
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferCancelledException
import app.tandem.transport.TransferControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val DEVICE = "0123456789abcdef0123456789abcdef"

/**
 * Payload endpoint for tests. A receive writes [incoming] to the sink. A send reads the whole source
 * and records it, after [announce] (unless [announces] is false). [beforeSend] runs first, per call.
 */
private class FakeEndpoint : PayloadEndpoint {
    var incoming: ByteArray = ByteArray(0)
    var receiveError: Exception? = null
    var receivedPort: Int? = null
    var announcePort = 1739
    var announces = true
    var beforeSend: (Int) -> Unit = {}
    val sentBytes = mutableListOf<ByteArray>()
    private var sendCount = 0

    override suspend fun receive(
        deviceId: String,
        port: Int,
        size: Long,
        sink: OutputStream,
        control: TransferControl,
        progress: PayloadProgress,
    ): Long {
        receivedPort = port
        receiveError?.let { throw it }
        sink.write(incoming)
        progress.onProgress(incoming.size.toLong(), size)
        return incoming.size.toLong()
    }

    override suspend fun send(
        deviceId: String,
        size: Long,
        source: InputStream,
        control: TransferControl,
        progress: PayloadProgress,
        announce: suspend (port: Int) -> Boolean,
    ): Long? {
        beforeSend(sendCount++)
        control.throwIfCancelled()
        if (!announces || !announce(announcePort)) return null
        val bytes = source.readBytes()
        sentBytes += bytes
        return bytes.size.toLong()
    }
}

private class FakeStorage : IncomingStorage {
    val created = mutableListOf<FakeFile>()

    override fun create(name: String): IncomingFile = FakeFile(name).also { created += it }
}

private class FakeFile(override val name: String) : IncomingFile {
    val bytes = ByteArrayOutputStream()
    override val mime: String = "application/octet-stream"
    override val stream: OutputStream get() = bytes
    override val uri: String? = "content://downloads/$name"
    var committedAt: Long? = null
    var committed = false
    var aborted = false

    override fun commit(lastModifiedMs: Long?) {
        committed = true
        committedAt = lastModifiedMs
    }

    override fun abort() {
        if (!committed) aborted = true
    }
}

private class RecordingNotifier : ShareNotifier {
    val events = mutableListOf<String>()

    override fun receiving(
        id: String,
        deviceId: String,
        name: String,
        done: Long,
        total: Long,
    ) {
        events += "receiving:$name"
    }

    override fun fileReceived(
        id: String,
        deviceId: String,
        name: String,
        uri: String?,
        mime: String,
    ) {
        events += "file:$name"
    }

    override fun linkReceived(
        id: String,
        deviceId: String,
        url: String,
    ) {
        events += "link:$url"
    }

    override fun textReceived(
        id: String,
        deviceId: String,
        text: String,
    ) {
        events += "text:$text"
    }

    override fun failed(
        id: String,
        deviceId: String,
        name: String,
        reason: String,
    ) {
        events += "failed:$name"
    }
}

/** Input stream that records whether it was closed. */
private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

class ShareReceiverTest {
    private val store = InMemoryTransferStore()
    private val transfers = TransferRepository(store) { 1_000L }
    private val endpoint = FakeEndpoint()
    private val storage = FakeStorage()
    private val notifier = RecordingNotifier()
    private var ids = 0
    private val receiver =
        ShareReceiver(
            endpoint,
            transfers,
            storage,
            notifier,
            scope = CoroutineScope(Dispatchers.Unconfined),
            now = { 1_000L },
            newId = { "r${ids++}" },
        )

    private fun rows(): List<TransferEntity> = runBlocking { store.observeAll().first() }

    private fun fileRequest(
        name: String,
        size: Long,
        count: Int = 1,
        lastModified: Long? = null,
    ) = ShareRequestBody.File(name, numberOfFiles = count, totalPayloadSize = size, lastModified = lastModified)

    private fun filePacket(
        body: ShareRequestBody.File,
        size: Long,
        port: Int = 1740,
    ) = body.toPacket(payloadSize = size, payloadPort = port)

    @Test
    fun fileIsStreamedIntoStorageWithItsOwnName() =
        runBlocking {
            endpoint.incoming = "hello world".toByteArray()
            val body = fileRequest("greeting.txt", 11)
            receiver.onRequest(DEVICE, filePacket(body, 11), body)

            val file = storage.created.single()
            assertEquals("greeting.txt", file.name)
            assertEquals("hello world", file.bytes.toString(Charsets.UTF_8))
            assertTrue(file.committed)
            assertEquals(1740, endpoint.receivedPort)
            val row = rows().single { it.kind == ShareKind.FILE }
            assertEquals(ShareState.DONE, row.state)
            assertEquals(11, row.doneBytes)
            assertEquals("content://downloads/greeting.txt", row.contentUri)
            assertEquals(listOf("receiving:greeting.txt", "file:greeting.txt"), notifier.events)
        }

    @Test
    fun modificationTimeIsPassedToCommit() =
        runBlocking {
            endpoint.incoming = ByteArray(3)
            val body = fileRequest("a.bin", 3, lastModified = 1_732_030_000_000L)
            receiver.onRequest(DEVICE, filePacket(body, 3), body)
            assertEquals(1_732_030_000_000L, storage.created.single().committedAt)
        }

    @Test
    fun hostileFilenamesNeverReachStorageAsPaths() =
        runBlocking {
            endpoint.incoming = ByteArray(1)
            for (raw in listOf("../../etc/passwd", "/abs/path.txt", "..\\win.exe", "..")) {
                val body = fileRequest(raw, 1)
                receiver.onRequest(DEVICE, filePacket(body, 1), body)
            }
            val names = storage.created.map { it.name }
            assertEquals(listOf("passwd", "path.txt", "win.exe", "shared-file"), names)
        }

    @Test
    fun storageErrorIsReportedAndThePartialFileIsRemoved() =
        runBlocking {
            endpoint.incoming = ByteArray(5)
            endpoint.receiveError = IOException("disk full")
            val body = fileRequest("big.iso", 5)
            receiver.onRequest(DEVICE, filePacket(body, 5), body)

            val file = storage.created.single()
            assertTrue(file.aborted)
            assertFalse(file.committed)
            val row = rows().single()
            assertEquals(ShareState.FAILED, row.state)
            assertEquals("disk full", row.error)
            assertEquals("failed:big.iso", notifier.events.last())
        }

    @Test
    fun cancelledTransferIsMarkedCancelledWithoutAFailureNotice() =
        runBlocking {
            endpoint.receiveError = TransferCancelledException()
            val body = fileRequest("x.bin", 9)
            receiver.onRequest(DEVICE, filePacket(body, 9), body)
            assertTrue(storage.created.single().aborted)
            assertEquals(ShareState.CANCELLED, rows().single().state)
            assertFalse(notifier.events.any { it.startsWith("failed") })
        }

    @Test
    fun fileWithoutPayloadIsIgnored() =
        runBlocking {
            val body = fileRequest("x", 1)
            receiver.onRequest(DEVICE, NetworkPacket(1, PacketTypes.SHARE_REQUEST, JsonObject(emptyMap())), body)
            assertTrue(storage.created.isEmpty())
            assertTrue(rows().isEmpty())
        }

    @Test
    fun linkAndTextAreRecordedWithoutAPayload() =
        runBlocking {
            val url = ShareRequestBody.Url("https://kde.org")
            val text = ShareRequestBody.Text("hello\nsecond line")
            receiver.onRequest(DEVICE, url.toPacket(), url)
            receiver.onRequest(DEVICE, text.toPacket(), text)

            val link = rows().single { it.kind == ShareKind.URL }
            assertEquals("https://kde.org", link.text)
            val note = rows().single { it.kind == ShareKind.TEXT }
            assertEquals("hello", note.name)
            assertEquals("hello\nsecond line", note.text)
            assertEquals(ShareState.DONE, note.state)
            assertEquals(listOf("link:https://kde.org", "text:hello\nsecond line"), notifier.events)
            assertTrue(storage.created.isEmpty())
        }

    @Test
    fun filesOfOneBatchShareABatchIdAndAnUpdateRaisesTheCount() =
        runBlocking {
            endpoint.incoming = ByteArray(2)
            // The first packet announces one file. An update then says three are coming.
            val first = fileRequest("a", 2, count = 1)
            receiver.handle(DEVICE, filePacket(first, 2))
            assertTrue(receiver.handle(DEVICE, ShareUpdateBody(3, 6).toPacket()))
            for (name in listOf("b", "c")) {
                val body = fileRequest(name, 2, count = 3)
                receiver.handle(DEVICE, filePacket(body, 2))
            }
            val fileRows = rows().filter { it.kind == ShareKind.FILE }
            assertEquals(3, fileRows.size)
            assertEquals(1, fileRows.map { it.batchId }.toSet().size)
        }

    @Test
    fun aNewBatchStartsAfterTheLastFileOfTheOldOne() =
        runBlocking {
            endpoint.incoming = ByteArray(1)
            val single = fileRequest("one", 1, count = 1)
            receiver.handle(DEVICE, filePacket(single, 1))
            receiver.handle(DEVICE, filePacket(fileRequest("two", 1, count = 1), 1))
            val batches = rows().map { it.batchId }.toSet()
            assertEquals(2, batches.size)
        }

    @Test
    fun unknownPacketTypesAreNotHandled() {
        val other = NetworkPacket(1, PacketTypes.BATTERY, JsonObject(emptyMap()))
        runBlocking { assertFalse(receiver.handle(DEVICE, other)) }
    }

    @Test
    fun sameNamesAreLeftToStorageToMakeUnique() =
        runBlocking {
            endpoint.incoming = ByteArray(1)
            val body = fileRequest("a.jpg", 1)
            receiver.onRequest(DEVICE, filePacket(body, 1), body)
            receiver.onRequest(DEVICE, filePacket(body, 1), body)
            assertEquals(2, storage.created.size)
        }
}

class ShareSenderTest {
    private val store = InMemoryTransferStore()
    private val transfers = TransferRepository(store) { 1_000L }
    private val endpoint = FakeEndpoint()
    private val peers = FakePeerSender()
    private var ids = 0
    private val sender = ShareSender(endpoint, transfers, peers, now = { 1_000L }, newId = { "s${ids++}" })

    private fun rows(): List<TransferEntity> = runBlocking { store.observeAll().first() }

    @Test
    fun textAndUrlGoAsPacketsAndAreRecorded() =
        runBlocking {
            assertTrue(sender.sendText(DEVICE, "copied"))
            assertTrue(sender.sendUrl(DEVICE, "https://kde.org"))
            assertEquals(
                listOf(PacketTypes.SHARE_REQUEST, PacketTypes.SHARE_REQUEST),
                peers.sent.map { it.second.type },
            )
            assertEquals(ShareRequestBody.Text("copied").toJson(), peers.sent[0].second.body)
            assertEquals(2, rows().size)
            assertTrue(rows().all { it.state == ShareState.DONE })
        }

    @Test
    fun textFailsWhenNoLinkAcceptsIt() =
        runBlocking {
            val offline = ShareSender(endpoint, transfers, FakePeerSender { false }, newId = { "x" })
            assertFalse(offline.sendText(DEVICE, "hi"))
            assertEquals(ShareState.FAILED, store.get("x")!!.state)
            assertEquals(ShareSender.NO_LINK, store.get("x")!!.error)
        }

    @Test
    fun filesGoAsOneBatchWithTheAnnouncedPortAndSizes() =
        runBlocking {
            val batch =
                sender.sendFiles(
                    DEVICE,
                    listOf(
                        OutgoingFile("a.txt", 4, null, ByteArrayInputStream("aaaa".toByteArray())),
                        OutgoingFile("b.txt", 8, null, ByteArrayInputStream("bbbbbbbb".toByteArray())),
                    ),
                )
            assertEquals(listOf("aaaa", "bbbbbbbb"), endpoint.sentBytes.map { it.toString(Charsets.UTF_8) })
            assertEquals(2, peers.sent.size)
            val first = peers.sent[0].second
            assertEquals(4L, first.payloadSize)
            assertEquals(1739, first.payloadPort)
            val body = ShareRequestBody.parse(first.body) as ShareRequestBody.File
            assertEquals("a.txt", body.filename)
            assertEquals(2, body.numberOfFiles)
            assertEquals(12L, body.totalPayloadSize)
            assertTrue(rows().all { it.batchId == batch && it.state == ShareState.DONE })
        }

    @Test
    fun unknownSizeSendsMinusOneAndOmitsTheTotal() =
        runBlocking {
            sender.sendFiles(DEVICE, listOf(OutgoingFile("s.bin", -1, null, ByteArrayInputStream(ByteArray(3)))))
            assertEquals(-1L, peers.sent.single().second.payloadSize)
            val body = ShareRequestBody.parse(peers.sent.single().second.body) as ShareRequestBody.File
            assertNull(body.totalPayloadSize)
        }

    @Test
    fun lostLinkFailsTheBatchAndClosesEveryStream() =
        runBlocking {
            endpoint.announces = false
            val streams = List(2) { TrackedInput("x".toByteArray()) }
            sender.sendFiles(DEVICE, streams.mapIndexed { i, s -> OutgoingFile("f$i", 1, null, s) })
            assertTrue(streams.all { it.closed })
            assertTrue(rows().all { it.state == ShareState.FAILED && it.error == ShareSender.NO_LINK })
        }

    @Test
    fun cancellingTheBatchStopsEveryFileBeforeTheWire() =
        runBlocking {
            // Batch id is the first id handed out: "s0". Cancel it while the first file is starting.
            endpoint.beforeSend = { index -> if (index == 0) transfers.cancelBatch("s0") }
            val batch =
                sender.sendFiles(
                    DEVICE,
                    listOf(
                        OutgoingFile("one", 1, null, ByteArrayInputStream(ByteArray(1))),
                        OutgoingFile("two", 1, null, ByteArrayInputStream(ByteArray(1))),
                    ),
                )
            assertEquals("s0", batch)
            assertTrue(endpoint.sentBytes.isEmpty())
            assertTrue(peers.sent.isEmpty())
            assertTrue(rows().all { it.state == ShareState.CANCELLED })
        }
}
