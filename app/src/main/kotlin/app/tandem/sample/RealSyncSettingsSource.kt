// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sample

import app.tandem.core.data.NotifAppRuleDao
import app.tandem.core.data.NotifAppRuleEntity
import app.tandem.core.data.NotifRule
import app.tandem.core.data.SettingsStore
import app.tandem.plugins.HandledEntry
import app.tandem.plugins.HandledLog
import app.tandem.plugins.HandledType
import app.tandem.state.CallsSmsSettings
import app.tandem.state.HandledItem
import app.tandem.state.HandledKind
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.SyncApp
import app.tandem.state.SyncCategory
import app.tandem.state.SyncSettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification sync settings and per-app rules, backed by the settings store and the rules table.
 * Calls, texts and the handled list are real too.
 */
@Singleton
class RealSyncSettingsSource
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val rules: NotifAppRuleDao,
        private val handledLog: HandledLog,
    ) : SyncSettingsSource {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override val notifications: StateFlow<NotificationSyncSettings> =
            combine(settings.notifShowOnLaptop, settings.notifScreenOffOnly) { show, screenOff ->
                NotificationSyncSettings(showOnLaptop = show, screenOffOnly = screenOff)
            }.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SETTINGS)

        override val apps: StateFlow<List<SyncApp>> =
            rules.observeAll().map { list -> list.map(::toSyncApp) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        override val calls: StateFlow<CallsSmsSettings> =
            combine(settings.smsFromLaptop, settings.callAlerts, settings.pauseMediaOnCall) { sms, alerts, pause ->
                CallsSmsSettings(textFromLaptop = sms, callAlerts = alerts, pauseMediaOnCall = pause)
            }.stateIn(scope, SharingStarted.Eagerly, CallsSmsSettings(true, true, true))

        override val handled: StateFlow<List<HandledItem>> =
            handledLog.entries
                .map { list -> list.map(::toHandledItem) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        override fun setShowOnLaptop(enabled: Boolean) {
            scope.launch { settings.setNotifShowOnLaptop(enabled) }
        }

        override fun setScreenOffOnly(enabled: Boolean) {
            scope.launch { settings.setNotifScreenOffOnly(enabled) }
        }

        override fun setAppEnabled(
            id: String,
            enabled: Boolean,
        ) {
            scope.launch { rules.setRule(id, if (enabled) NotifRule.SYNC else NotifRule.HIDDEN) }
        }

        override fun setTextFromLaptop(enabled: Boolean) {
            scope.launch { settings.setSmsFromLaptop(enabled) }
        }

        override fun setCallAlerts(enabled: Boolean) {
            scope.launch { settings.setCallAlerts(enabled) }
        }

        override fun setPauseMediaOnCall(enabled: Boolean) {
            scope.launch { settings.setPauseMediaOnCall(enabled) }
        }

        private companion object {
            val TIME_FORMAT = SimpleDateFormat("H:mm", Locale.getDefault())
            val DEFAULT_SETTINGS = NotificationSyncSettings(showOnLaptop = true, screenOffOnly = false)
        }

        private fun toHandledItem(entry: HandledEntry): HandledItem =
            HandledItem(
                id = entry.id.toString(),
                title = entry.title,
                detail = entry.detail,
                kind = if (entry.type == HandledType.Missed) HandledKind.Missed else HandledKind.Sms,
                timeLabel = TIME_FORMAT.format(Date(entry.timeMs)),
                initials = initialsOf(entry.title),
            )

        /** Up to two capitals from a contact name. A phone number has none, so it gets no initials. */
        private fun initialsOf(title: String): String? =
            title.split(' ')
                .filter { it.isNotEmpty() }
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
                .takeIf { it.isNotEmpty() && title.first().isLetter() }

        private fun toSyncApp(entity: NotifAppRuleEntity): SyncApp =
            SyncApp(
                id = entity.packageName,
                name = entity.appName,
                category = SyncCategory.entries.firstOrNull { it.name == entity.category } ?: SyncCategory.Messaging,
                enabled = entity.rule != NotifRule.HIDDEN,
                note = if (entity.rule == NotifRule.PREVIEW_ONLY) "Preview only" else entity.packageName,
                initial = entity.appName.firstOrNull()?.uppercase().orEmpty(),
            )
    }
