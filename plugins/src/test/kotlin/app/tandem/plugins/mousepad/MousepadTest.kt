// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.mousepad

import app.tandem.plugins.FakePeerSender
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.MousepadBody
import app.tandem.protocol.body.SpecialKey
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getInt
import app.tandem.protocol.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MousepadBodyTest {
    @Test
    fun moveAndScrollCarryDeltas() {
        val move = MousepadBody.Move(3f, -4.5f).toJson()
        assertEquals(3.0, move["dx"]!!.jsonPrimitive.doubleOrNull!!, 0.0)
        assertEquals(-4.5, move["dy"]!!.jsonPrimitive.doubleOrNull!!, 0.0)
        val scroll = MousepadBody.Scroll(2f).toJson()
        assertEquals(true, scroll.getBoolean("scroll"))
        assertEquals(2.0, scroll["dy"]!!.jsonPrimitive.doubleOrNull!!, 0.0)
    }

    @Test
    fun clicksUseTheDesktopFieldNames() {
        val names =
            mapOf(
                MousepadBody.Click.Single to "singleclick",
                MousepadBody.Click.Double to "doubleclick",
                MousepadBody.Click.Middle to "middleclick",
                MousepadBody.Click.Right to "rightclick",
                MousepadBody.Click.Hold to "singlehold",
                MousepadBody.Click.Release to "singlerelease",
            )
        for ((click, field) in names) {
            assertEquals(true, MousepadBody.ClickEvent(click).toJson().getBoolean(field))
        }
    }

    @Test
    fun keysAndModifiers() {
        val text = MousepadBody.Text("a", app.tandem.protocol.body.KeyModifiers(ctrl = true)).toJson()
        assertEquals("a", text.getString("key"))
        assertEquals(true, text.getBoolean("ctrl"))
        assertEquals(null, text.getBoolean("shift"))
        assertEquals(12, MousepadBody.Special(SpecialKey.Enter).toJson().getInt("specialKey"))
        assertEquals(8, SpecialKey.PageUp.code)
        assertEquals(PacketTypes.MOUSEPAD_REQUEST, MousepadBody.Scroll(1f).toPacket().type)
    }
}

class MousepadSenderTest {
    private val peers = FakePeerSender()
    private var device: String? = "dev1"
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val sender = MousepadSender(peers, { device }, scope)

    @Test
    fun clickGoesToTheTargetDevice() =
        runBlocking {
            assertTrue(sender.click(MousepadBody.Click.Right))
            assertEquals("dev1", peers.sent.single().first)
        }

    @Test
    fun nothingIsSentWithoutAConnectedDevice() =
        runBlocking {
            device = null
            assertFalse(sender.click(MousepadBody.Click.Single))
            assertTrue(peers.sent.isEmpty())
        }

    @Test
    fun typedTextSplitsLinesIntoEnterKeys() =
        runBlocking {
            assertTrue(sender.type("ab\ncd"))
            val bodies = peers.sent.map { it.second.body }
            assertEquals(listOf("ab", null, "cd"), bodies.map { it.getString("key") })
            assertEquals(12, bodies[1].getInt("specialKey"))
        }

    @Test
    fun specialKeyAndTypedTextCarryModifiers() =
        runBlocking {
            val ctrl = app.tandem.protocol.body.KeyModifiers(ctrl = true)
            sender.special(SpecialKey.Left, ctrl)
            sender.type("c", ctrl)
            val bodies = peers.sent.map { it.second.body }
            assertEquals(4, bodies[0].getInt("specialKey"))
            assertEquals(true, bodies[0].getBoolean("ctrl"))
            assertEquals("c", bodies[1].getString("key"))
            assertEquals(true, bodies[1].getBoolean("ctrl"))
        }

    @Test
    fun movesThatQueueUpAreSummed() =
        runBlocking {
            // Unconfined runs the collector at once, so the first move is sent alone.
            sender.move(1f, 1f)
            sender.move(2f, 3f)
            kotlinx.coroutines.yield()
            val total = peers.sent.sumOf { it.second.body["dx"]!!.jsonPrimitive.doubleOrNull!! }
            assertEquals(3.0, total, 0.0)
        }
}
