// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.tandem.core.device.DeviceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receive-side notifications. Nothing opens by itself: a link opens, and a text is copied, only when
 * the user taps the notification or its action. Progress shows while a file arrives.
 */
@Singleton
class ShareNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        // Lazy: DeviceManager reaches this class through the plugin registry, so injecting it directly is a cycle.
        private val devices: dagger.Lazy<DeviceManager>,
    ) : ShareNotifier {
        init {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        override fun receiving(
            id: String,
            deviceId: String,
            name: String,
            done: Long,
            total: Long,
        ) {
            val percent = if (total > 0) ((done * PERCENT) / total).toInt().coerceIn(0, PERCENT) else 0
            val builder =
                base()
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Receiving $name")
                    .setContentText("From ${deviceName(deviceId)}")
                    .setProgress(PERCENT, percent, total < 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
            post(id, builder.build())
        }

        override fun fileReceived(
            id: String,
            deviceId: String,
            name: String,
            uri: String?,
            mime: String,
        ) {
            val builder =
                base()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Received $name")
                    .setContentText("From ${deviceName(deviceId)} · Download/Tandem")
            if (uri != null) {
                val open = viewIntent(uri.toUri(), mime)
                builder.setContentIntent(activityPending(id, open))
                builder.addAction(0, "Open", activityPending(id, open))
            }
            post(id, builder.build())
        }

        override fun linkReceived(
            id: String,
            deviceId: String,
            url: String,
        ) {
            val open = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val builder =
                base()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Link from ${deviceName(deviceId)}")
                    .setContentText(url)
                    .setContentIntent(activityPending(id, open))
                    .addAction(0, "Open link", activityPending(id, open))
            post(id, builder.build())
        }

        override fun textReceived(
            id: String,
            deviceId: String,
            text: String,
        ) {
            val copy =
                Intent().setClassName(context.packageName, COPY_ACTIVITY)
                    .putExtra(EXTRA_TEXT, text.take(MAX_COPY_CHARS))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val builder =
                base()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Text from ${deviceName(deviceId)}")
                    .setContentText(text.lineSequence().firstOrNull().orEmpty().take(MAX_PREVIEW_CHARS))
                    .setContentIntent(activityPending(id, copy))
                    .addAction(0, "Copy to clipboard", activityPending(id, copy))
            post(id, builder.build())
        }

        override fun failed(
            id: String,
            deviceId: String,
            name: String,
            reason: String,
        ) {
            val builder =
                base()
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Could not receive $name")
                    .setContentText("${deviceName(deviceId)}: $reason")
            post(id, builder.build())
        }

        private fun base(): NotificationCompat.Builder =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID,
            ).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)

        private fun deviceName(deviceId: String): String =
            devices.get().deviceStates.value.firstOrNull { it.deviceId == deviceId }?.name ?: "your device"

        private fun viewIntent(
            uri: Uri,
            mime: String,
        ): Intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun activityPending(
            id: String,
            intent: Intent,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The permission is checked just above on Android 13 and later, which lint cannot follow. */
        @SuppressLint("MissingPermission")
        private fun post(
            id: String,
            notification: Notification,
        ) {
            val allowed =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (allowed) {
                NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
            }
        }

        companion object {
            const val CHANNEL_ID = "transfers"
            const val COPY_ACTIVITY = "app.tandem.ui.share.CopyTextActivity"
            const val EXTRA_TEXT = "app.tandem.extra.TEXT"
            private const val PERCENT = 100
            private const val MAX_COPY_CHARS = 100_000
            private const val MAX_PREVIEW_CHARS = 80
        }
    }
