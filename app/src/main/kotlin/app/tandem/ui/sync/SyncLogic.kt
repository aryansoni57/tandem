// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.sync

import app.tandem.state.SyncApp
import app.tandem.state.SyncCategory

/** The two segments of the Sync screen. Exactly one is selected at a time. */
enum class SyncTab { Notifications, CallsAndSms }

/** Multi-select category chips. An empty selection shows every app. */
object SyncCategoryFilter {
    fun toggle(
        selected: Set<SyncCategory>,
        category: SyncCategory,
    ): Set<SyncCategory> = if (category in selected) selected - category else selected + category

    fun visible(
        apps: List<SyncApp>,
        selected: Set<SyncCategory>,
    ): List<SyncApp> = if (selected.isEmpty()) apps else apps.filter { it.category in selected }
}
