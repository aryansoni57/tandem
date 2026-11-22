// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.data.NotifRule
import app.tandem.protocol.body.NotificationBody
import java.security.MessageDigest

/** Decides what goes to the desktop and builds the packet body (plan section 3, Phase 4). */
class NotificationMapper(private val ownPackage: String) {
    /**
     * Returns null when the notification must not be sent: our own, an ongoing or foreground-service
     * one, a group summary, a secret one, or an app whose rule is HIDDEN.
     */
    fun map(
        snapshot: NotificationSnapshot,
        rule: NotifRule,
    ): NotificationBody? {
        if (isSkipped(snapshot, rule)) return null
        val previewOnly = rule == NotifRule.PREVIEW_ONLY
        return NotificationBody(
            id = snapshot.key,
            appName = snapshot.appName,
            title = snapshot.title,
            text = if (previewOnly) "" else snapshot.text,
            ticker = if (previewOnly) snapshot.title else snapshot.ticker,
            isClearable = snapshot.isClearable,
            time = snapshot.timeMs,
            silent = snapshot.silent,
            // A preview cannot be replied to or acted on.
            requestReplyId = snapshot.replyId.takeUnless { previewOnly },
            actions = if (previewOnly) emptyList() else snapshot.actionTitles,
            payloadHash = snapshot.iconPng?.let(::md5Hex),
            groupName = snapshot.groupName,
            conversation = snapshot.conversation,
        )
    }

    private fun isSkipped(
        snapshot: NotificationSnapshot,
        rule: NotifRule,
    ): Boolean =
        snapshot.packageName == ownPackage ||
            rule == NotifRule.HIDDEN ||
            snapshot.ongoing ||
            snapshot.foregroundService ||
            snapshot.groupSummary ||
            snapshot.secret

    companion object {
        /** Uppercase MD5 of [bytes], the icon cache key the desktop expects. */
        fun md5Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
