// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlinx.serialization.json.JsonObject

/**
 * One KDE Connect packet. Bodies stay untyped here; plugins map them to typed bodies.
 *
 * @property payloadSize null when the packet has no payload, -1 when the size is unknown.
 * @property payloadPort port from `payloadTransferInfo`, required whenever [payloadSize] is set.
 */
data class NetworkPacket(
    val id: Long,
    val type: String,
    val body: JsonObject = JsonObject(emptyMap()),
    val payloadSize: Long? = null,
    val payloadPort: Int? = null,
) {
    val hasPayload: Boolean get() = payloadSize != null
}
