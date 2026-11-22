// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.sample

import app.tandem.state.CallsSmsSettings
import app.tandem.state.ClipItem
import app.tandem.state.ClipSource
import app.tandem.state.CommandIcon
import app.tandem.state.ConnectionRoute
import app.tandem.state.HandledItem
import app.tandem.state.HandledKind
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.NowPlaying
import app.tandem.state.RunCommand
import app.tandem.state.SharedStorage
import app.tandem.state.SlideDeck
import app.tandem.state.SyncApp
import app.tandem.state.SyncCategory

/** Realistic sample content from the Material mockup. Used by the Fake* sources and previews only. */
object SampleData {
    val storage =
        SharedStorage(
            enabled = true,
            usedGb = 96f,
            totalGb = 128f,
            folders = listOf("DCIM", "Download", "Documents"),
            candidates = listOf("Music", "Pictures"),
        )

    val clips =
        listOf(
            ClipItem("c1", "npm i @tandem/cli", ClipSource.Laptop, "From laptop · 9:10", monospace = true),
            ClipItem("c2", "Gate B14, boarding 18:20", ClipSource.Phone, "From phone · 9:38", monospace = false),
            ClipItem(
                "c3",
                "https://docs.example.com/spec-v2",
                ClipSource.Laptop,
                "From laptop · yesterday",
                monospace = false,
            ),
            ClipItem("c4", "+91 98765 43210", ClipSource.Phone, "From phone · yesterday", monospace = false),
        )

    val syncApps =
        listOf(
            SyncApp("messages", "Messages", SyncCategory.Messaging, true, "Reply enabled", "M"),
            SyncApp("mail", "Mail", SyncCategory.Work, true, "Preview only", "M"),
            SyncApp("calendar", "Calendar", SyncCategory.Work, true, "Reminders", "C"),
            SyncApp("bank", "Bank", SyncCategory.Shopping, false, "Hidden · sensitive", "B"),
        )

    val notificationSettings = NotificationSyncSettings(showOnLaptop = true, screenOffOnly = false)

    val calls =
        CallsSmsSettings(
            textFromLaptop = true,
            callAlerts = true,
            pauseMediaOnCall = true,
        )

    val handled =
        listOf(
            HandledItem("h1", "Maya Rao", "You replied “Yes! Leaving at 6:30.”", HandledKind.Sms, "9:35", "MR"),
            HandledItem("h2", "+91 98123 45678", "Ringer muted from laptop", HandledKind.Missed, "9:12", null),
            HandledItem("h3", "Dev", "You replied “Re-running CI now”", HandledKind.Messages, "9:02", "DV"),
        )

    val nowPlaying =
        NowPlaying(
            title = "Night Drive",
            artist = "Kora Lane",
            source = "Browser",
            positionMs = 72_000L,
            durationMs = 225_000L,
            playing = true,
            volume = 55,
        )

    val commands =
        listOf(
            RunCommand("lock", "Lock screen", "loginctl lock-session", CommandIcon.Lock, destructive = false),
            RunCommand("suspend", "Suspend", "systemctl suspend", CommandIcon.Sleep, destructive = false),
            RunCommand("backup", "Start backup", "~/bin/backup.sh --quick", CommandIcon.Backup, destructive = false),
            RunCommand("screenshot", "Screenshot", "spectacle -b -n", CommandIcon.Screenshot, destructive = false),
            RunCommand("poweroff", "Shut down", "systemctl poweroff", CommandIcon.Power, destructive = true),
        )

    val deck = SlideDeck(name = "Q4 roadmap.pdf", slide = 2, total = 12)

    val routes =
        listOf(
            ConnectionRoute("wifi", "Local Wi‑Fi", "Home‑5G · 192.168.1.24", viaVpn = false),
            ConnectionRoute("vpn", "OpenVPN", "remote-office.ovpn · away from home", viaVpn = true),
        )

    const val LATENCY_MS = 4
    const val FINGERPRINT = "A4:7F:19:C2:8B:03:5E:D1\n90:6A:B7:44:E2:1C:F8:3D"

    /** Phone battery levels for the last 12 hours, oldest first. */
    val batteryHistory = listOf(1f, .96f, .93f, .90f, .88f, .86f, .83f, .79f, .76f, .78f, .80f, .82f)
}
