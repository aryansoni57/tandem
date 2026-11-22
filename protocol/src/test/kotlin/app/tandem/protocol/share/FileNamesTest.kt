// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun plainNameIsKept() {
        assertEquals("photo 1.jpg", FileNames.sanitize("photo 1.jpg"))
    }

    @Test
    fun traversalIsRemoved() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.sanitize("..\\..\\evil.sh"))
    }

    @Test
    fun absolutePathsKeepOnlyTheLeaf() {
        assertEquals("notes.txt", FileNames.sanitize("/data/data/app/notes.txt"))
        assertEquals("win.txt", FileNames.sanitize("C:\\Users\\me\\win.txt"))
    }

    @Test
    fun dotNamesAreRejected() {
        assertEquals(FileNames.FALLBACK, FileNames.sanitize(".."))
        assertEquals(FileNames.FALLBACK, FileNames.sanitize("."))
        assertEquals(FileNames.FALLBACK, FileNames.sanitize(""))
        assertEquals(FileNames.FALLBACK, FileNames.sanitize("/"))
        assertEquals(FileNames.FALLBACK, FileNames.sanitize(null))
    }

    @Test
    fun hiddenAndTrailingDotsAreRemoved() {
        assertEquals("bashrc", FileNames.sanitize(".bashrc"))
        assertEquals("name", FileNames.sanitize("name. "))
    }

    @Test
    fun oddCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d.txt", FileNames.sanitize("a<b>c:d.txt"))
        assertEquals("tab_here.txt", FileNames.sanitize("tab\there.txt"))
        assertEquals("q_star_.txt", FileNames.sanitize("q?star*.txt"))
    }

    @Test
    fun unicodeIsKept() {
        assertEquals("résumé 日本.pdf", FileNames.sanitize("résumé 日本.pdf"))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val result = FileNames.sanitize("x".repeat(400) + ".mp4")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_BYTES)
        assertTrue(result.endsWith(".mp4"))
    }

    @Test
    fun longMultibyteNamesStayValidUtf8() {
        val result = FileNames.sanitize("日".repeat(200) + ".txt")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_BYTES)
        assertTrue(result.endsWith(".txt"))
        assertFalse(result.contains('\uFFFD'))
    }

    @Test
    fun duplicatesGetNumberedNames() {
        val taken = setOf("a.jpg", "a (1).jpg")
        assertEquals("a (2).jpg", FileNames.uniqueName("a.jpg") { it in taken })
        assertEquals("free.jpg", FileNames.uniqueName("free.jpg") { it in taken })
    }

    @Test
    fun duplicatesWithoutExtensionGetNumberedNames() {
        assertEquals("README (1)", FileNames.uniqueName("README") { it == "README" })
    }
}
