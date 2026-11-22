// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * What call alerts need. The phone state and call log permissions are required: the first delivers the call
 * broadcast and the second gives the caller's number. Contacts only add a name and photo.
 */
object CallPermissions {
    val ALL =
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )

    fun isGranted(context: Context): Boolean =
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}
