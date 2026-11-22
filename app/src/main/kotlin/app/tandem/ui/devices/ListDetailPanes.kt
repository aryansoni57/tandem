// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The screen width, in dp, from which a list and its detail sit side by side. */
const val LIST_DETAIL_MIN_WIDTH_DP = 720

/**
 * A list on the left and the chosen item's detail on the right, for tablets and unfolded foldables. With no
 * item chosen the right side shows [placeholder]. On narrow screens the caller does not use this: the list
 * opens the detail as its own screen.
 */
@Composable
fun ListDetailPanes(
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(LIST_WEIGHT)) { list() }
        VerticalDivider()
        Box(modifier = Modifier.weight(DETAIL_WEIGHT), contentAlignment = Alignment.Center) {
            if (detail != null) {
                detail()
            } else {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}

private const val LIST_WEIGHT = 0.4f
private const val DETAIL_WEIGHT = 0.6f
