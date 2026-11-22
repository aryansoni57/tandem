// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import app.tandem.R
import app.tandem.designsystem.icon.TandemIcons
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/*
 * Typed routes for Navigation Compose. The four tabs are top level; every other route is pushed on
 * top of them and hides the navigation bar.
 */

@Serializable data object HomeRoute

@Serializable data object ShareRoute

@Serializable data object SyncRoute

@Serializable data object RemoteRoute

@Serializable data object ClipboardRoute

@Serializable data object MediaRoute

@Serializable data object PresenterRoute

@Serializable data object CommandsRoute

@Serializable data object DevicesRoute

@Serializable data object SecurityRoute

@Serializable data class DeviceDetailRoute(val deviceId: String)

/** Bottom navigation tabs in mockup order. */
enum class TopTab(
    val route: Any,
    val kClass: KClass<*>,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home(HomeRoute, HomeRoute::class, R.string.tab_home, Icons.Filled.Home),
    Share(ShareRoute, ShareRoute::class, R.string.tab_share, Icons.Filled.Share),
    Sync(SyncRoute, SyncRoute::class, R.string.tab_sync, TandemIcons.Sync),
    Remote(RemoteRoute, RemoteRoute::class, R.string.tab_remote, TandemIcons.Mouse),
}

/** Maps the debug start extra to a route. Unknown or missing keys start at Home. */
fun startRouteFor(key: String?): Any =
    when (key) {
        "share" -> ShareRoute
        "sync" -> SyncRoute
        "remote" -> RemoteRoute
        "clipboard" -> ClipboardRoute
        "media" -> MediaRoute
        "presenter" -> PresenterRoute
        "commands" -> CommandsRoute
        "devices" -> DevicesRoute
        "security" -> SecurityRoute
        else -> HomeRoute
    }
