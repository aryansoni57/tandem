// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.designsystem.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Gives a control the name TalkBack reads for it. A switch sitting beside its title is a separate element, so
 * without this it is announced as just "switch". Use the same words as the visible title.
 */
fun Modifier.accessibleName(label: String): Modifier = semantics { contentDescription = label }
