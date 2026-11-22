// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One command the user set up on the desktop. [id] is the key to send back to run it. */
data class DesktopCommand(
    val id: String,
    val name: String,
    val command: String,
)

/** What the phone sends in `kdeconnect.runcommand.request`. */
sealed interface RunCommandRequest {
    /** Asks for the command list. */
    data object List : RunCommandRequest

    /** Runs the command with this key on the desktop. */
    data class Run(val key: String) : RunCommandRequest

    /** Opens the desktop's own dialog for adding commands. */
    data object Setup : RunCommandRequest

    fun toJson(): JsonObject =
        buildJsonObject {
            when (this@RunCommandRequest) {
                List -> put("requestCommandList", true)
                is Run -> put("key", key)
                Setup -> put("setup", true)
            }
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.RUNCOMMAND_REQUEST, toJson())
}

/**
 * Body of `kdeconnect.runcommand` from the desktop. [commands] is null when the packet has no list.
 * The list arrives as a JSON-encoded string inside the body, not as an object, and keeps its order.
 */
data class RunCommandList(
    val commands: kotlin.collections.List<DesktopCommand>?,
    val canAddCommand: Boolean?,
) {
    companion object {
        fun parse(body: JsonObject): RunCommandList =
            RunCommandList(
                commands = body.getString("commandList")?.let(::parseCommands),
                canAddCommand = body.getBoolean("canAddCommand"),
            )

        /** An empty or damaged list gives no commands. An entry without a name or a command is skipped. */
        internal fun parseCommands(text: String): kotlin.collections.List<DesktopCommand> {
            val root =
                try {
                    Json.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return emptyList()
            return root.mapNotNull { (id, entry) ->
                val fields = entry as? JsonObject ?: return@mapNotNull null
                val name = fields.getString("name") ?: return@mapNotNull null
                val command = fields.getString("command") ?: return@mapNotNull null
                DesktopCommand(id, name, command)
            }
        }
    }
}
