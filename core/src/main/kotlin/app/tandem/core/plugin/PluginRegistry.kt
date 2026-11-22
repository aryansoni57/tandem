// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.plugin

import app.tandem.protocol.CapabilitySpec
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton

/** Declares the multibound set so that an empty set still injects in Phase 1. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {
    @Multibinds
    abstract fun pluginDescriptors(): Set<@JvmSuppressWildcards PluginDescriptor>
}

/** All plugin descriptors. Phase 1 contributes none. */
@Singleton
class PluginRegistry
    @Inject
    constructor(
        private val descriptors: Set<@JvmSuppressWildcards PluginDescriptor>,
    ) {
        fun all(): List<PluginDescriptor> = descriptors.sortedBy { it.id }

        fun capabilitySpecs(): List<CapabilitySpec> = all().map { it.toCapabilitySpec() }
    }
