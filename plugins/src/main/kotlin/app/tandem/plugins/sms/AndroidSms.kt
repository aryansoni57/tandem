// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import app.tandem.core.data.SettingsStore
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.PeerSender
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.plugins.HandledLog
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.body.SmsConversationRequest
import app.tandem.protocol.body.SmsMessage
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

/** The SMS database, read-only. Texts only: MMS parts live in another table and are not read. */
@Singleton
class AndroidSmsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SmsStore {
        private val columns =
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.SUBSCRIPTION_ID,
            )

        override suspend fun latestPerThread(): List<SmsMessage> =
            withContext(Dispatchers.IO) {
                val seen = LinkedHashMap<Long, SmsMessage>()
                query(null, null) { message ->
                    seen.putIfAbsent(message.threadId, message)
                    seen.size < MAX_THREADS
                }
                seen.values.toList()
            }

        override suspend fun thread(request: SmsConversationRequest): List<SmsMessage> =
            withContext(Dispatchers.IO) {
                val older = request.rangeStartMs
                val selection = if (older == null) "thread_id = ?" else "thread_id = ? AND date < ?"
                val args = listOfNotNull(request.threadId.toString(), older?.toString()).toTypedArray()
                val out = mutableListOf<SmsMessage>()
                val limit = request.limit ?: Int.MAX_VALUE
                query(selection, args) { message ->
                    out += message
                    out.size < limit
                }
                out
            }

        override suspend fun threadIdFor(addresses: List<String>): Long =
            withContext(Dispatchers.IO) {
                runCatching { Telephony.Threads.getOrCreateThreadId(context, addresses.toSet()) }
                    // No thread could be made (no permission). A stable stand-in keeps the messages together.
                    .getOrElse { addresses.sorted().joinToString(",").hashCode().toLong() and Int.MAX_VALUE.toLong() }
            }

        /** Calls [accept] for each row, newest first, until it returns false. */
        private fun query(
            selection: String?,
            args: Array<String>?,
            accept: (SmsMessage) -> Boolean,
        ) {
            if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, columns, selection, args, "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    var rows = 0
                    while (cursor.moveToNext() && rows++ < MAX_ROWS) {
                        val sub = cursor.getInt(SUB_COLUMN).takeIf { it >= 0 }
                        val message =
                            SmsMessage(
                                id = cursor.getLong(ID_COLUMN),
                                threadId = cursor.getLong(THREAD_COLUMN),
                                addresses = listOf(cursor.getString(ADDRESS_COLUMN).orEmpty()),
                                body = cursor.getString(BODY_COLUMN).orEmpty(),
                                date = cursor.getLong(DATE_COLUMN),
                                type = cursor.getInt(TYPE_COLUMN),
                                read = cursor.getInt(READ_COLUMN) != 0,
                                subId = sub,
                            )
                        if (!accept(message)) break
                    }
                }
            }
        }

        private companion object {
            const val MAX_THREADS = 200
            const val MAX_ROWS = 5_000
            const val ID_COLUMN = 0
            const val THREAD_COLUMN = 1
            const val ADDRESS_COLUMN = 2
            const val BODY_COLUMN = 3
            const val DATE_COLUMN = 4
            const val TYPE_COLUMN = 5
            const val READ_COLUMN = 6
            const val SUB_COLUMN = 7
        }
    }

/** Sends through [SmsManager]. A long text is split into parts, which the radio joins again. */
@Singleton
class AndroidSmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SmsSender {
        override suspend fun send(
            addresses: List<String>,
            body: String,
            subId: Int?,
        ): Boolean =
            withContext(Dispatchers.IO) {
                if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    return@withContext false
                }
                val manager = manager(subId) ?: return@withContext false
                runCatching {
                    val parts = manager.divideMessage(body)
                    addresses.forEach { manager.sendMultipartTextMessage(it, null, parts, null, null) }
                }.isSuccess
            }

        private fun manager(subId: Int?): SmsManager? =
            if (subId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }
    }

@Singleton
class StoreSmsSettings
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) : SmsSettings {
        override suspend fun enabled(): Boolean = settings.smsFromLaptop.first()
    }

/**
 * Receives each incoming text. Only the system may send this broadcast (BROADCAST_SMS), and RECEIVE_SMS is
 * needed to be given it. Parts of one long text arrive together and are joined here.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject lateinit var push: SmsPush

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = parts.groupBy { it.originatingAddress.orEmpty() }
        val sub = intent.getIntExtra("subscription", -1).takeIf { it >= 0 }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                for ((sender, pieces) in bySender) {
                    if (sender.isEmpty()) continue
                    val text = pieces.joinToString("") { it.messageBody.orEmpty() }
                    push.onReceived(sender, text, pieces.first().timestampMillis, sub)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsBindings {
    @Binds
    abstract fun store(impl: AndroidSmsStore): SmsStore

    @Binds
    abstract fun sender(impl: AndroidSmsSender): SmsSender

    @Binds
    abstract fun settings(impl: StoreSmsSettings): SmsSettings

    companion object {
        @Provides
        @IntoSet
        fun descriptor(
            store: SmsStore,
            sender: SmsSender,
            settings: SmsSettings,
            log: HandledLog,
        ): PluginDescriptor = SmsDescriptor.create(store, sender, settings, log)

        @Provides
        @Singleton
        fun push(
            // Lazy: PeerSender and DeviceManager are the same object, which builds the plugin registry.
            peers: dagger.Lazy<PeerSender>,
            devices: dagger.Lazy<DeviceManager>,
            settings: SmsSettings,
            store: SmsStore,
        ): SmsPush =
            SmsPush(
                peers =
                    object : PeerSender {
                        override suspend fun sendPacket(
                            deviceId: String,
                            packet: NetworkPacket,
                        ): Boolean = peers.get().sendPacket(deviceId, packet)
                    },
                settings = settings,
                store = store,
                connectedDevices = {
                    devices.get().deviceStates.value.filter { it.paired && it.reachable }.map { it.deviceId }
                },
            )
    }
}
