// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tandem.state.CallsSmsSettings
import app.tandem.state.HandledItem
import app.tandem.state.NotificationSyncSettings
import app.tandem.state.SyncApp
import app.tandem.state.SyncCategory
import app.tandem.state.SyncSettingsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SyncUiState(
    val tab: SyncTab = SyncTab.Notifications,
    val notifications: NotificationSyncSettings = NotificationSyncSettings(showOnLaptop = true, screenOffOnly = false),
    val categories: Set<SyncCategory> = emptySet(),
    val apps: List<SyncApp> = emptyList(),
    val calls: CallsSmsSettings = CallsSmsSettings(true, true, true),
    val handled: List<HandledItem> = emptyList(),
    val smsBannerHidden: Boolean = false,
)

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val source: SyncSettingsSource,
    ) : ViewModel() {
        private val tab = MutableStateFlow(SyncTab.Notifications)
        private val categories = MutableStateFlow<Set<SyncCategory>>(emptySet())
        private val bannerHidden = MutableStateFlow(false)

        private val base =
            combine(tab, categories, source.notifications, source.apps) { t, selected, notes, apps ->
                SyncUiState(
                    tab = t,
                    notifications = notes,
                    categories = selected,
                    apps = SyncCategoryFilter.visible(apps, selected),
                )
            }

        val state: StateFlow<SyncUiState> =
            combine(base, source.calls, source.handled, bannerHidden) { state, calls, handled, hidden ->
                state.copy(calls = calls, handled = handled, smsBannerHidden = hidden)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

        fun dismissSmsBanner() {
            bannerHidden.value = true
        }

        fun selectTab(next: SyncTab) {
            tab.value = next
        }

        fun toggleCategory(category: SyncCategory) {
            categories.value = SyncCategoryFilter.toggle(categories.value, category)
        }

        fun setShowOnLaptop(enabled: Boolean) = source.setShowOnLaptop(enabled)

        fun setScreenOffOnly(enabled: Boolean) = source.setScreenOffOnly(enabled)

        fun setAppEnabled(
            id: String,
            enabled: Boolean,
        ) = source.setAppEnabled(id, enabled)

        fun setTextFromLaptop(enabled: Boolean) = source.setTextFromLaptop(enabled)

        fun setCallAlerts(enabled: Boolean) = source.setCallAlerts(enabled)

        fun setPauseMediaOnCall(enabled: Boolean) = source.setPauseMediaOnCall(enabled)

    }
