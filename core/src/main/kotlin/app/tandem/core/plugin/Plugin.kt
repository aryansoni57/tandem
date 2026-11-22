// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.NetworkPacket

/** Behaviour of one feature for one device. Plugins never touch sockets. */
interface Plugin {
    suspend fun onCreate() {}

    /** Returns true when the packet was handled. */
    suspend fun onPacket(packet: NetworkPacket): Boolean

    suspend fun onDestroy() {}
}
