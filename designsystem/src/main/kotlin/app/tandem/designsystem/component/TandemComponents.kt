// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Subhead used between list groups, in the primary role like the mockup. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Round or square tile behind a list leading icon or initials. */
@Composable
fun LeadingTile(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    square: Boolean = false,
    icon: ImageVector? = null,
    initials: String? = null,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = if (square) RoundedCornerShape(12.dp) else androidx.compose.foundation.shape.CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                icon != null -> Icon(icon, contentDescription = contentDescription)
                initials != null -> Text(initials, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** One list row with an optional click. Uses M3 [ListItem] with transparent container. */
@Composable
fun TandemListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    overline: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        modifier =
            modifier.then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            ),
        overlineContent = overline?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Small pill with an optional icon, used for tags such as Verified, SMS and Missed. */
@Composable
fun TandemTag(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Icon above a label on a tonal pill, used for the Home quick actions. */
@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(width = 64.dp, height = 56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Bar chart for a battery history. Values are fractions 0..1, the last bar is the current one. */
@Composable
fun BatteryBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    val current = MaterialTheme.colorScheme.primary
    val past = MaterialTheme.colorScheme.primaryContainer
    val gap = 4.dp
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        if (values.isEmpty()) return@Canvas
        val gapPx = gap.toPx()
        val barWidth = (size.width - gapPx * (values.size - 1)) / values.size
        val radius = CornerRadius(4.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = size.height * v.coerceIn(0f, 1f)
            drawRoundRect(
                color = if (i == values.lastIndex) current else past,
                topLeft = Offset(i * (barWidth + gapPx), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}

/** Inline banner with an icon, a message and up to two text actions. */
@Composable
fun PermissionBanner(
    icon: ImageVector,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Row {
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                    }
                    TextButton(onClick = onPrimary) { Text(primaryLabel) }
                }
            }
        }
    }
}

/** Certificate or key fingerprint in monospace, split into lines as given. */
@Composable
fun FingerprintText(
    fingerprint: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = fingerprint,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

/** Two-button confirm dialog. [destructive] colors the confirm action with the error role. */
@Composable
fun TandemConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor =
                            if (destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
