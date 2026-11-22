// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.pairing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.PairingPrompt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** High-priority notification for an incoming pairing request, with Accept and Reject actions. */
object IncomingPairNotification {
    private const val CHANNEL_ID = "pairing"
    private const val ACTION_ACCEPT = "app.tandem.action.PAIR_ACCEPT"
    private const val ACTION_REJECT = "app.tandem.action.PAIR_REJECT"
    private const val EXTRA_DEVICE_ID = "deviceId"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun show(
        context: Context,
        prompt: PairingPrompt,
    ) {
        val accept = actionIntent(context, ACTION_ACCEPT, prompt.deviceId, requestCode = 1)
        val reject = actionIntent(context, ACTION_REJECT, prompt.deviceId, requestCode = 2)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Pairing request from ${prompt.deviceName}")
                .setContentText("Code ${prompt.verificationKey}. Check that it matches the other device.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setTimeoutAfter(PROMPT_TIMEOUT_MS)
                .setAutoCancel(true)
                .addAction(0, "Accept", accept)
                .addAction(0, "Reject", reject)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(prompt.deviceId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was denied. The pairing sheet in the app still works.
        }
    }

    private fun actionIntent(
        context: Context,
        action: String,
        deviceId: String,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(context, PairingActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
        return PendingIntent.getBroadcast(
            context,
            requestCode + deviceId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private const val PROMPT_TIMEOUT_MS = 25_000L

    internal fun actionOf(intent: Intent): Pair<String?, String?> =
        intent.action to intent.getStringExtra(EXTRA_DEVICE_ID)

    internal const val ACCEPT = ACTION_ACCEPT
    internal const val REJECT = ACTION_REJECT
}

/** Receives the Accept and Reject buttons on the pairing notification. */
@AndroidEntryPoint
class PairingActionReceiver : BroadcastReceiver() {
    @Inject lateinit var deviceManager: DeviceManager

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val (action, deviceId) = IncomingPairNotification.actionOf(intent)
        if (deviceId == null) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (action) {
                    IncomingPairNotification.ACCEPT -> deviceManager.acceptPairing(deviceId)
                    IncomingPairNotification.REJECT -> deviceManager.rejectPairing(deviceId)
                }
                NotificationManagerCompat.from(context).cancel(deviceId.hashCode())
            } finally {
                pending.finish()
            }
        }
    }
}
