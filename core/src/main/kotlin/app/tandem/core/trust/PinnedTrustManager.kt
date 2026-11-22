// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.trust

import app.tandem.transport.TrustPolicy
import javax.inject.Inject

/** The transport layer's view of the trust store: pinned certificate DER per deviceId. */
class PinnedTrustManager
    @Inject
    constructor(
        private val store: TrustStore,
    ) : TrustPolicy {
        override fun pinnedCertificate(deviceId: String): ByteArray? = store.get(deviceId)?.certDer
    }
