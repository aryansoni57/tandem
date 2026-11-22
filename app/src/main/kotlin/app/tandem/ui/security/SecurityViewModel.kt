// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import app.tandem.state.ConnectionRoute
import app.tandem.state.ConnectionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SecurityUiState(
    val device: DeviceState? = null,
    val routes: List<ConnectionRoute> = emptyList(),
    val selectedRouteId: String = "",
    val latencyMs: Int? = null,
    val linksFromBrowser: Boolean = true,
    val browserMedia: Boolean = true,
)

/** Connection and security. The certificate and TLS details come from the connected device. */
@HiltViewModel
class SecurityViewModel
    @Inject
    constructor(
        deviceManager: DeviceManager,
        private val connection: ConnectionSource,
    ) : ViewModel() {
        val state: StateFlow<SecurityUiState> =
            combine(
                deviceManager.deviceStates,
                connection.routes,
                connection.selectedRouteId,
                connection.latencyMs,
            ) { devices, routes, selected, latency ->
                SecurityUiState(
                    device = devices.firstOrNull { it.paired && it.reachable } ?: devices.firstOrNull { it.paired },
                    routes = routes,
                    selectedRouteId = selected,
                    latencyMs = latency,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

        fun selectRoute(id: String) = connection.selectRoute(id)

        fun setLinksFromBrowser(enabled: Boolean) = connection.setLinksFromBrowser(enabled)

        fun setBrowserMedia(enabled: Boolean) = connection.setBrowserMedia(enabled)
    }
