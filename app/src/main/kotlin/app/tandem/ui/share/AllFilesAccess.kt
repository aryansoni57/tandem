// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Sharing storage needs "all files access", a special permission the user grants in system settings. */
object AllFilesAccess {
    /** The settings page for this app, or the general list on the rare phone that lacks it. */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
