// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tandem.core.trust.TrustStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restarts the connection service after boot when at least one device is paired. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var trust: TrustStore

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (trust.hasAny()) ConnectionService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
