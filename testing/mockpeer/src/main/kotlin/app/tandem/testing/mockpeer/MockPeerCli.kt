// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.testing.mockpeer

import app.tandem.protocol.IdentityPackets
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.system.exitProcess

/**
 * Command line driver for manual checks against the app over `adb reverse` or LAN.
 *
 *   connect <host> <port> <ourDeviceId>   act as a desktop that connects to the app (flow A)
 *   listen <port>                         act as a desktop that waits for the app (flow B)
 *
 * Both print the peer identity and TLS parameters, exchange one ping, and exit.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: connect <host> <port> <ourDeviceId> | listen <port>")
        exitProcess(2)
    }
    val peerId = System.getenv("MOCK_DEVICE_ID") ?: "mockpeer0000000000000000000000000"
    MockPeer(peerId).use { peer ->
        val session =
            when (args[0]) {
                "connect" -> peer.connectTo(InetSocketAddress(args[1], args[2].toInt()), args[3])
                "listen" -> ServerSocket(args[1].toInt()).use { peer.acceptFrom(it) }
                else -> {
                    System.err.println("unknown command ${args[0]}")
                    exitProcess(2)
                }
            }
        println("peer=${session.peer.deviceId} name=${session.peer.deviceName} type=${session.peer.deviceType}")
        println("tls=${session.tlsVersion} cipher=${session.cipherSuite} protocol=${session.peer.protocolVersion}")
        runBlocking {
            session.channel.send(NetworkPacket(1, PacketTypes.IDENTITY, JsonObject(emptyMap())))
        }
        println("ok ${IdentityPackets.PROTOCOL_VERSION}")
        session.channel.close()
    }
}
