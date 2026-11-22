// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** UDP identity announce and receive on port 1716 (plan section 2.2). Blocking calls; run on IO. */
class UdpDiscovery(private val port: Int = PortRange.DISCOVERY_PORT) {
    fun open(): DatagramSocket =
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            // Inside apply, bare `port` is DatagramSocket.getPort() (-1 when unconnected).
            bind(InetSocketAddress(this@UdpDiscovery.port))
        }

    /** Sends an encoded identity line to the broadcast address and to each custom IP. */
    fun announce(
        socket: DatagramSocket,
        identityLine: ByteArray,
        customTargets: List<InetAddress>,
    ) {
        for (target in listOf(BROADCAST) + customTargets) {
            try {
                socket.send(DatagramPacket(identityLine, identityLine.size, target, port))
            } catch (_: IOException) {
                // One unreachable target must not stop the others.
            }
        }
    }

    /** Unicast identity to one host, used by mDNS resolve (the peer then connects to us). */
    fun unicast(
        socket: DatagramSocket,
        identityLine: ByteArray,
        host: InetAddress,
    ) {
        socket.send(DatagramPacket(identityLine, identityLine.size, host, port))
    }

    /** Receive loop. Returns when the socket closes. Malformed datagrams are dropped. */
    fun receive(
        socket: DatagramSocket,
        onPacket: (NetworkPacket, InetAddress) -> Unit,
    ) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (!socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (_: IOException) {
                if (socket.isClosed) return
                continue
            }
            val text = String(datagram.data, datagram.offset, datagram.length, Charsets.UTF_8).trim()
            val packet =
                try {
                    PacketCodec.decode(text)
                } catch (_: PacketException) {
                    continue
                }
            onPacket(packet, datagram.address)
        }
    }

    companion object {
        const val RECEIVE_BUFFER_BYTES = 64 * 1024
        val BROADCAST: InetAddress =
            InetAddress.getByAddress(
                byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()),
            )
    }
}
