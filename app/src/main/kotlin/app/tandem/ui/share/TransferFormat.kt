// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tandem.R
import app.tandem.designsystem.icon.TandemIcons
import app.tandem.state.Transfer
import app.tandem.state.TransferDirection
import app.tandem.state.TransferKind
import app.tandem.state.TransferStatus

/** Icon that matches the kind of file or link. */
fun TransferKind.icon(): ImageVector =
    when (this) {
        TransferKind.Video -> TandemIcons.SmartDisplay
        TransferKind.Image -> TandemIcons.Image
        TransferKind.Document -> TandemIcons.Description
        TransferKind.Link -> TandemIcons.Link
    }

/** Size as the mockup shows it: one decimal under 10 MB, whole numbers above. */
fun formatSizeMb(sizeMb: Float): String = if (sizeMb >= 10f) "${sizeMb.toInt()} MB" else "%.1f MB".format(sizeMb)

/** Second line of a transfer row, in the user's language. */
@Composable
fun Transfer.supportingText(): String {
    val size = sizeMb?.let { formatSizeMb(it) }
    return when {
        status == TransferStatus.Cancelled -> stringResource(R.string.transfer_cancelled)
        status == TransferStatus.Failed -> stringResource(R.string.transfer_failed)
        kind == TransferKind.Link && direction == TransferDirection.ToLaptop ->
            stringResource(
                R.string.transfer_link_sent,
            )
        kind == TransferKind.Link -> stringResource(R.string.transfer_link_opened)
        progress != null && direction == TransferDirection.ToLaptop ->
            stringResource(R.string.transfer_sending, size.orEmpty(), (progress * 100).toInt())
        progress != null -> stringResource(R.string.transfer_receiving, size.orEmpty(), (progress * 100).toInt())
        direction == TransferDirection.ToLaptop && size != null -> stringResource(R.string.transfer_sent, size)
        size != null -> stringResource(R.string.transfer_received, size)
        else -> stringResource(R.string.transfer_received_plain)
    }
}

@Composable
fun shareItemsLabel(count: Int): String = pluralStringResource(R.plurals.share_items_count, count, count)
