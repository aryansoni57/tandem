// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.tandem.designsystem.theme.TandemTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One light and one dark image per screen, saved under `app/src/test/screenshots`.
 * Record with `./gradlew :app:recordRoborazziDebug`, compare with `:app:verifyRoborazziDebug`.
 * The seed palette is used, not wallpaper colors, so the images do not depend on the device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String) {
        val fixture = ScreenFixtures.all.first { it.name == name }
        // setContent runs once per test, so the theme flips through state between the two captures.
        var dark by mutableStateOf(false)
        compose.setContent { TandemTheme(dynamicColor = false, darkTheme = dark, content = fixture.content) }
        for (isDark in listOf(false, true)) {
            dark = isDark
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/${name}_${if (isDark) "dark" else "light"}.png")
        }
    }

    @Test fun home() = shoot("home")

    @Test fun share() = shoot("share")

    @Test fun sync() = shoot("sync")

    @Test fun clipboard() = shoot("clipboard")

    @Test fun remote() = shoot("remote")

    @Test fun remoteKeyboard() = shoot("remote_keyboard")

    @Test fun media() = shoot("media")

    @Test fun presenter() = shoot("presenter")

    @Test fun commands() = shoot("commands")

    @Test fun security() = shoot("security")

    @Test fun findMyPhone() = shoot("find_my_phone")
}
