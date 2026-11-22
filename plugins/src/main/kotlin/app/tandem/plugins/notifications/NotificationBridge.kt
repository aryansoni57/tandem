// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.notifications

import app.tandem.core.device.PeerSender
import app.tandem.core.transfer.PayloadEndpoint
import app.tandem.protocol.body.NotificationBody
import app.tandem.protocol.body.NotificationCancelBody
import app.tandem.transport.PayloadProgress
import app.tandem.transport.TransferControl
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends notifications to every connected, paired device. The listener calls [onPosted] and [onRemoved];
 * the plugin calls [sendAll] when the desktop asks for the current list.
 */
class NotificationBridge(
    private val peers: PeerSender,
    private val endpoint: PayloadEndpoint,
    private val policy: NotificationPolicy,
    private val mapper: NotificationMapper,
    private val connectedDevices: () -> List<String>,
) {
    // The icon hash last sent per device and notification key. The icon goes again only when it changes.
    private val sentIcons = ConcurrentHashMap<String, String>()

    suspend fun onPosted(snapshot: NotificationSnapshot) {
        policy.seen(snapshot)
        if (!policy.showOnLaptop()) return
        if (policy.screenOffOnly() && policy.isScreenOn()) return
        val body = mapper.map(snapshot, policy.ruleFor(snapshot.packageName)) ?: return
        for (deviceId in connectedDevices()) send(deviceId, body, snapshot.iconPng)
    }

    suspend fun onRemoved(key: String) {
        val packet = NotificationCancelBody(key).toPacket()
        for (deviceId in connectedDevices()) {
            sentIcons.remove(iconKey(deviceId, key))
            peers.sendPacket(deviceId, packet)
        }
    }

    /** Answers `{request: true}`: sends the current notifications to one device, once each. */
    suspend fun sendAll(
        deviceId: String,
        current: List<NotificationSnapshot>,
    ) {
        if (!policy.showOnLaptop()) return
        for (snapshot in current) {
            policy.seen(snapshot)
            val body = mapper.map(snapshot, policy.ruleFor(snapshot.packageName)) ?: continue
            // A fresh request means the desktop dropped its state, so resend the icons too.
            sentIcons.remove(iconKey(deviceId, snapshot.key))
            send(deviceId, body, snapshot.iconPng)
        }
    }

    private suspend fun send(
        deviceId: String,
        body: NotificationBody,
        icon: ByteArray?,
    ) {
        val hash = body.payloadHash
        val key = iconKey(deviceId, body.id)
        if (icon == null || hash == null || sentIcons[key] == hash) {
            peers.sendPacket(deviceId, body.toPacket())
            return
        }
        val sent =
            endpoint.send(
                deviceId,
                icon.size.toLong(),
                ByteArrayInputStream(icon),
                TransferControl(),
                PayloadProgress { _, _ -> },
            ) { port ->
                peers.sendPacket(deviceId, body.toPacket(payloadSize = icon.size.toLong(), payloadPort = port))
            }
        if (sent != null) sentIcons[key] = hash
    }

    private fun iconKey(
        deviceId: String,
        key: String,
    ) = "$deviceId/$key"
}
