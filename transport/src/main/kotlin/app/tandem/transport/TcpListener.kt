// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/** Plaintext TCP accept loop on the first free port of [PortRange.TCP_LISTEN]. */
class TcpListener private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    /** Accepts sockets and hands each one to [handler] on its own coroutine. */
    fun start(
        scope: CoroutineScope,
        handler: (Socket) -> Unit,
    ): Job =
        scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        if (server.isClosed) break else continue
                    }
                launch(Dispatchers.IO) { handler(socket) }
            }
        }

    override fun close() {
        server.close()
    }

    companion object {
        fun bind(range: IntRange = PortRange.TCP_LISTEN): TcpListener =
            TcpListener(
                PortRange.firstFree(range) { port ->
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(port))
                    }
                },
            )
    }
}
