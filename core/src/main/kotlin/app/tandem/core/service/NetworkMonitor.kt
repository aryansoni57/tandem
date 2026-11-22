// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls [onChange] when the default network appears or its addresses change. Calls are
 * debounced by [DEBOUNCE_MS] so that a burst of callbacks announces only once.
 */
@Singleton
class NetworkMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private var pending: Job? = null

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = schedule()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties,
                ) = schedule()
            }

        private var onChangeAction: (() -> Unit)? = null
        private var scope: CoroutineScope? = null

        fun start(
            scope: CoroutineScope,
            onChange: () -> Unit,
        ) {
            this.scope = scope
            onChangeAction = onChange
            connectivity.registerDefaultNetworkCallback(callback)
        }

        fun stop() {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            pending?.cancel()
            scope = null
            onChangeAction = null
        }

        private fun schedule() {
            val owner = scope ?: return
            pending?.cancel()
            pending =
                owner.launch {
                    delay(DEBOUNCE_MS)
                    onChangeAction?.invoke()
                }
        }

        companion object {
            const val DEBOUNCE_MS = 200L
        }
    }
