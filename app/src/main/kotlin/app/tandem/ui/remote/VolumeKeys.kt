// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import android.view.KeyEvent

/**
 * Lets one screen take the volume keys. While [handler] is set, volume down means next and volume up
 * means previous, and the system volume does not change. [MainActivity] asks this before it passes a key on.
 */
object VolumeKeys {
    @Volatile var handler: ((next: Boolean) -> Unit)? = null

    /** Returns true when the key was taken. Both the press and the release are taken, so no volume bar shows. */
    fun onKey(event: KeyEvent): Boolean {
        val take = handler ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            take(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        }
        return true
    }
}
