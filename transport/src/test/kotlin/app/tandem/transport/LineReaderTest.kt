// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineReaderTest {
    @Test
    fun readsLinesAndSkipsEmptyOnes() {
        val reader = LineReader(ByteArrayInputStream("a\n\nb\r\n".toByteArray()), maxLineBytes = 100)
        assertEquals("a", reader.readLine())
        assertEquals("b", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun unbufferedReaderDoesNotConsumeBytesAfterTheNewline() {
        val input =
            ByteArrayInputStream(byteArrayOf('h'.code.toByte(), 'i'.code.toByte(), '\n'.code.toByte(), 0x16, 0x03))
        val reader = LineReader(input, maxLineBytes = 100, buffered = false)
        assertEquals("hi", reader.readLine())
        assertEquals(0x16, input.read(), "a TLS record byte must still be in the stream")
    }

    @Test
    fun overlongLineIsDroppedAndTheConnectionContinues() {
        val reader = LineReader(ByteArrayInputStream(("x".repeat(50) + "\nok\n").toByteArray()), maxLineBytes = 10)
        assertEquals("ok", reader.readLine())
    }

    @Test
    fun timeoutKeepsThePartialLine() {
        val input =
            object : InputStream() {
                private val script = ArrayDeque<Any>(listOf('a'.code, SocketTimeoutException(), 'b'.code, '\n'.code))

                override fun read(): Int {
                    return when (val next = script.removeFirst()) {
                        is SocketTimeoutException -> throw next
                        is Int -> next
                        else -> error("unreachable")
                    }
                }
            }
        val reader = LineReader(input, maxLineBytes = 100)
        var line: String? = null
        var timeouts = 0
        while (line == null) {
            try {
                line = reader.readLine()
            } catch (_: SocketTimeoutException) {
                timeouts++
            }
        }
        assertEquals("ab", line)
        assertEquals(1, timeouts)
    }

    @Test
    fun endOfStreamMidLineReturnsNull() {
        val reader = LineReader(ByteArrayInputStream("partial".toByteArray()), maxLineBytes = 100)
        assertNull(reader.readLine())
    }
}
