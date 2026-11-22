// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.tandem.core.device.DeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service of type connectedDevice. It keeps the links alive with the screen off.
 * It is started from the UI, from boot, or from the network callback while already running.
 */
@AndroidEntryPoint
class ConnectionService : Service() {
    @Inject lateinit var deviceManager: DeviceManager

    @Inject lateinit var networkMonitor: NetworkMonitor

    @Inject lateinit var nsdDiscovery: NsdDiscovery

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForegroundSafely()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundSafely()
        scope.launch {
            deviceManager.start()
            networkMonitor.start(scope) { deviceManager.announce() }
            deviceManager.localService()?.let { nsdDiscovery.start(it) }
            deviceManager.announce()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkMonitor.stop()
        nsdDiscovery.stop()
        deviceManager.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val notification = ongoingNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "connection"
        private const val TAG = "Tandem"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ refuses a foreground start from a background process
                // (ForegroundServiceStartNotAllowedException).
                // The service starts on the next foreground launch instead.
                Log.w(TAG, "connection service not started from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN),
            )
        }

        private fun ongoingNotification(context: Context): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Tandem is running")
                .setContentText("Keeping devices connected on this network")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
    }
}
