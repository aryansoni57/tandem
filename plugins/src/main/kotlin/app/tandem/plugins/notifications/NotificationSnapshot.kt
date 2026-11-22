// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.data.NotifRule

/** What the listener knows about one posted notification, without any Android types. */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val ticker: String,
    val timeMs: Long,
    val isClearable: Boolean,
    val silent: Boolean,
    val ongoing: Boolean,
    val foregroundService: Boolean,
    val groupSummary: Boolean,
    val secret: Boolean,
    val actionTitles: List<String>,
    val replyId: String?,
    val groupName: String?,
    val conversation: Boolean,
    val iconPng: ByteArray?,
    /** SyncCategory name, guessed from the app's category. */
    val category: String = "Messaging",
)

/** The user's choices, read when a notification is posted. */
interface NotificationPolicy {
    suspend fun ruleFor(packageName: String): NotifRule

    /** Remembers an app that posted, so it shows in the app list. */
    suspend fun seen(snapshot: NotificationSnapshot)

    suspend fun showOnLaptop(): Boolean

    suspend fun screenOffOnly(): Boolean

    fun isScreenOn(): Boolean
}

/** The phone side of notifications: what is posted now, and what a desktop action does here. */
interface NotificationSource {
    fun current(): List<NotificationSnapshot>

    fun cancel(key: String)

    /** Returns false when the reply id is unknown or the app refused it. */
    fun reply(
        replyId: String,
        message: String,
    ): Boolean

    /** Returns false when the notification or action is gone. */
    fun invokeAction(
        key: String,
        title: String,
    ): Boolean
}
