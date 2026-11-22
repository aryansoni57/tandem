// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** What texting from the laptop needs: read the messages, send new ones, and be told when one arrives. */
object SmsPermissions {
    val ALL =
        arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
        )

    fun isGranted(context: Context): Boolean =
        ALL.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
