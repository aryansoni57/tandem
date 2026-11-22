// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.telephony

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Base64
import app.tandem.core.data.SettingsStore
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.plugins.mpris.MprisController
import app.tandem.plugins.mpris.MprisState
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.body.MprisAction
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Name and photo from the contacts, found by phone number. Needs READ_CONTACTS; without it nothing is found. */
@Singleton
class AndroidContactLookup
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ContactLookup {
        override suspend fun find(number: String): ContactInfo? =
            withContext(Dispatchers.IO) {
                if (!hasContactsAccess()) return@withContext null
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                val columns =
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
                runCatching {
                    context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        ContactInfo(cursor.getString(0), cursor.getString(1)?.let { thumbnail(Uri.parse(it)) })
                    }
                }.getOrNull()
            }

        private fun hasContactsAccess(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        /** The photo as base64, or null when it is missing or larger than [MAX_PHOTO_BYTES]. */
        private fun thumbnail(uri: Uri): String? =
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.takeIf { it.size <= MAX_PHOTO_BYTES }
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            }.getOrNull()

        private companion object {
            const val MAX_PHOTO_BYTES = 64 * 1024
        }
    }

/** Mutes the ring stream while a call rings. Some phones refuse while Do Not Disturb rules apply. */
@Singleton
class AndroidCallMuter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CallMuter {
        private val audio = context.getSystemService(AudioManager::class.java)
        private var muted = false

        @Synchronized
        override fun mute() {
            if (muted) return
            muted =
                runCatching {
                    audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                }.isSuccess
        }

        @Synchronized
        override fun restore() {
            if (!muted) return
            muted = false
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
        }
    }

@Singleton
class StoreCallSettings
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) : CallSettings {
        override suspend fun alertsOn(): Boolean = settings.callAlerts.first()

        override suspend fun pauseMediaOn(): Boolean = settings.pauseMediaOnCall.first()
    }

/**
 * Receives the system's PHONE_STATE broadcast. It needs READ_PHONE_STATE, and READ_CALL_LOG to be given the
 * caller's number. The work runs on a scope so the receiver can finish before the packets are sent.
 */
@AndroidEntryPoint
class PhoneStateReceiver : BroadcastReceiver() {
    @Inject lateinit var events: CallEvents

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state =
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> CallState.Ringing
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.Offhook
                TelephonyManager.EXTRA_STATE_IDLE -> CallState.Idle
                else -> return
            }
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                events.onState(state, number)
            } finally {
                pending.finish()
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TelephonyBindings {
    @Binds
    abstract fun contacts(impl: AndroidContactLookup): ContactLookup

    @Binds
    abstract fun muter(impl: AndroidCallMuter): CallMuter

    @Binds
    abstract fun settings(impl: StoreCallSettings): CallSettings

    companion object {
        // One parameter per collaborator of CallEvents; the module is the only place they are put together.
        @Suppress("LongParameterList")
        @Provides
        @Singleton
        fun events(
            // Lazy: PeerSender and DeviceManager are the same object, which builds the plugin registry.
            peers: dagger.Lazy<PeerSender>,
            devices: dagger.Lazy<DeviceManager>,
            settings: CallSettings,
            contacts: ContactLookup,
            muter: CallMuter,
            mpris: MprisController,
            mprisState: MprisState,
            log: app.tandem.plugins.HandledLog,
        ): CallEvents =
            CallEvents(
                peers =
                    object : PeerSender {
                        override suspend fun sendPacket(
                            deviceId: String,
                            packet: NetworkPacket,
                        ): Boolean = peers.get().sendPacket(deviceId, packet)
                    },
                settings = settings,
                contacts = contacts,
                muter = muter,
                connectedDevices = {
                    devices.get().deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
                },
                pauseMedia = { if (mprisState.nowPlaying.value?.isPlaying == true) mpris.action(MprisAction.Pause) },
                log = log,
            )

        @Provides
        @IntoSet
        fun descriptor(events: CallEvents): PluginDescriptor = TelephonyDescriptor.create(events)
    }
}
