// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.ping

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.tandem.core.device.DeviceManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts a notification for each incoming ping. Silent when notifications are not allowed. */
@Singleton
class AndroidPingSink
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deviceManager: Lazy<DeviceManager>,
    ) : PingSink {
        // The POST_NOTIFICATIONS check above covers the notify call, which lint cannot see through.
        @SuppressLint("MissingPermission")
        override fun onPing(
            deviceId: String,
            message: String?,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel(manager)
            val name = deviceManager.get().deviceStates.value.firstOrNull { it.deviceId == deviceId }?.name ?: deviceId
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Ping from $name")
                    .setContentText(message ?: "Ping")
                    .setAutoCancel(true)
                    .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun ensureChannel(manager: NotificationManagerCompat) {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pings", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        companion object {
            const val CHANNEL_ID = "pings"
            const val NOTIFICATION_ID = 2002
            private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        }
    }
