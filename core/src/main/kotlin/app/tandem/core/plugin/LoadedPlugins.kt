// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.NetworkPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * The plugin instances loaded for one device on one link. One failing plugin never stops the others:
 * its exception goes to [onError].
 */
class LoadedPlugins(
    private val scope: CoroutineScope,
    private val plugins: List<Plugin>,
    private val onError: (Throwable) -> Unit = {},
) {
    suspend fun start() {
        for (plugin in plugins) {
            guarded(Unit) { plugin.onCreate() }
        }
    }

    /** Offers the packet to each plugin in order. Returns true when one of them handled it. */
    suspend fun dispatch(packet: NetworkPacket): Boolean {
        for (plugin in plugins) {
            if (guarded(false) { plugin.onPacket(packet) }) return true
        }
        return false
    }

    suspend fun destroy() {
        try {
            for (plugin in plugins) {
                guarded(Unit) { plugin.onDestroy() }
            }
        } finally {
            scope.cancel()
        }
    }

    // A plugin that throws must not take the device link or the other plugins down.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guarded(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            fallback
        }
}
