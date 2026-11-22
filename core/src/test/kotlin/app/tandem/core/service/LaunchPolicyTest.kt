// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyTest {
    @Test
    fun pairedDevicesStartTheServiceAtLaunch() {
        assertTrue(LaunchPolicy.shouldStartConnectionService(hasPairedDevices = true))
    }

    @Test
    fun nothingPairedMeansNoServiceUntilTheUserAsks() {
        assertFalse(LaunchPolicy.shouldStartConnectionService(hasPairedDevices = false))
    }
}
