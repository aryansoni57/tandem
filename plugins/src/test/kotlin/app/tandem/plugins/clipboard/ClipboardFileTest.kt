// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.plugins.FakeContext
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.ClipboardFileBody
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ClipboardFileTest {
    private class Endpoint(var fail: Boolean = false) : PayloadEndpoint {
        var port: Int? = null

        override suspend fun send(
            deviceId: String,
            size: Long,
            source: InputStream,
            control: TransferControl,
            progress: PayloadProgress,
            announce: suspend (port: Int) -> Boolean,
        ): Long? = null

        override suspend fun receive(
            deviceId: String,
            port: Int,
            size: Long,
            sink: OutputStream,
            control: TransferControl,
            progress: PayloadProgress,
        ): Long {
            this.port = port
            if (fail) error("socket closed")
            sink.write(ByteArray(size.toInt()) { 7 })
            return size
        }
    }

    private class Store : ClipboardFileStore {
        var name: String? = null
        val out = ByteArrayOutputStream()
        var aborted = false

        override fun create(name: String): StagedClipFile {
            this.name = name
            return object : StagedClipFile {
                override val stream: OutputStream = out
                override val uri = "content://test/$name"
                override val mime = "image/png"

                override fun abort() {
                    aborted = true
                }
            }
        }
    }

    private class Sys : SystemClipboard {
        val files = mutableListOf<Pair<String, String>>()

        override suspend fun readText(skipSensitive: Boolean): String? = null

        override suspend fun writeText(text: String) = Unit

        override suspend fun writeFile(
            uri: String,
            mime: String,
        ) {
            files += uri to mime
        }
    }

    private object NoHistory : ClipHistory {
        override suspend fun record(
            text: String,
            deviceId: String?,
            at: Long,
        ) = Unit
    }

    private fun plugin(
        endpoint: Endpoint,
        store: Store,
        sys: Sys,
    ) = ClipboardPlugin(
        FakeContext(setOf(PacketTypes.CLIPBOARD_FILE)).context,
        ClipboardStore(),
        sys,
        NoHistory,
        endpoint,
        store,
    )

    private fun packet(name: String) = ClipboardFileBody(name).toPacket(id = 1, payloadSize = 4, payloadPort = 1745)

    @Test
    fun fileGoesToClipboardWithSanitizedName() =
        runBlocking {
            val endpoint = Endpoint()
            val store = Store()
            val sys = Sys()
            assertTrue(plugin(endpoint, store, sys).onPacket(packet("../evil/pic.png")))
            assertEquals(1745, endpoint.port)
            assertEquals(4, store.out.size())
            assertEquals(false, store.name!!.contains('/'))
            assertEquals(listOf("content://test/${store.name}" to "image/png"), sys.files)
        }

    @Test
    fun failedReceiveAbortsAndLeavesClipboardAlone() =
        runBlocking {
            val store = Store()
            val sys = Sys()
            plugin(Endpoint(fail = true), store, sys).onPacket(packet("pic.png"))
            assertTrue(store.aborted)
            assertTrue(sys.files.isEmpty())
        }

    @Test
    fun packetWithoutPayloadIsIgnored() =
        runBlocking {
            val store = Store()
            val sys = Sys()
            val bare = ClipboardFileBody("pic.png").toPacket(id = 1)
            assertTrue(plugin(Endpoint(), store, sys).onPacket(bare))
            assertEquals(null, store.name)
        }
}
