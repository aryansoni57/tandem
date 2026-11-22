// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share.target

import app.tandem.core.device.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareShortcutsTest {
    private val devices =
        listOf(
            DeviceState(deviceId = "a", name = "Laptop", paired = true),
            DeviceState(deviceId = "b", name = "Unpaired", paired = false),
        )

    @Test
    fun pairedDeviceMatchesItsShortcutId() = assertEquals("Laptop", ShareShortcuts.target("a", devices)?.name)

    @Test
    fun unpairedUnknownOrMissingIdGivesNull() {
        assertNull(ShareShortcuts.target("b", devices))
        assertNull(ShareShortcuts.target("zzz", devices))
        assertNull(ShareShortcuts.target(null, devices))
    }
}
