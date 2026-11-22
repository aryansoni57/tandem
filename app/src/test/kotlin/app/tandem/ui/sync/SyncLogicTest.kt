// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.sync

import app.tandem.sample.SampleData
import app.tandem.state.SyncCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncLogicTest {
    @Test
    fun categoryToggleAddsAndRemoves() {
        val once = SyncCategoryFilter.toggle(emptySet(), SyncCategory.Work)
        assertEquals(setOf(SyncCategory.Work), once)
        assertEquals(emptySet<SyncCategory>(), SyncCategoryFilter.toggle(once, SyncCategory.Work))
    }

    @Test
    fun emptySelectionShowsAllApps() {
        assertEquals(SampleData.syncApps, SyncCategoryFilter.visible(SampleData.syncApps, emptySet()))
    }

    @Test
    fun selectedCategoriesFilterApps() {
        val names =
            SyncCategoryFilter.visible(
                SampleData.syncApps,
                setOf(SyncCategory.Messaging, SyncCategory.Work),
            ).map {
                it.name
            }
        assertEquals(listOf("Messages", "Mail", "Calendar"), names)
    }

    @Test
    fun segmentsDefaultToNotifications() {
        assertEquals(SyncTab.Notifications, SyncTab.entries.first())
    }
}
