// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the notifications the listener service sees and carries out the desktop's requests on them.
 * The service registers itself in [listener] while it is connected. Without notification access
 * there is no listener, so [current] is empty and every action returns false.
 */
@Singleton
class AndroidNotificationSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NotificationSource {
        @Volatile var listener: NotificationListenerService? = null

        private class ReplyTarget(val intent: PendingIntent, val input: RemoteInput)

        // Reply ids stay valid while the notification is likely posted. Old ones fall out first.
        private val replies =
            object : LinkedHashMap<String, ReplyTarget>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplyTarget>?) =
                    size > MAX_REPLIES
            }

        override fun current(): List<NotificationSnapshot> =
            listener?.activeNotifications.orEmpty().map(::snapshot)

        override fun cancel(key: String) {
            listener?.cancelNotification(key)
        }

        override fun reply(
            replyId: String,
            message: String,
        ): Boolean {
            val target = synchronized(replies) { replies[replyId] } ?: return false
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(target.input.resultKey, message) }
            RemoteInput.addResultsToIntent(arrayOf(target.input), intent, results)
            return try {
                target.intent.send(context, 0, intent)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }

        override fun invokeAction(
            key: String,
            title: String,
        ): Boolean {
            val sbn = listener?.activeNotifications?.firstOrNull { it.key == key } ?: return false
            val action = sbn.notification.actions?.firstOrNull { it.title?.toString() == title } ?: return false
            return try {
                action.actionIntent.send()
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        }

        /** Converts one posted notification. A reply action is registered under a fresh id. */
        fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
            val n = sbn.notification
            val extras = n.extras
            val actions = n.actions.orEmpty().filter { it.title != null }
            val replyAction = actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            val replyId =
                replyAction?.let { action ->
                    UUID.randomUUID().toString().also { id ->
                        val target = ReplyTarget(action.actionIntent, action.remoteInputs.first())
                        synchronized(replies) { replies[id] = target }
                    }
                }
            val info = appInfo(sbn.packageName)
            return NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = info?.let { context.packageManager.getApplicationLabel(it).toString() } ?: sbn.packageName,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = bodyText(extras),
                ticker = n.tickerText?.toString().orEmpty(),
                timeMs = sbn.postTime,
                isClearable = sbn.isClearable,
                silent = isSilent(n),
                ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
                foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                secret = n.visibility == Notification.VISIBILITY_SECRET,
                actionTitles = actions.map { it.title.toString() },
                replyId = replyId,
                groupName = n.group,
                conversation = n.category == Notification.CATEGORY_MESSAGE,
                iconPng = iconPng(n),
                category = categoryOf(info),
            )
        }

        private fun bodyText(extras: Bundle): String =
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()
                .orEmpty()

        @Suppress("DEPRECATION")
        private fun isSilent(n: Notification): Boolean = n.priority < Notification.PRIORITY_DEFAULT

        private fun appInfo(packageName: String): ApplicationInfo? =
            try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }

        private fun categoryOf(info: ApplicationInfo?): String =
            if (info?.category == ApplicationInfo.CATEGORY_PRODUCTIVITY) "Work" else "Messaging"

        /** The large icon, or the small one, as a 64 px PNG. Null when it cannot be drawn. */
        private fun iconPng(n: Notification): ByteArray? {
            val drawable = (n.getLargeIcon() ?: n.smallIcon)?.loadDrawable(context) ?: return null
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            return out.toByteArray()
        }

        private companion object {
            const val MAX_REPLIES = 200
            const val ICON_PX = 64
            const val PNG_QUALITY = 100
        }
    }
