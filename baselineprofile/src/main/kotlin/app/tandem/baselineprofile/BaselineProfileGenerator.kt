// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test

/**
 * Records the cold start and a walk across the four tabs. Run on a device with
 * `./gradlew :app:generateBaselineProfile`; the profile lands in `app/src/release/generated`.
 */
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndTabs() =
        rule.collect(packageName = "app.tandem", includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            for (label in listOf("Share", "Sync", "Remote", "Home")) {
                device.findObject(By.text(label))?.click()
                device.waitForIdle()
            }
        }
}
