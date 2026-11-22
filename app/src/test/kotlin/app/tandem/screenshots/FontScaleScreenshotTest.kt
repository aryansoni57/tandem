// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.tandem.designsystem.theme.TandemTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every screen at 200% font size, the largest setting, to catch clipped or overlapping text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class FontScaleScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyScreenAtTwiceTheFontSize() {
        var current by mutableStateOf(ScreenFixtures.all.first())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                TandemTheme(dynamicColor = false) { current.content() }
            }
        }
        for (fixture in ScreenFixtures.all) {
            current = fixture
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/fontscale/${fixture.name}.png")
        }
    }
}
