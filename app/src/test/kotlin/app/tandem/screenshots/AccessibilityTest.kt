// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import app.tandem.designsystem.theme.TandemTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Checks every screen for two things that decide whether TalkBack and touch work: each tappable element has a
 * name (its text or a content description), and each is at least 48 dp in both directions. The Google
 * accessibility framework cannot inspect Compose under Robolectric, so these rules read the semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private val minDp = 48f

    @Test
    fun everyTappableElementHasANameAndIsBigEnough() {
        var current by mutableStateOf(ScreenFixtures.all.first())
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            TandemTheme(dynamicColor = false) { current.content() }
        }
        val problems = mutableListOf<String>()
        for (fixture in ScreenFixtures.all) {
            current = fixture
            compose.waitForIdle()
            val nodes = compose.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()
            for (node in nodes) {
                val config = node.config
                val label =
                    (config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() +
                        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()).trim()
                // The touch bounds include the padding Material adds to reach 48 dp, which the visual size does not.
                val widthDp = node.touchBoundsInRoot.width / density
                val heightDp = node.touchBoundsInRoot.height / density
                val role = config.getOrNull(SemanticsProperties.Role)
                val id = "${fixture.name}: ${role ?: "node"} \"$label\" ${widthDp.toInt()}x${heightDp.toInt()}dp"
                if (label.isEmpty() && !hasLabelInTree(node)) problems += "$id has no name"
                if ((widthDp < minDp || heightDp < minDp) && role != Role.Tab) problems += "$id is under 48dp"
            }
        }
        check(problems.isEmpty()) { "Accessibility problems:\n" + problems.distinct().joinToString("\n") }
    }

    /** A tappable container (a row, a card) is named by the text of what it holds. */
    private fun hasLabelInTree(node: androidx.compose.ui.semantics.SemanticsNode): Boolean =
        node.children.any { child ->
            child.config.getOrNull(SemanticsProperties.Text) != null ||
                child.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
                hasLabelInTree(child)
        }

    private fun isToggleable() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
}
