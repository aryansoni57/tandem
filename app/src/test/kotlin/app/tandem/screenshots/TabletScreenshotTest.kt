// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.tandem.core.device.BatteryState
import app.tandem.core.device.DeviceState
import app.tandem.designsystem.theme.TandemTheme
import app.tandem.ui.devices.DeviceDetailScreen
import app.tandem.ui.devices.ListDetailPanes
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Devices list and its detail side by side on a 1280 x 800 dp tablet, with and without a chosen device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class TabletScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val laptop = DeviceState(deviceId = "abc", name = "Aryan's laptop", paired = true, reachable = true)

    @Test
    fun devicesListAndDetail() {
        var chosen by mutableStateOf(false)
        compose.setContent {
            TandemTheme(dynamicColor = false) {
                ListDetailPanes(
                    list = { ScreenFixtures.devicesList() },
                    detail =
                        if (chosen) {
                            { DeviceDetailScreen(laptop, BatteryState(64, true), null, {}, {}, {}, {}, {}) }
                        } else {
                            null
                        },
                    placeholder = "Choose a device to see its details.",
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/tablet/devices_nothing_chosen.png")
        chosen = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/tablet/devices_detail.png")
    }
}
