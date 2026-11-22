// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.sftp

import app.tandem.core.plugin.Plugin
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginDescriptor
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.SftpReply
import app.tandem.protocol.body.SftpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Answers `startBrowsing` with the server's address and a fresh password. Stops the server when the link closes. */
class SftpPlugin(
    private val context: PluginContext,
    private val host: SftpHost,
    private val peerAddress: suspend (String) -> InetAddress?,
) : Plugin {
    override suspend fun onPacket(packet: NetworkPacket): Boolean {
        if (packet.type != PacketTypes.SFTP_REQUEST) return false
        if (SftpRequest.isStartBrowsing(packet.body)) {
            // Making the server can take a few seconds the first time (the host key), so leave the packet loop.
            context.scope.launch {
                val reply =
                    try {
                        withContext(Dispatchers.IO) { host.start(peerAddress(context.deviceId)) }
                    } catch (_: Exception) {
                        SftpReply.Failed(SftpHost.START_FAILED)
                    } catch (_: LinkageError) {
                        // The SSH library failed to load. Say so instead of leaving the laptop waiting.
                        SftpReply.Failed(SftpHost.START_FAILED)
                    }
                context.send(reply.toPacket())
            }
        }
        return true
    }

    override suspend fun onDestroy() {
        host.stop()
    }
}

object SftpDescriptor {
    fun create(
        host: SftpHost,
        peerAddress: suspend (String) -> InetAddress?,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "sftp",
            incoming = setOf(PacketTypes.SFTP_REQUEST),
            outgoing = setOf(PacketTypes.SFTP),
            requiredPermissions = emptyList(),
            factory = { SftpPlugin(it, host, peerAddress) },
        )
}
