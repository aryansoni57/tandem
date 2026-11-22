// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tandem.clipboard.SendClipboardActivity

/** Quick-settings tile: sends the clipboard to connected devices through [SendClipboardActivity]. */
class SendClipboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    // Below API 34 the only overload takes an Intent. The Intent overload is deprecated from 34 on.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent =
            Intent(this, SendClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
