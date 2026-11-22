// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.clipboard

import app.tandem.state.ClipItem
import app.tandem.state.ClipSource

/** Filter chips on the clipboard screen. */
enum class ClipFilter { All, FromLaptop, FromPhone }

/** Applies the chip and the search text. The search is case-insensitive and ignores blank queries. */
fun List<ClipItem>.filterClips(
    filter: ClipFilter,
    query: String,
): List<ClipItem> {
    val needle = query.trim()
    return filter { item ->
        val sourceMatches =
            when (filter) {
                ClipFilter.All -> true
                ClipFilter.FromLaptop -> item.source == ClipSource.Laptop
                ClipFilter.FromPhone -> item.source == ClipSource.Phone
            }
        sourceMatches && (needle.isEmpty() || item.text.contains(needle, ignoreCase = true))
    }
}
