// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.identity

import app.tandem.transport.KeyMaterialProvider
import java.security.cert.X509Certificate

/** Where our private key lives. Keystore is preferred; the wrapped software key is the fallback. */
enum class IdentityBackend { KEYSTORE, WRAPPED_SOFTWARE }

/** Our deviceId, certificate and private key handle, as used by the TLS layer. */
class LocalIdentity(
    val deviceId: String,
    val backend: IdentityBackend,
    val material: KeyMaterialProvider,
) {
    val certificate: X509Certificate get() = material.certificate
}
