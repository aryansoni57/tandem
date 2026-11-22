// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import app.tandem.state.CommandIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandClassifierTest {
    @Test
    fun iconsFollowTheWordsInTheNameOrCommand() {
        assertEquals(CommandIcon.Lock, CommandClassifier.icon("Lock screen", "loginctl lock-session"))
        assertEquals(CommandIcon.Sleep, CommandClassifier.icon("Suspend", "systemctl suspend"))
        assertEquals(CommandIcon.Backup, CommandClassifier.icon("Nightly", "borg create repo::x"))
        assertEquals(CommandIcon.Screenshot, CommandClassifier.icon("Shot", "spectacle -b -n"))
        assertEquals(CommandIcon.Power, CommandClassifier.icon("Shut down", "systemctl poweroff"))
        assertEquals(CommandIcon.Generic, CommandClassifier.icon("Open notes", "kate ~/notes.md"))
    }

    @Test
    fun onlyPowerAndDataLossCommandsAskFirst() {
        assertTrue(CommandClassifier.isDestructive("Shut down", "systemctl poweroff"))
        assertTrue(CommandClassifier.isDestructive("Reboot", "reboot"))
        assertTrue(CommandClassifier.isDestructive("Clean", "rm -rf ~/tmp"))
        assertFalse(CommandClassifier.isDestructive("Suspend", "systemctl suspend"))
        assertFalse(CommandClassifier.isDestructive("Lock screen", "loginctl lock-session"))
        assertFalse(CommandClassifier.isDestructive("Open notes", "kate"))
    }

    @Test
    fun lockWordNeedsToBeWholeSoBlockedDoesNotMatch() {
        assertEquals(CommandIcon.Generic, CommandClassifier.icon("Blocklist", "update-blocklist"))
    }
}
