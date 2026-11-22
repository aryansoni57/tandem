// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Holds a Wi-Fi multicast lock only while mDNS discovery runs. The lock costs battery. */
@Singleton
class MulticastLockHolder
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val lock =
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("tandem:mdns")
                .apply { setReferenceCounted(true) }

        fun acquire() {
            if (!lock.isHeld) lock.acquire()
        }

        fun release() {
            if (lock.isHeld) lock.release()
        }
    }
