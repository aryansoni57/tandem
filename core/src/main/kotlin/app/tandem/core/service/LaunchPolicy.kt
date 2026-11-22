// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

/**
 * Whether the app should start the connection service when its process starts. Paired devices need
 * the service to accept and dial links; without it nothing listens and every device shows as offline.
 */
object LaunchPolicy {
    fun shouldStartConnectionService(hasPairedDevices: Boolean): Boolean = hasPairedDevices
}
