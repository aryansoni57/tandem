// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Needs notification access (granted by the user in system settings). Forwards posts and removals. */
@AndroidEntryPoint
class TandemNotificationListener : NotificationListenerService() {
    @Inject lateinit var bridge: NotificationBridge

    @Inject lateinit var source: AndroidNotificationSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        source.listener = this
    }

    override fun onListenerDisconnected() {
        source.listener = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { bridge.onPosted(source.snapshot(sbn)) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        scope.launch { bridge.onRemoved(sbn.key) }
    }

    override fun onDestroy() {
        source.listener = null
        scope.cancel()
        super.onDestroy()
    }
}
