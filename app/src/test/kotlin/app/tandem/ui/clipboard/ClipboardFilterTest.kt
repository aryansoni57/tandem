// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.clipboard

import app.tandem.sample.SampleData
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardFilterTest {
    private val clips = SampleData.clips

    @Test
    fun allShowsEveryItemWithoutQuery() {
        assertEquals(clips, clips.filterClips(ClipFilter.All, ""))
    }

    @Test
    fun laptopAndPhoneChipsSplitBySource() {
        assertEquals(
            listOf(
                "npm i @tandem/cli",
                "https://docs.example.com/spec-v2",
            ),
            clips.filterClips(ClipFilter.FromLaptop, "").map {
                it.text
            },
        )
        assertEquals(
            listOf(
                "Gate B14, boarding 18:20",
                "+91 98765 43210",
            ),
            clips.filterClips(ClipFilter.FromPhone, "").map {
                it.text
            },
        )
    }

    @Test
    fun searchIsCaseInsensitiveAndCombinesWithChip() {
        assertEquals(listOf("Gate B14, boarding 18:20"), clips.filterClips(ClipFilter.All, "gate").map { it.text })
        assertEquals(emptyList<String>(), clips.filterClips(ClipFilter.FromLaptop, "gate").map { it.text })
    }

    @Test
    fun blankQueryIsIgnored() {
        assertEquals(clips.size, clips.filterClips(ClipFilter.All, "   ").size)
    }
}
