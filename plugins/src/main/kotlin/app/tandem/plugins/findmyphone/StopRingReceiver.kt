// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.findmyphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The "Stop ringing" button in the notification. */
@AndroidEntryPoint
class StopRingReceiver : BroadcastReceiver() {
    @Inject lateinit var ringer: PhoneRinger

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ringer.stop()
    }
}
