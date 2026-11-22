// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.runcommand

import app.tandem.plugins.FakeContext
import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.DesktopCommand
import app.tandem.protocol.body.RunCommandList
import app.tandem.protocol.body.RunCommandRequest
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LIST =
    """{"b":{"name":"Lock","command":"loginctl lock-session"},"a":{"name":"Backup","command":"~/backup.sh"}}"""

class RunCommandBodyTest {
    @Test
    fun requestsUseTheDesktopFieldNames() {
        assertEquals(true, RunCommandRequest.List.toJson().getBoolean("requestCommandList"))
        assertEquals("k1", RunCommandRequest.Run("k1").toJson().getString("key"))
        assertEquals(true, RunCommandRequest.Setup.toJson().getBoolean("setup"))
        assertEquals(PacketTypes.RUNCOMMAND_REQUEST, RunCommandRequest.List.toPacket().type)
    }

    @Test
    fun theListIsAJsonStringAndKeepsItsOrder() {
        val body = buildJsonObject {
            put("commandList", LIST)
            put("canAddCommand", true)
        }
        val parsed = RunCommandList.parse(body)
        assertEquals(listOf("b", "a"), parsed.commands!!.map { it.id })
        assertEquals(DesktopCommand("b", "Lock", "loginctl lock-session"), parsed.commands!!.first())
        assertEquals(true, parsed.canAddCommand)
    }

    @Test
    fun damagedOrIncompleteListsGiveNoCommandsInsteadOfFailing() {
        assertTrue(RunCommandList.parse(buildJsonObject { put("commandList", "not json") }).commands!!.isEmpty())
        assertTrue(RunCommandList.parse(buildJsonObject { put("commandList", "[1,2]") }).commands!!.isEmpty())
        val partial = """{"x":{"name":"Only name"},"y":{"name":"Ok","command":"true"}}"""
        val kept = RunCommandList.parse(buildJsonObject { put("commandList", partial) })
        assertEquals(listOf("y"), kept.commands!!.map { it.id })
        assertNull(RunCommandList.parse(buildJsonObject { put("canAddCommand", false) }).commands)
    }
}

class RunCommandPluginTest {
    private val fake = FakeContext(setOf(PacketTypes.RUNCOMMAND_REQUEST))
    private val state = RunCommandState()
    private val plugin = RunCommandPlugin(fake.context, state)

    private fun packet(type: String = PacketTypes.RUNCOMMAND) =
        NetworkPacket(
            1,
            type,
            buildJsonObject {
                put("commandList", LIST)
                put("canAddCommand", true)
            },
        )

    @Test
    fun linkOpenAsksForTheList() =
        runBlocking {
            plugin.onCreate()
            assertEquals(true, fake.sent.single().body.getBoolean("requestCommandList"))
        }

    @Test
    fun aListUpdatesTheState() =
        runBlocking {
            assertTrue(plugin.onPacket(packet()))
            assertEquals(2, state.commands.value.size)
            assertTrue(state.canAdd.value)
            assertEquals(FakeContext.DEVICE, state.device)
        }

    @Test
    fun otherTypesAreIgnoredAndDestroyClears() =
        runBlocking {
            assertFalse(plugin.onPacket(packet(PacketTypes.PING)))
            plugin.onPacket(packet())
            plugin.onDestroy()
            assertTrue(state.commands.value.isEmpty())
            assertFalse(state.canAdd.value)
            assertNull(state.device)
        }
}

class RunCommandControllerTest {
    private val peers = FakePeerSender()
    private val state = RunCommandState()
    private val controller = RunCommandController(peers, state)

    @Test
    fun nothingIsSentBeforeTheDesktopHasListed() =
        runBlocking {
            assertFalse(controller.run("a"))
            assertTrue(peers.sent.isEmpty())
        }

    @Test
    fun commandsGoToTheDesktopThatSentTheList() =
        runBlocking {
            state.update("dev7", RunCommandList(listOf(DesktopCommand("a", "A", "true")), true))
            assertTrue(controller.run("a"))
            assertTrue(controller.setup())
            assertTrue(controller.refresh())
            assertEquals(listOf("dev7", "dev7", "dev7"), peers.sent.map { it.first })
            assertEquals("a", peers.sent[0].second.body.getString("key"))
            assertEquals(true, peers.sent[1].second.body.getBoolean("setup"))
        }
}
