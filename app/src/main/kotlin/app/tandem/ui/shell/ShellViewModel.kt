// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.state.SyncSettingsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The badge on the Sync tab: items the laptop handled today. */
@HiltViewModel
class ShellViewModel
    @Inject
    constructor(
        sync: SyncSettingsSource,
    ) : ViewModel() {
        val syncBadge: StateFlow<Int> =
            sync.handled.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    }
