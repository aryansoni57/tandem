// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body of `kdeconnect.battery`. [thresholdEvent] is [THRESHOLD_LOW] only in the packet that reports
 * the transition to low battery; every other packet carries [THRESHOLD_NONE].
 */
data class BatteryBody(
    val currentCharge: Int,
    val isCharging: Boolean,
    val thresholdEvent: Int = THRESHOLD_NONE,
) {
    init {
        require(currentCharge in 0..MAX_CHARGE) { "currentCharge must be 0..$MAX_CHARGE" }
    }

    val isLowEvent: Boolean get() = thresholdEvent == THRESHOLD_LOW

    fun toJson(): JsonObject =
        buildJsonObject {
            put("currentCharge", currentCharge)
            put("isCharging", isCharging)
            put("thresholdEvent", thresholdEvent)
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(
            id,
            PacketTypes.BATTERY,
            toJson(),
        )

    companion object {
        const val THRESHOLD_NONE = 0
        const val THRESHOLD_LOW = 1
        const val MAX_CHARGE = 100

        /** Returns null when the charge is missing or outside 0..100, or when the charging flag is missing. */
        fun parse(body: JsonObject): BatteryBody? {
            val level = body.getInt("currentCharge") ?: return null
            if (level !in 0..MAX_CHARGE) return null
            val charging = body.getBoolean("isCharging") ?: return null
            val threshold = body.getInt("thresholdEvent") ?: THRESHOLD_NONE
            return BatteryBody(level, charging, threshold)
        }
    }
}

/** Body of `kdeconnect.battery.request`: asks the peer to send its battery status now. */
object BatteryRequestBody {
    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(
            id,
            PacketTypes.BATTERY_REQUEST,
            buildJsonObject { put("request", true) },
        )
}
