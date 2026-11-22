// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tandem.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launcher icon as the system draws it (background and foreground layers), saved for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class IconScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun launcherIcon() {
        compose.setContent {
            AndroidView(
                factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_launcher) } },
                modifier = Modifier.size(216.dp),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/launcher_icon.png")
    }
}
