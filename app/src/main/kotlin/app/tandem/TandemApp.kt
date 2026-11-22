// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem

import android.app.Application
import app.tandem.core.device.DeviceManager
import app.tandem.core.service.ConnectionService
import app.tandem.core.service.LaunchPolicy
import app.tandem.core.trust.TrustStore
import app.tandem.plugins.mpris.MprisMediaService
import app.tandem.ui.pairing.IncomingPairNotification
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TandemApp : Application() {
    @Inject lateinit var deviceManager: DeviceManager

    @Inject lateinit var trust: TrustStore

    @Inject lateinit var shareShortcuts: app.tandem.ui.share.target.ShareShortcuts

    @Inject lateinit var mprisState: app.tandem.plugins.mpris.MprisState

    override fun onCreate() {
        super.onCreate()
        IncomingPairNotification.ensureChannel(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (LaunchPolicy.shouldStartConnectionService(trust.hasAny())) ConnectionService.start(this@TandemApp)
        }
        shareShortcuts.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // The laptop's media shows on the lock screen while a player is open.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            mprisState.nowPlaying.map { it != null }.distinctUntilChanged().collect { active ->
                if (active) MprisMediaService.start(this@TandemApp)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            deviceManager.pairingPrompts.collect { prompt ->
                IncomingPairNotification.show(this@TandemApp, prompt)
            }
        }
    }
}
