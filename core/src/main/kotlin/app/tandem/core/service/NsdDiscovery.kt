// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.LocalService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS: registers `_kdeconnect._udp` with our TCP port and TXT records, and discovers peers.
 * On a resolve, the device manager sends a unicast UDP identity to the host.
 */
@Singleton
class NsdDiscovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deviceManager: DeviceManager,
        private val multicast: MulticastLockHolder,
    ) {
        private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        private var registration: NsdManager.RegistrationListener? = null
        private var discovery: NsdManager.DiscoveryListener? = null

        fun start(service: LocalService) {
            if (registration != null) return
            multicast.acquire()

            val info =
                NsdServiceInfo().apply {
                    serviceName = service.deviceId
                    serviceType = SERVICE_TYPE
                    port = service.tcpPort
                    setAttribute("id", service.deviceId)
                    setAttribute("name", service.name)
                    setAttribute("type", "phone")
                    setAttribute("protocol", "8")
                }
            val registrationListener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit
                }
            registration = registrationListener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)

            val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (serviceInfo.serviceName == service.deviceId) return
                        resolve(serviceInfo)
                    }
                }
            discovery = discoveryListener
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }

        fun stop() {
            registration?.let { runCatching { nsd.unregisterService(it) } }
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            registration = null
            discovery = null
            multicast.release()
        }

        @Suppress("DEPRECATION")
        private fun resolve(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        resolved.host?.let { deviceManager.onMdnsResolved(it) }
                    }
                },
            )
        }

        companion object {
            const val SERVICE_TYPE = "_kdeconnect._udp."
        }
    }
