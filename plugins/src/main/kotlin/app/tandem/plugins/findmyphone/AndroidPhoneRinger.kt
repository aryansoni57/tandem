// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.findmyphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings on the alarm stream at full volume, so silent mode does not hide it, and puts the volume back
 * afterwards. Every part is best effort: a phone without a flashlight still rings, and a refused
 * notification still sounds. The ring stops by itself after [MAX_RING_MS].
 */
@Singleton
class AndroidPhoneRinger
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PhoneRinger {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val state = MutableStateFlow(false)
        override val ringing: StateFlow<Boolean> = state.asStateFlow()

        private val audio = context.getSystemService(AudioManager::class.java)
        private val notifications = context.getSystemService(NotificationManager::class.java)
        private var player: MediaPlayer? = null
        private var savedVolume = NO_VOLUME
        private var torchJob: Job? = null
        private var timeoutJob: Job? = null

        @Synchronized
        override fun start(requester: String?) {
            if (state.value) return
            state.value = true
            raiseVolume()
            playSound()
            vibrate()
            blinkTorch()
            showNotification(requester)
            timeoutJob = scope.launch {
                delay(MAX_RING_MS)
                stop()
            }
        }

        @Synchronized
        override fun stop() {
            if (!state.value) return
            state.value = false
            timeoutJob?.cancel()
            torchJob?.cancel()
            runCatching { player?.run { stop(); release() } }
            player = null
            stopVibration()
            restoreVolume()
            notifications.cancel(NOTIFICATION_ID)
        }

        private fun raiseVolume() {
            runCatching {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        }

        private fun restoreVolume() {
            if (savedVolume == NO_VOLUME) return
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = NO_VOLUME
        }

        private fun playSound() {
            val uri =
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player =
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                        setDataSource(context, uri)
                        isLooping = true
                        prepare()
                        start()
                    }
                }.getOrNull()
        }

        // VIBRATE is declared in the app manifest, which is the one that counts at run time.
        @SuppressLint("MissingPermission")
        private fun stopVibration() {
            vibrator()?.cancel()
        }

        private fun vibrator(): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }

        @SuppressLint("MissingPermission")
        private fun vibrate() {
            runCatching { vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)) }
        }

        private fun blinkTorch() {
            val manager = context.getSystemService(CameraManager::class.java) ?: return
            val id =
                runCatching {
                    manager.cameraIdList.firstOrNull {
                        manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                }.getOrNull() ?: return
            torchJob = scope.launch {
                var on = false
                try {
                    while (isActive) {
                        on = !on
                        manager.setTorchMode(id, on)
                        delay(TORCH_BLINK_MS)
                    }
                } catch (_: CameraAccessException) {
                    // Another app took the camera; the ring goes on without the light.
                } finally {
                    runCatching { manager.setTorchMode(id, false) }
                }
            }
        }

        private fun showNotification(requester: String?) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Find my phone", NotificationManager.IMPORTANCE_HIGH),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent().setClassName(context.packageName, ACTIVITY_CLASS)
                        .putExtra(EXTRA_DEVICE_NAME, requester)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val stop =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, StopRingReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            // Android 14 may refuse the full-screen intent to an app that is not a call or alarm app. The
            // notification still shows, and tapping it opens the same screen.
            val fullScreenAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                notifications.canUseFullScreenIntent()
            val builder =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("${requester ?: "Your laptop"} is looking for this phone")
                    .setContentText("Ringing at full volume")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .setContentIntent(open)
                    .addAction(0, "Stop ringing", stop)
            if (fullScreenAllowed) builder.setFullScreenIntent(open, true)
            runCatching { notifications.notify(NOTIFICATION_ID, builder.build()) }
        }

        companion object {
            const val MAX_RING_MS = 3 * 60 * 1000L
            const val EXTRA_DEVICE_NAME = "app.tandem.extra.DEVICE_NAME"
            private const val ACTIVITY_CLASS = "app.tandem.ui.findphone.FindMyPhoneActivity"
            private const val CHANNEL_ID = "find_my_phone"
            private const val NOTIFICATION_ID = 4210
            private const val NO_VOLUME = -1
            private const val TORCH_BLINK_MS = 500L
        }
    }
