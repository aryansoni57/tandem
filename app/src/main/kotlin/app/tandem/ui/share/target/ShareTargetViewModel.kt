// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share.target

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Paired devices offered as share targets, with reachable ones first. */
@HiltViewModel
class ShareTargetViewModel
    @Inject
    constructor(
        deviceManager: DeviceManager,
    ) : ViewModel() {
        val devices: StateFlow<List<DeviceState>> =
            deviceManager.deviceStates
                .map { list -> list.filter { it.paired }.sortedByDescending { it.reachable } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }
