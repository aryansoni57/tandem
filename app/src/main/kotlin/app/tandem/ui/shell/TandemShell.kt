// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.tandem.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tandem.ui.clipboard.ClipboardEntry
import app.tandem.ui.devices.DeviceDetailEntry
import app.tandem.ui.devices.DevicesEntry
import app.tandem.ui.home.HomeEntry
import app.tandem.ui.remote.MediaEntry
import app.tandem.ui.remote.PresenterEntry
import app.tandem.ui.remote.RemoteEntry
import app.tandem.ui.remote.RunCommandsEntry
import app.tandem.ui.security.SecurityEntry
import app.tandem.ui.share.ShareEntry
import app.tandem.ui.sync.SyncEntry
import kotlinx.coroutines.launch

/**
 * App shell: navigation bar with the four tabs (Sync shows a badge), the snackbar host and the
 * NavHost. [startKey] is a debug extra that starts the app on a given screen.
 */
@Composable
fun TandemShell(
    startKey: String? = null,
    viewModel: ShellViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val badge by viewModel.syncBadge.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = TopTab.entries.any { tab -> destination?.hierarchy?.any { it.hasRoute(tab.kClass) } == true }
    val onMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    val isSelected: (TopTab) -> Boolean = { tab -> destination?.hierarchy?.any { it.hasRoute(tab.kClass) } == true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar && !wide) {
                NavigationBar {
                    TopTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = isSelected(tab),
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { TabIcon(tab, badge) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showBar && wide) {
                // Tablets and landscape: the tabs move to a rail on the start edge.
                NavigationRail {
                    TopTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = isSelected(tab),
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { TabIcon(tab, badge) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        NavHost(
            navController = nav,
            startDestination = startRouteFor(startKey),
            modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
            composable<HomeRoute> {
                HomeEntry(
                    onSend = { nav.navigateTab(ShareRoute) },
                    onClipboard = { nav.navigate(ClipboardRoute) },
                    onTrackpad = { nav.navigateTab(RemoteRoute) },
                    onMedia = { nav.navigate(MediaRoute) },
                    onConnection = { nav.navigate(SecurityRoute) },
                    onDevices = { nav.navigate(DevicesRoute) },
                    onMessage = onMessage,
                )
            }
            composable<ShareRoute> { ShareEntry(onMessage = onMessage) }
            composable<SyncRoute> { SyncEntry() }
            composable<RemoteRoute> {
                RemoteEntry(
                    onPresenter = { nav.navigate(PresenterRoute) },
                    onCommands = { nav.navigate(CommandsRoute) },
                )
            }
            composable<ClipboardRoute> {
                ClipboardEntry(onBack = { nav.popBackStack() }, onMessage = onMessage)
            }
            composable<MediaRoute> { MediaEntry(onBack = { nav.popBackStack() }) }
            composable<PresenterRoute> { PresenterEntry(onBack = { nav.popBackStack() }) }
            composable<CommandsRoute> {
                RunCommandsEntry(onBack = { nav.popBackStack() }, onMessage = onMessage)
            }
            composable<SecurityRoute> { SecurityEntry(onBack = { nav.popBackStack() }) }
            composable<DevicesRoute> {
                DevicesEntry(
                    onBack = { nav.popBackStack() },
                    onOpenDevice = { id -> nav.navigate(DeviceDetailRoute(id)) },
                    onMessage = onMessage,
                )
            }
            composable<DeviceDetailRoute> { entry ->
                val route = entry.toRoute<DeviceDetailRoute>()
                DeviceDetailEntry(
                    deviceId = route.deviceId,
                    onBack = { nav.popBackStack() },
                    onMessage = onMessage,
                )
            }
        }
        }
    }
}

private const val WIDE_SCREEN_DP = 600

@Composable
private fun TabIcon(
    tab: TopTab,
    badge: Int,
) {
    if (tab == TopTab.Sync && badge > 0) {
        BadgedBox(badge = { Badge { Text(badge.toString()) } }) { Icon(tab.icon, contentDescription = null) }
    } else {
        Icon(tab.icon, contentDescription = null)
    }
}

/** Switches tabs without piling up back stack entries. */
private fun NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
