// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tandem.core.identity.IdentityBackend
import app.tandem.protocol.DeviceNameFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tandem_settings")

/** Preferences DataStore: our deviceId, identity backend, custom IPs and onboarding state. */
@Singleton
class SettingsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store: DataStore<Preferences> get() = context.settingsDataStore

        private val deviceIdKey = stringPreferencesKey("device_id")
        private val backendKey = stringPreferencesKey("identity_backend")
        private val customIpsKey = stringSetPreferencesKey("custom_ips")
        private val onboardingKey = booleanPreferencesKey("onboarding_done")
        private val clipboardHistoryKey = booleanPreferencesKey("clipboard_history")
        private val skipSensitiveKey = booleanPreferencesKey("clipboard_skip_sensitive")
        private val notifShowKey = booleanPreferencesKey("notif_show_on_laptop")
        private val notifScreenOffKey = booleanPreferencesKey("notif_screen_off_only")
        private val callAlertsKey = booleanPreferencesKey("call_alerts")
        private val pauseMediaKey = booleanPreferencesKey("call_pause_media")
        private val smsFromLaptopKey = booleanPreferencesKey("sms_from_laptop")
        private val sftpEnabledKey = booleanPreferencesKey("sftp_enabled")
        private val sftpFoldersKey = stringSetPreferencesKey("sftp_folders")

        /** 32 hex characters with no dashes, matching the desktop's deviceId pattern. Generated once. */
        suspend fun ensureDeviceId(): String {
            store.data.first()[deviceIdKey]?.let { return it }
            val fresh = UUID.randomUUID().toString().replace("-", "")
            store.edit { it[deviceIdKey] = fresh }
            return fresh
        }

        suspend fun identityBackend(): IdentityBackend? =
            store.data.first()[backendKey]?.let { name -> IdentityBackend.entries.firstOrNull { it.name == name } }

        suspend fun setIdentityBackend(backend: IdentityBackend) {
            store.edit { it[backendKey] = backend.name }
        }

        val customIps: Flow<Set<String>> get() = store.data.map { it[customIpsKey] ?: emptySet() }

        val onboardingDone: Flow<Boolean> get() = store.data.map { it[onboardingKey] ?: false }

        suspend fun setOnboardingDone() {
            store.edit { it[onboardingKey] = true }
        }

        /** Keep a local history of clipboard text. Off by default. */
        val clipboardHistory: Flow<Boolean> get() = store.data.map { it[clipboardHistoryKey] ?: false }

        suspend fun setClipboardHistory(enabled: Boolean) {
            store.edit { it[clipboardHistoryKey] = enabled }
        }

        /** Skip clipboard text the system marks as sensitive (API 33+). On by default. */
        val skipSensitiveClips: Flow<Boolean> get() = store.data.map { it[skipSensitiveKey] ?: true }

        /** Send phone notifications to the laptop. On by default. */
        val notifShowOnLaptop: Flow<Boolean> get() = store.data.map { it[notifShowKey] ?: true }

        suspend fun setNotifShowOnLaptop(enabled: Boolean) {
            store.edit { it[notifShowKey] = enabled }
        }

        /** Send notifications only while the phone screen is off. Off by default. */
        val notifScreenOffOnly: Flow<Boolean> get() = store.data.map { it[notifScreenOffKey] ?: false }

        suspend fun setNotifScreenOffOnly(enabled: Boolean) {
            store.edit { it[notifScreenOffKey] = enabled }
        }

        /** Tell the laptop about incoming calls. On by default. */
        val callAlerts: Flow<Boolean> get() = store.data.map { it[callAlertsKey] ?: true }

        suspend fun setCallAlerts(enabled: Boolean) {
            store.edit { it[callAlertsKey] = enabled }
        }

        /** Pause the laptop's media player when a call rings. On by default. */
        val pauseMediaOnCall: Flow<Boolean> get() = store.data.map { it[pauseMediaKey] ?: true }

        suspend fun setPauseMediaOnCall(enabled: Boolean) {
            store.edit { it[pauseMediaKey] = enabled }
        }

        /** Read and send texts from the laptop. On by default, but nothing works until SMS access is granted. */
        val smsFromLaptop: Flow<Boolean> get() = store.data.map { it[smsFromLaptopKey] ?: true }

        suspend fun setSmsFromLaptop(enabled: Boolean) {
            store.edit { it[smsFromLaptopKey] = enabled }
        }

        /** Let the laptop browse the phone's files. Off by default: it opens the storage to the network. */
        val sftpEnabled: Flow<Boolean> get() = store.data.map { it[sftpEnabledKey] ?: false }

        suspend fun setSftpEnabled(enabled: Boolean) {
            store.edit { it[sftpEnabledKey] = enabled }
        }

        /** Top-level storage folders the laptop may reach. */
        val sftpFolders: Flow<Set<String>> get() = store.data.map { it[sftpFoldersKey] ?: DEFAULT_SFTP_FOLDERS }

        suspend fun setSftpFolders(folders: Set<String>) {
            store.edit { it[sftpFoldersKey] = folders }
        }

        /** Name from the global device name, falling back to the model, filtered for the wire. */
        fun deviceName(): String {
            val raw = Settings.Global.getString(context.contentResolver, "device_name") ?: Build.MODEL
            return DeviceNameFilter.filter(raw).ifBlank { "Tandem" }
        }

        private companion object {
            val DEFAULT_SFTP_FOLDERS = setOf("DCIM", "Download", "Documents")
        }
    }
