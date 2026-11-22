// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads newline-terminated UTF-8 lines.
 *
 * - [buffered] = false reads one byte at a time, so nothing past the newline is consumed.
 *   Use it before TLS: a buffered reader would swallow the start of the TLS ClientHello.
 * - Empty lines are skipped. Lines longer than [maxLineBytes] are dropped in full and the
 *   connection stays open.
 * - A read timeout keeps the partial line, so the next call resumes where it stopped.
 */
class LineReader(
    input: InputStream,
    private val maxLineBytes: Int,
    buffered: Boolean = false,
) {
    private val stream: InputStream = if (buffered) BufferedInputStream(input) else input
    private val partial = ByteArrayOutputStream()
    private var overflow = false

    /** Returns the next non-empty line without its terminator, or null at end of stream. */
    fun readLine(): String? {
        while (true) {
            val b = stream.read()
            if (b < 0) {
                // A line cut off by end of stream is discarded.
                return null
            }
            val line = if (b == '\n'.code) endLine() else accept(b)
            if (line != null) return line
        }
    }

    /** Closes the current line. Returns its text, or null when it was empty or too long. */
    private fun endLine(): String? {
        val dropped = overflow
        val bytes = partial.toByteArray()
        partial.reset()
        overflow = false
        if (dropped) return null
        return String(bytes, Charsets.UTF_8).trimEnd('\r').takeIf { it.isNotEmpty() }
    }

    /** Adds one byte to the current line. Always returns null: only a newline completes a line. */
    private fun accept(b: Int): String? {
        if (overflow) return null
        if (partial.size() >= maxLineBytes) {
            overflow = true
            partial.reset()
            return null
        }
        partial.write(b)
        return null
    }
}
