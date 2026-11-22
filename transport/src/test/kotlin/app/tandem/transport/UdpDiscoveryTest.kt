// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals

class UdpDiscoveryTest {
    @Test
    fun openBindsToConfiguredPort() {
        val port = DatagramSocket(0).use { it.localPort }
        val socket = UdpDiscovery(port).open()
        socket.use { assertEquals(port, it.localPort) }
    }
}
