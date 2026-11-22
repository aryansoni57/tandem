// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's battery, as the battery plugin reports it. */
data class BatteryReading(
    val level: Int,
    val charging: Boolean,
    val low: Boolean,
) {
    companion object {
        /** Null when the level or scale is unknown (-1 or 0), so the caller keeps its previous reading. */
        fun from(
            level: Int,
            scale: Int,
            charging: Boolean,
            low: Boolean,
        ): BatteryReading? {
            if (level < 0 || scale <= 0) return null
            return BatteryReading((level * 100 / scale).coerceIn(0, 100), charging, low)
        }
    }
}

/** Where the battery plugin reads the phone's battery from. */
interface BatterySource {
    val readings: StateFlow<BatteryReading>

    /** Begins listening. Calling it again does nothing. */
    fun start()
}

/**
 * Listens to the sticky ACTION_BATTERY_CHANGED broadcast and to the low and okay events. A single
 * instance serves every device.
 */
@Singleton
class BatteryMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BatterySource {
        private val state = MutableStateFlow(BatteryReading(level = 0, charging = false, low = false))
        override val readings: StateFlow<BatteryReading> = state.asStateFlow()
        private val started = AtomicBoolean(false)

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> applyIntent(intent)
                        Intent.ACTION_BATTERY_LOW -> state.update { it.copy(low = true) }
                        Intent.ACTION_BATTERY_OKAY -> state.update { it.copy(low = false) }
                    }
                }
            }

        override fun start() {
            if (!started.compareAndSet(false, true)) return
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_BATTERY_LOW)
                    addAction(Intent.ACTION_BATTERY_OKAY)
                }
            val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            if (sticky != null) applyIntent(sticky)
        }

        private fun applyIntent(intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val reading =
                BatteryReading.from(
                    level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    charging = charging,
                    low = state.value.low,
                ) ?: return
            state.value = reading
        }
    }
