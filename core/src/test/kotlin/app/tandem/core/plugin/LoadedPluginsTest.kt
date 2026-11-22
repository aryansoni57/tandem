// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedPluginsTest {
    private val packet = NetworkPacket(1, PacketTypes.PING, JsonObject(emptyMap()))

    private class Recorder(
        private val handles: Boolean,
        private val throws: Boolean = false,
    ) : Plugin {
        var packets = 0
        var destroyed = false

        override suspend fun onPacket(packet: NetworkPacket): Boolean {
            packets++
            if (throws) error("boom")
            return handles
        }

        override suspend fun onDestroy() {
            destroyed = true
        }
    }

    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Test
    fun dispatchStopsAtTheFirstPluginThatHandles() {
        val first = Recorder(handles = true)
        val second = Recorder(handles = true)
        val loaded = LoadedPlugins(scope(), listOf(first, second))
        assertTrue(runBlocking { loaded.dispatch(packet) })
        assertEquals(1, first.packets)
        assertEquals(0, second.packets)
    }

    @Test
    fun dispatchReturnsFalseWhenNobodyHandles() {
        val loaded = LoadedPlugins(scope(), listOf(Recorder(handles = false)))
        assertFalse(runBlocking { loaded.dispatch(packet) })
    }

    @Test
    fun failingPluginIsReportedAndOthersStillSeeThePacket() {
        val errors = mutableListOf<Throwable>()
        val broken = Recorder(handles = false, throws = true)
        val working = Recorder(handles = true)
        val loaded = LoadedPlugins(scope(), listOf(broken, working), onError = { errors += it })
        assertTrue(runBlocking { loaded.dispatch(packet) })
        assertEquals(1, errors.size)
        assertEquals(1, working.packets)
    }

    @Test
    fun destroyCallsEveryPluginAndCancelsTheScope() {
        val scope = scope()
        val a = Recorder(handles = false)
        val b = Recorder(handles = false)
        runBlocking { LoadedPlugins(scope, listOf(a, b)).destroy() }
        assertTrue(a.destroyed && b.destroyed)
        assertFalse(scope.isActive)
    }
}
