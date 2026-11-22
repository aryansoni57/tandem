// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.findmyphone

import app.tandem.plugins.FakeContext
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.body.FindMyPhoneRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindMyPhoneTest {
    private class FakeRinger : PhoneRinger {
        private val flow = MutableStateFlow(false)
        override val ringing: StateFlow<Boolean> = flow
        val starts = mutableListOf<String?>()
        var stops = 0

        override fun start(requester: String?) {
            starts += requester
            flow.value = true
        }

        override fun stop() {
            stops++
            flow.value = false
        }
    }

    private val ringer = FakeRinger()
    private val fake = FakeContext(setOf(PacketTypes.FINDMYPHONE_REQUEST))
    private val plugin =
        FindMyPhonePlugin(fake.context, ringer) { id -> if (id == FakeContext.DEVICE) "Aryan's laptop" else null }
    private val request = FindMyPhoneRequest.toPacket(id = 1)

    @Test
    fun requestBodyIsEmptyAndTypedForTheDesktop() {
        assertEquals(PacketTypes.FINDMYPHONE_REQUEST, request.type)
        assertTrue(request.body.isEmpty())
    }

    @Test
    fun firstRequestRingsWithTheLaptopName() =
        runBlocking {
            assertTrue(plugin.onPacket(request))
            assertEquals(listOf<String?>("Aryan's laptop"), ringer.starts)
            assertTrue(ringer.ringing.value)
        }

    @Test
    fun secondRequestWhileRingingStopsIt() =
        runBlocking {
            plugin.onPacket(request)
            plugin.onPacket(request)
            assertEquals(1, ringer.stops)
            assertFalse(ringer.ringing.value)
            assertEquals(1, ringer.starts.size)
        }

    @Test
    fun otherPacketsAreNotHandled() =
        runBlocking {
            assertFalse(plugin.onPacket(NetworkPacket(1, PacketTypes.PING, JsonObject(emptyMap()))))
            assertTrue(ringer.starts.isEmpty())
        }

    @Test
    fun descriptorRingsAndAsksInBothDirections() {
        val descriptor = FindMyPhoneDescriptor.create(ringer) { null }
        assertEquals(setOf(PacketTypes.FINDMYPHONE_REQUEST), descriptor.incoming)
        assertEquals(setOf(PacketTypes.FINDMYPHONE_REQUEST), descriptor.outgoing)
    }
}
