// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.transfer

import app.tandem.transport.TransferControl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRepositoryTest {
    private var clock = 1_000L
    private val store = InMemoryTransferStore()
    private val repo = TransferRepository(store) { clock }

    private fun file(
        id: String,
        batch: String = "b1",
        name: String = "$id.bin",
        total: Long = 100,
        device: String = "dev1",
    ) = TransferEntity(
        id = id,
        batchId = batch,
        deviceId = device,
        outgoing = false,
        kind = ShareKind.FILE,
        name = name,
        mime = null,
        totalBytes = total,
        doneBytes = 0,
        state = ShareState.RUNNING,
        contentUri = null,
        text = null,
        error = null,
        createdAt = clock,
        updatedAt = clock,
    )

    @Test
    fun completedFileKeepsItsUriAndFullSize() =
        runBlocking {
            repo.begin(file("f1"), TransferControl())
            repo.progress("f1", 40, 100)
            repo.complete("f1", contentUri = "content://media/downloads/7")

            val row = store.get("f1")!!
            assertEquals(ShareState.DONE, row.state)
            assertEquals(100, row.doneBytes)
            assertEquals("content://media/downloads/7", row.contentUri)
        }

    @Test
    fun liveProgressShowsOnlyWhileRunning() =
        runBlocking {
            repo.begin(file("f1"), TransferControl())
            repo.progress("f1", 40, 100)
            assertEquals(40, repo.observe().first().single().doneBytes)

            repo.fail("f1", "link dropped")
            val failed = repo.observe().first().single()
            assertEquals(ShareState.FAILED, failed.state)
            assertEquals("link dropped", failed.error)
            assertEquals(0, failed.doneBytes)
        }

    @Test
    fun cancelFlipsTheControlAndMarksTheItem() =
        runBlocking {
            val control = TransferControl()
            repo.begin(file("f1"), control)
            repo.cancel("f1")
            assertTrue(control.isCancelled)
            repo.markCancelled("f1")
            assertEquals(ShareState.CANCELLED, store.get("f1")!!.state)
        }

    @Test
    fun cancelBatchCancelsEveryRunningItemOfThatBatchOnly() =
        runBlocking {
            val a = TransferControl()
            val b = TransferControl()
            val other = TransferControl()
            repo.begin(file("a", batch = "x"), a)
            repo.begin(file("b", batch = "x"), b)
            repo.begin(file("c", batch = "y"), other)
            repo.cancelBatch("x")
            assertTrue(a.isCancelled)
            assertTrue(b.isCancelled)
            assertFalse(other.isCancelled)
        }

    @Test
    fun textAndUrlRowsAreStoredFinished() =
        runBlocking {
            val url =
                TransferEntity(
                    id = "u1", batchId = "u1", deviceId = "dev1", outgoing = true, kind = ShareKind.URL,
                    name = "https://kde.org", mime = null, totalBytes = 0, doneBytes = 0, state = ShareState.RUNNING,
                    contentUri = null, text = "https://kde.org", error = null, createdAt = clock, updatedAt = clock,
                )
            repo.recordFinished(url)
            assertEquals(ShareState.DONE, store.get("u1")!!.state)
            repo.recordFinished(url.copy(id = "u2", batchId = "u2"), failed = "no link")
            assertEquals(ShareState.FAILED, store.get("u2")!!.state)
            assertEquals("no link", store.get("u2")!!.error)
        }

    @Test
    fun interruptedRunningItemsBecomeFailedAtStartup() =
        runBlocking {
            // Written by a previous process: no control registered.
            store.put(file("old"))
            repo.begin(file("live"), TransferControl())
            assertEquals(1, repo.recoverInterrupted())
            assertEquals(ShareState.FAILED, store.get("old")!!.state)
            assertEquals(TransferRepository.INTERRUPTED, store.get("old")!!.error)
            assertEquals(ShareState.RUNNING, store.get("live")!!.state)
        }

    @Test
    fun cancelOfAnUnknownItemIsIgnored() =
        runBlocking {
            repo.cancel("missing")
            repo.cancelBatch("missing")
            assertNull(store.get("missing"))
        }

    @Test
    fun batchSummaryAddsUpFilesAndKeepsUnknownSizeUnknown() {
        val items =
            listOf(
                file("a", total = 100).copy(state = ShareState.DONE, doneBytes = 100),
                file("b", total = 300).copy(doneBytes = 50),
            )
        val summary = groupBatches(items).single()
        assertEquals("2 files", summary.name)
        assertEquals(400, summary.totalBytes)
        assertEquals(150, summary.doneBytes)
        assertEquals(ShareState.RUNNING, summary.state)
        assertEquals(0.375f, summary.fraction)

        val unknown = groupBatches(listOf(file("c", total = -1))).single()
        assertEquals(-1, unknown.totalBytes)
        assertNull(unknown.fraction)
    }

    @Test
    fun batchesAreNewestFirstAndTextBatchesHaveNoSize() {
        val old = file("old", batch = "b-old").copy(createdAt = 1)
        val new = file("new", batch = "b-new").copy(createdAt = 5, state = ShareState.DONE)
        val text =
            TransferEntity(
                id = "t", batchId = "b-text", deviceId = "dev1", outgoing = true, kind = ShareKind.TEXT,
                name = "hello", mime = null, totalBytes = 0, doneBytes = 0, state = ShareState.DONE,
                contentUri = null, text = "hello", error = null, createdAt = 9, updatedAt = 9,
            )
        val batches = groupBatches(listOf(old, new, text))
        assertEquals(listOf("b-text", "b-new", "b-old"), batches.map { it.batchId })
        assertEquals(0, batches.first().totalBytes)
        assertEquals("hello", batches.first().text)
        assertEquals(ShareState.DONE, batches[1].state)
    }

    @Test
    fun batchStateReportsFailureWhenAnyItemFailed() {
        val items =
            listOf(
                file("a").copy(state = ShareState.DONE),
                file("b").copy(state = ShareState.FAILED, error = "x"),
            )
        assertEquals(ShareState.FAILED, groupBatches(items).single().state)
    }
}
