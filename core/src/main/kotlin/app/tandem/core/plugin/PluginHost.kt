// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.Capabilities
import app.tandem.protocol.Identity
import javax.inject.Inject
import javax.inject.Singleton

/** Creates the plugin instances that a peer's capabilities and pairing state allow. */
@Singleton
class PluginHost
    @Inject
    constructor(
        private val registry: PluginRegistry,
    ) {
        fun instantiate(
            peer: Identity,
            paired: Boolean,
            context: (String) -> PluginContext,
        ): List<Plugin> =
            registry.all()
                .filter {
                    Capabilities.shouldLoad(
                        it.toCapabilitySpec(),
                        peerIncoming = peer.incomingCapabilities,
                        peerOutgoing = peer.outgoingCapabilities,
                        paired = paired,
                    )
                }
                .map { it.factory(context(peer.deviceId)) }
    }
