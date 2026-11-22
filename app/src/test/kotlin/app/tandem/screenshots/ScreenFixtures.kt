// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.screenshots

import androidx.compose.runtime.Composable
import app.tandem.core.device.BatteryState
import app.tandem.core.device.DeviceState
import app.tandem.sample.SampleData
import app.tandem.state.KeyMods
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.Transfer
import app.tandem.state.TransferDirection
import app.tandem.state.TransferKind
import app.tandem.ui.clipboard.ClipFilter
import app.tandem.ui.clipboard.ClipboardScreen
import app.tandem.ui.clipboard.ClipboardUiState
import app.tandem.ui.devices.DeviceDetailScreen
import app.tandem.ui.devices.DevicesScreen
import app.tandem.ui.findphone.FindMyPhoneScreen
import app.tandem.ui.home.HomeScreen
import app.tandem.ui.home.HomeUiState
import app.tandem.ui.remote.CommandsUiState
import app.tandem.ui.remote.MediaScreen
import app.tandem.ui.remote.MediaUiState
import app.tandem.ui.remote.PresenterScreen
import app.tandem.ui.remote.PresenterTimer
import app.tandem.ui.remote.PresenterUiState
import app.tandem.ui.remote.RemoteMode
import app.tandem.ui.remote.RemoteScreen
import app.tandem.ui.remote.RemoteUiState
import app.tandem.ui.remote.RunCommandsScreen
import app.tandem.ui.security.SecurityScreen
import app.tandem.ui.security.SecurityUiState
import app.tandem.ui.share.ShareScreen
import app.tandem.ui.share.ShareUiState
import app.tandem.ui.sync.SyncScreen
import app.tandem.ui.sync.SyncUiState

/** One screen filled with sample data, shared by the screenshot, font scale and accessibility tests. */
class ScreenFixture(
    val name: String,
    val content: @Composable () -> Unit,
)

object ScreenFixtures {
    private val laptop = DeviceState(deviceId = "abc", name = "Aryan's laptop", paired = true, reachable = true)

    /** The Devices list with a paired laptop and a nearby unpaired phone. */
    @Composable
    fun devicesList() {
        val nearby = DeviceState(deviceId = "def", name = "Maya's phone", reachable = true)
        DevicesScreen(
            devices = listOf(laptop, nearby),
            batteries = mapOf("abc" to BatteryState(64, true)),
            pairingTargetId = null,
            onBack = {},
            onOpenDevice = {},
            onRing = {},
            onUnpair = {},
            onPairNearby = {},
            onClosePairing = {},
            onRequestPairing = {},
            onAcceptPairing = {},
            onRejectPairing = {},
            onRefresh = {},
        )
    }

    val all: List<ScreenFixture> =
        listOf(
            ScreenFixture("home") {
                val today =
                    listOf(
                        Transfer(
                            "t1", "IMG_2041.jpg", TransferKind.Image,
                            TransferDirection.ToLaptop, 4.2f, null, "9:41",
                        ),
                        Transfer(
                            "t2", "project-brief.pdf", TransferKind.Document,
                            TransferDirection.FromLaptop, 1.1f, null, "9:20",
                        ),
                    )
                HomeScreen(
                    HomeUiState(laptop, 64, true, 4, SampleData.nowPlaying, today),
                    {}, {}, {}, {}, {}, {}, {}, {},
                )
            },
            ScreenFixture("share") {
                val running =
                    Transfer("t1", "holiday.mp4", TransferKind.Video, TransferDirection.ToLaptop, 812f, 0.42f, "now")
                val state = ShareUiState(transfers = listOf(running), storage = SampleData.storage)
                ShareScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
            },
            ScreenFixture("sync") {
                val state =
                    SyncUiState(
                        notifications = NotificationSyncSettings(showOnLaptop = true, screenOffOnly = false),
                        apps = SampleData.syncApps,
                        handled = SampleData.handled,
                    )
                SyncScreen(state, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
            },
            ScreenFixture("clipboard") {
                val state = ClipboardUiState(filter = ClipFilter.All, items = SampleData.clips)
                ClipboardScreen(state, {}, {}, {}, {}, {}, {})
            },
            ScreenFixture("remote") {
                RemoteScreen(RemoteUiState(), {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            },
            ScreenFixture("remote_keyboard") {
                val state = RemoteUiState(mode = RemoteMode.Keyboard, mods = KeyMods(ctrl = true))
                RemoteScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            },
            ScreenFixture("media") {
                val state = MediaUiState(SampleData.nowPlaying, players = listOf("Browser", "Video player"))
                MediaScreen(state, {}, {}, {}, {}, {}, {}, {})
            },
            ScreenFixture("presenter") {
                PresenterScreen(
                    PresenterUiState(SampleData.deck, PresenterTimer(accumulatedMs = 754_000L), 0L, false),
                    {}, {}, {}, {}, { _, _ -> }, {}, {},
                )
            },
            ScreenFixture("commands") {
                RunCommandsScreen(CommandsUiState(SampleData.commands, canAdd = true), {}, {}, {}, {}, {})
            },
            ScreenFixture("security") {
                SecurityScreen(
                    SecurityUiState(laptop, SampleData.routes, SampleData.routes.first().id, 4, true, true),
                    {}, {}, {}, {},
                )
            },
            ScreenFixture("devices") { devicesList() },
            ScreenFixture("device_detail") {
                DeviceDetailScreen(laptop, BatteryState(64, true), null, {}, {}, {}, {}, {})
            },
            ScreenFixture("find_my_phone") { FindMyPhoneScreen("Aryan's laptop") {} },
        )
}
