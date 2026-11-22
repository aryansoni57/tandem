// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.battery

import app.tandem.core.device.BatteryState
import app.tandem.core.device.PeerStateStore
import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.BatteryBody
import app.tandem.protocol.body.BatteryRequestBody
import kotlinx.coroutines.launch

/**
 * Sends our battery on connect and on every change, answers battery.request, and stores the
 * peer's battery for the device screen.
 */
class BatteryPlugin(
    private val context: PluginContext,
    private val source: BatterySource,
    private val peers: PeerStateStore,
) : Plugin {
    /**
     * The low flag we last sent. Null until the first send, so a battery that is already low
     * does not alert on connect.
     */
    private var lastLow: Boolean? = null

    override suspend fun onCreate() {
        source.start()
        context.scope.launch {
            source.readings.collect { sendChange(it) }
        }
        context.send(BatteryRequestBody.toPacket())
    }

    override suspend fun onPacket(packet: NetworkPacket): Boolean =
        when (packet.type) {
            PacketTypes.BATTERY_REQUEST -> {
                sendCurrent()
                true
            }
            PacketTypes.BATTERY -> {
                BatteryBody.parse(packet.body)?.let {
                    peers.setBattery(context.deviceId, BatteryState(it.currentCharge, it.isCharging, it.isLowEvent))
                }
                true
            }
            else -> false
        }

    override suspend fun onDestroy() {
        peers.clearBattery(context.deviceId)
    }

    private suspend fun sendChange(reading: BatteryReading) {
        val previous = lastLow
        val threshold =
            if (reading.low && previous == false) BatteryBody.THRESHOLD_LOW else BatteryBody.THRESHOLD_NONE
        lastLow = reading.low
        context.send(BatteryBody(reading.level, reading.charging, threshold).toPacket())
    }

    private suspend fun sendCurrent() {
        val reading = source.readings.value
        context.send(BatteryBody(reading.level, reading.charging).toPacket())
    }
}
