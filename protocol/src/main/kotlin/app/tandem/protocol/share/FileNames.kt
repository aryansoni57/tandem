// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.share

/**
 * Turns a filename from a peer into a safe leaf name. The peer is untrusted, so the result never
 * contains a path separator, never starts with a dot, and never escapes the target folder.
 */
object FileNames {
    const val FALLBACK = "shared-file"

    /** Longest allowed name in UTF-8 bytes. Most file systems allow 255. */
    const val MAX_BYTES = 200

    private const val MAX_EXTENSION_CHARS = 16
    private val FORBIDDEN = setOf('<', '>', ':', '"', '|', '?', '*')

    /**
     * Returns a leaf name. Steps: keep only the part after the last `/` or `\`, replace control
     * characters and characters that are invalid on common file systems with `_`, drop leading
     * dots and trailing dots or spaces, then cap the length while keeping a short extension.
     */
    fun sanitize(raw: String?): String {
        if (raw == null) return FALLBACK
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            buildString {
                for (ch in leaf) {
                    append(if (ch.code < 0x20 || ch.code == 0x7f || ch in FORBIDDEN) '_' else ch)
                }
            }
        val trimmed = cleaned.trim().trimStart('.').trimEnd('.', ' ')
        if (trimmed.isEmpty() || trimmed.all { it == '_' }) return FALLBACK
        return truncate(trimmed)
    }

    /**
     * Returns [name] when [exists] says it is free, else "base (n).ext" with the first free n from 1.
     */
    fun uniqueName(
        name: String,
        exists: (String) -> Boolean,
    ): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && name.length - dot - 1 in 1..MAX_EXTENSION_CHARS
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    private fun truncate(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && name.length - dot - 1 in 1..MAX_EXTENSION_CHARS
        val ext = if (hasExtension) name.substring(dot) else ""
        val base = if (hasExtension) name.substring(0, dot) else name
        val budget = MAX_BYTES - ext.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        var used = 0
        for (cp in base.codePoints().toArray()) {
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            sb.appendCodePoint(cp)
            used += size
        }
        return sb.toString().ifEmpty { FALLBACK } + ext
    }
}
