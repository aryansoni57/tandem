// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `kdeconnect.sftp.request` from the desktop: `{startBrowsing: true}` asks the phone to open its storage. */
object SftpRequest {
    fun isStartBrowsing(body: JsonObject): Boolean = body.getBoolean("startBrowsing") == true
}

/** What the phone answers in `kdeconnect.sftp`: where to connect, or why it cannot. */
sealed interface SftpReply {
    /**
     * The server is up. [paths] are the shared folders as the server names them, [names] what the desktop
     * should call each one. [path] is the single folder, or `/` when there are several (kept for old desktops).
     */
    data class Ready(
        val ip: String,
        val port: Int,
        val user: String,
        val password: String,
        val paths: List<String>,
        val names: List<String>,
    ) : SftpReply

    data class Failed(val message: String) : SftpReply

    fun toJson(): JsonObject =
        buildJsonObject {
            when (this@SftpReply) {
                is Ready -> {
                    put("ip", ip)
                    put("port", port)
                    put("user", user)
                    put("password", password)
                    put("path", if (paths.size == 1) paths.first() else "/")
                    if (paths.isNotEmpty()) {
                        put("multiPaths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
                        put("pathNames", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                is Failed -> put("errorMessage", message)
            }
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.SFTP, toJson())
}
