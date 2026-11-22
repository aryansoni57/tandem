// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.CapabilitySpec

/** Static description of a plugin. Each plugin package contributes one via @IntoSet. */
data class PluginDescriptor(
    val id: String,
    val incoming: Set<String>,
    val outgoing: Set<String>,
    val requiredPermissions: List<String>,
    val optionalPermissions: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    val listenToUnpaired: Boolean = false,
    val minSdk: Int = 26,
    val factory: (PluginContext) -> Plugin,
) {
    fun toCapabilitySpec(enabled: Boolean = enabledByDefault): CapabilitySpec =
        CapabilitySpec(
            id = id,
            incoming = incoming,
            outgoing = outgoing,
            enabled = enabled,
            minSdk = minSdk,
            listenToUnpaired = listenToUnpaired,
        )
}
