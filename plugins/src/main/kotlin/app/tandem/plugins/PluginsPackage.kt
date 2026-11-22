// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins

/**
 * Marker for the plugins module. Each plugin package holds its plugin class, its Android side
 * (sinks, monitors) and a Hilt module that contributes one PluginDescriptor with @IntoSet.
 */
object PluginsPackage
