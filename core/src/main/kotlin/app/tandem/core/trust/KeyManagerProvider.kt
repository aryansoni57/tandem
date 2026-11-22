// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.trust

import app.tandem.core.identity.IdentityLoader
import app.tandem.transport.KeyMaterialProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the TLS key material for our loaded identity. */
@Singleton
class KeyManagerProvider
    @Inject
    constructor(
        private val identities: IdentityLoader,
    ) {
        suspend fun material(): KeyMaterialProvider = identities.load().material
    }
