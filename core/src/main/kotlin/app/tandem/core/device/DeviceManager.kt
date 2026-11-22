// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.core.device

import android.os.Build
import android.util.Log
import app.tandem.core.data.SettingsStore
import app.tandem.core.data.TrustedDeviceEntity
import app.tandem.core.identity.CertificateFactory
import app.tandem.core.identity.IdentityLoader
import app.tandem.core.identity.LocalIdentity
import app.tandem.core.plugin.LoadedPlugins
import app.tandem.core.plugin.PluginContext
import app.tandem.core.plugin.PluginHost
import app.tandem.core.plugin.PluginRegistry
import app.tandem.core.trust.PinnedTrustManager
import app.tandem.core.trust.TrustStore
import app.tandem.protocol.Capabilities
import app.tandem.protocol.DeviceType
import app.tandem.protocol.Identity
import app.tandem.protocol.IdentityException
import app.tandem.protocol.IdentityPackets
import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketCodec
import app.tandem.protocol.PacketTypes
import app.tandem.protocol.PairingAction
import app.tandem.protocol.PairingEvent
import app.tandem.protocol.PairingState
import app.tandem.protocol.PairingStateMachine
import app.tandem.protocol.VerificationKey
import app.tandem.protocol.getBoolean
import app.tandem.protocol.getLong
import app.tandem.transport.AddressFilter
import app.tandem.transport.DuplicateLinkPolicy
import app.tandem.transport.HandshakeCoordinator
import app.tandem.transport.LinkDecision
import app.tandem.transport.PeerSession
import app.tandem.transport.PortRange
import app.tandem.transport.RateLimiter
import app.tandem.transport.TcpListener
import app.tandem.transport.TlsUpgrader
import app.tandem.transport.UdpDiscovery
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Our name and TCP port, for mDNS registration. */
data class LocalService(val deviceId: String, val name: String, val tcpPort: Int)

/**
 * Owns discovery, both connection flows, duplicate handling, pairing and the device list.
 *
 * Pairing actions and packets run under [lock], so one device's events stay in order.
 * A one-second tick enforces the pairing timeouts.
 */
@Singleton
class DeviceManager
    @Inject
    constructor(
        private val identities: IdentityLoader,
        private val settings: SettingsStore,
        private val trust: TrustStore,
        private val pinnedTrust: PinnedTrustManager,
        private val plugins: PluginRegistry,
        private val pluginHost: PluginHost,
    ) : PeerSender {
        private val errorHandler =
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "unhandled error in device manager", error)
            }
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
        private val lock = Mutex()
        private val started = AtomicBoolean(false)
        private val devices = ConcurrentHashMap<String, Device>()
        private val jobs = mutableListOf<Job>()

        private val states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())

        /** The device list for the UI: paired and nearby devices, sorted by name. */
        val deviceStates: StateFlow<List<DeviceState>> =
            states
                .map { map -> map.values.sortedBy { it.name.lowercase() } }
                .stateIn(appScope, SharingStarted.Eagerly, emptyList())

        private val prompts = MutableSharedFlow<PairingPrompt>(extraBufferCapacity = PROMPT_BUFFER)

        /** Emitted when a peer asks us to pair. The app turns it into a notification. */
        val pairingPrompts: SharedFlow<PairingPrompt> = prompts.asSharedFlow()

        private val discoveryLimiter = RateLimiter()

        // Separate limiters: an accepted socket from a peer must not be refused because we just dialed
        // that peer for its UDP announce (or the reverse). Sharing one limiter closed sockets unread.
        private val acceptLimiter = RateLimiter()
        private val dialLimiter = RateLimiter()
        private val udp = UdpDiscovery()

        @Volatile private var udpSocket: DatagramSocket? = null

        @Volatile private var listener: TcpListener? = null

        @Volatile private var coordinator: HandshakeCoordinator? = null

        @Volatile private var local: LocalIdentity? = null

        @Volatile private var ourIdentity: Identity? = null

        @Volatile private var customAddresses: Set<InetAddress> = emptySet()

        /** Starts the listeners and loops. A second call does nothing. */
        suspend fun start() {
            if (!started.compareAndSet(false, true)) return
            val identity = identities.load()
            local = identity
            trust.load()
            val me =
                Identity(
                    deviceId = identity.deviceId,
                    deviceName = settings.deviceName(),
                    deviceType = DeviceType.PHONE,
                    protocolVersion = IdentityPackets.PROTOCOL_VERSION,
                )
            val advertised = Capabilities.advertised(plugins.capabilitySpecs(), Build.VERSION.SDK_INT)
            ourIdentity =
                me.copy(
                    incomingCapabilities = advertised.incoming,
                    outgoingCapabilities = advertised.outgoing,
                )
            coordinator =
                HandshakeCoordinator(
                    localIdentity = { checkNotNull(ourIdentity) { "identity not ready" } },
                    tls = TlsUpgrader(identity.material, pinnedTrust),
                    scope = appScope,
                )
            trust.all().forEach { entity ->
                publish(entity.id) { it.copy(name = entity.name, paired = true) }
            }

            val tcp = TcpListener.bind()
            listener = tcp
            jobs += tcp.start(appScope) { socket -> onAcceptedSocket(socket) }

            val socket = udp.open()
            udpSocket = socket
            jobs +=
                appScope.launch(Dispatchers.IO) {
                    udp.receive(socket) { packet, from -> onDiscovery(packet, from) }
                }
            jobs +=
                appScope.launch {
                    settings.customIps.collect { ips ->
                        customAddresses =
                            ips
                                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                                .toSet()
                    }
                }
            jobs +=
                appScope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        tick()
                    }
                }
            jobs +=
                appScope.launch {
                    while (isActive) {
                        announce()
                        delay(ANNOUNCE_INTERVAL_MS)
                    }
                }
        }

        fun stop() {
            jobs.forEach { it.cancel() }
            jobs.clear()
            udpSocket?.close()
            listener?.close()
            devices.values.forEach { it.link?.close() }
            started.set(false)
        }

        /** Our TCP port and name, for mDNS registration. Null before [start]. */
        fun localService(): LocalService? {
            val id = local?.deviceId ?: return null
            val port = listener?.port ?: return null
            return LocalService(id, ourIdentity?.deviceName ?: "Tandem", port)
        }

        /** Sends our UDP identity to the broadcast address and to custom IPs. */
        fun announce() {
            val socket = udpSocket ?: return
            val identity = ourIdentity ?: return
            val port = listener?.port ?: return
            appScope.launch(Dispatchers.IO) {
                val line = PacketCodec.encode(IdentityPackets.toUdp(identity, port))
                udp.announce(socket, line, customAddresses.toList())
            }
        }

        /** mDNS resolved a peer at [host]. We send it a unicast identity, and it connects to us. */
        fun onMdnsResolved(host: InetAddress) {
            val socket = udpSocket ?: return
            val identity = ourIdentity ?: return
            val port = listener?.port ?: return
            if (!AddressFilter.isAllowed(host, customAddresses)) return
            appScope.launch(Dispatchers.IO) {
                val line = PacketCodec.encode(IdentityPackets.toUdp(identity, port))
                runCatching { udp.unicast(socket, line, host) }
            }
        }

        /** Flow A: the peer connected to our TCP listener. Runs on the listener's IO thread. */
        private fun onAcceptedSocket(socket: Socket) {
            val remote = socket.inetAddress
            val allowed =
                AddressFilter.isAllowed(remote, customAddresses) &&
                    acceptLimiter.tryAcquire(remote.hostAddress.orEmpty())
            val handshake = coordinator
            if (!allowed || handshake == null) {
                socket.close()
                return
            }
            val session =
                try {
                    handshake.acceptFromPeer(socket)
                } catch (_: Exception) {
                    socket.close()
                    return
                }
            appScope.launch { onLinkEstablished(session) }
        }

        /** Flow B: a discovery packet that announces a TCP port. Connects out when the rules allow. */
        // Each early return is one discovery rule that rejects the packet.
        @Suppress("ReturnCount")
        private fun onDiscovery(
            packet: NetworkPacket,
            from: InetAddress,
        ) {
            val identity =
                try {
                    IdentityPackets.parseDiscovery(packet)
                } catch (_: IdentityException) {
                    return
                }
            val tcpPort = identity.tcpPort ?: return
            if (tcpPort !in PortRange.TCP_LISTEN) return
            if (identity.deviceId == local?.deviceId) return
            val version = identity.protocolVersion
            if (version != null && version < IdentityPackets.PROTOCOL_VERSION) return
            if (!AddressFilter.isAllowed(from, customAddresses)) return
            if (devices[identity.deviceId]?.link?.isOpen == true) return
            if (trust.get(identity.deviceId) == null && unpairedLinkCount() >= MAX_UNPAIRED_LINKS) return
            if (!discoveryLimiter.tryAcquire(identity.deviceId)) return
            if (!dialLimiter.tryAcquire(from.hostAddress.orEmpty())) return
            val address = InetSocketAddress(from, tcpPort)
            appScope.launch(Dispatchers.IO) { connectOut(identity.deviceId, address) }
        }

        private suspend fun connectOut(
            deviceId: String,
            address: InetSocketAddress,
        ) {
            val handshake = coordinator ?: return
            val session =
                try {
                    handshake.connectToPeer(address, deviceId)
                } catch (_: Exception) {
                    return
                }
            onLinkEstablished(session)
        }

        private suspend fun onLinkEstablished(session: PeerSession) {
            lock.withLock {
                val id = session.peer.deviceId
                val trusted = trust.get(id)
                if (trusted != null) {
                    val pinMatches = trusted.certDer.contentEquals(session.peerCertDer)
                    val peerVersion = session.peer.protocolVersion ?: 0
                    val downgraded = IdentityPackets.isProtocolDowngrade(trusted.protocolVersion, peerVersion)
                    if (!pinMatches || downgraded) {
                        session.channel.close()
                        return
                    }
                }
                val device = devices.getOrPut(id) { Device(id, trusted != null) }
                if (trusted == null && device.link == null && unpairedLinkCount() >= MAX_UNPAIRED_LINKS) {
                    session.channel.close()
                    return
                }
                when (DuplicateLinkPolicy.decide(device.link?.certDer, session.peerCertDer)) {
                    LinkDecision.REJECT -> {
                        session.channel.close()
                        return
                    }
                    LinkDecision.REPLACE_EXISTING -> device.link?.close()
                    LinkDecision.ACCEPT -> Unit
                }

                val link = DeviceLink(session)
                device.link = link
                syncPlugins(device)
                if (trusted != null) {
                    trust.store(trusted.copy(lastSeen = now(), lastAddress = session.remoteAddress.hostAddress))
                }
                publish(id) {
                    it.copy(
                        name = session.peer.deviceName ?: it.name,
                        type = session.peer.deviceType ?: DeviceType.PHONE,
                        paired = trusted != null,
                        reachable = true,
                        protocolVersion = session.peer.protocolVersion,
                        address = session.remoteAddress.hostAddress,
                        tlsVersion = session.tlsVersion,
                        cipherSuite = session.cipherSuite,
                        certSha256 = CertificateFactory.sha256Fingerprint(session.peerCertDer),
                    )
                }
                appScope.launch {
                    for (packet in session.channel.incoming) {
                        lock.withLock { handlePacket(device, link, packet) }
                    }
                }
                appScope.launch {
                    session.channel.awaitClosed()
                    lock.withLock { onLinkClosed(device, link) }
                }
            }
        }

        private suspend fun onLinkClosed(
            device: Device,
            link: DeviceLink,
        ) {
            if (device.link !== link) return
            device.link = null
            syncPlugins(device)
            publish(device.deviceId) { it.copy(reachable = false, tlsVersion = null, cipherSuite = null) }
            // A request in flight is cancelled locally. Nothing is sent on a dead link.
            when (device.machine.state) {
                is PairingState.Requested, is PairingState.RequestedByPeer ->
                    perform(device, null, device.machine.onEvent(PairingEvent.UserReject, now()), null)
                else -> Unit
            }
        }

        private suspend fun handlePacket(
            device: Device,
            link: DeviceLink,
            packet: NetworkPacket,
        ) {
            when (packet.type) {
                PacketTypes.PAIR -> {
                    val pair = packet.body.getBoolean("pair") ?: return
                    val timestamp = packet.body.getLong("timestamp")
                    val actions = device.machine.onEvent(PairingEvent.PacketReceived(pair, timestamp), now())
                    perform(device, link, actions, timestamp)
                }
                // Identity repeats match no plugin, so they are ignored by dispatch.
                else -> device.plugins?.takeIf { it.link === link }?.loaded?.dispatch(packet)
            }
        }

        /** User action: ask a connected device to pair. */
        suspend fun requestPairing(deviceId: String) {
            lock.withLock {
                val device = devices[deviceId] ?: return
                val link = device.link?.takeIf { it.isOpen } ?: return
                perform(device, link, device.machine.onEvent(PairingEvent.UserRequest, now()), null)
            }
        }

        /** User action: accept an incoming request. */
        suspend fun acceptPairing(deviceId: String) {
            lock.withLock {
                val device = devices[deviceId] ?: return
                perform(device, device.link, device.machine.onEvent(PairingEvent.UserAccept, now()), null)
            }
        }

        /** User action: reject an incoming request, or cancel ours. */
        suspend fun rejectPairing(deviceId: String) {
            lock.withLock {
                val device = devices[deviceId] ?: return
                perform(device, device.link, device.machine.onEvent(PairingEvent.UserReject, now()), null)
            }
        }

        /** User action: unpair. The peer is told with pair:false. */
        suspend fun unpair(deviceId: String) {
            lock.withLock {
                val device = devices[deviceId] ?: return
                perform(device, device.link, device.machine.onEvent(PairingEvent.UserUnpair, now()), null)
            }
        }

        private suspend fun tick() {
            lock.withLock {
                val current = now()
                for (device in devices.values.toList()) {
                    val deadline = device.machine.nextDeadlineMs() ?: continue
                    if (current >= deadline) {
                        perform(device, device.link, device.machine.onEvent(PairingEvent.Tick, current), null)
                    }
                }
            }
        }

        /**
         * Carries out the side effects of the pairing state machine.
         * [requestTimestampSec] is the timestamp of the peer's request, used for the verification key.
         */
        private suspend fun perform(
            device: Device,
            link: DeviceLink?,
            actions: List<PairingAction>,
            requestTimestampSec: Long?,
        ) {
            val id = device.deviceId
            for (action in actions) {
                when (action) {
                    is PairingAction.SendRequest -> {
                        link?.send(pairPacket(pair = true, timestamp = action.timestampSec))
                        val key = link?.let { verificationKey(it, action.timestampSec) }.orEmpty()
                        publish(id) {
                            it.copy(pairing = PairingUi.Requested(key, now() + PairingStateMachine.REQUEST_TIMEOUT_MS))
                        }
                    }
                    PairingAction.SendAccept -> link?.send(pairPacket(pair = true, timestamp = null))
                    PairingAction.SendReject -> link?.send(pairPacket(pair = false, timestamp = null))
                    PairingAction.ShowPrompt -> {
                        val peer = link ?: continue
                        val key = verificationKey(peer, requestTimestampSec ?: (now() / 1000))
                        val name = states.value[id]?.name ?: id.take(NAME_FALLBACK_CHARS)
                        publish(
                            id,
                        ) { it.copy(pairing = PairingUi.Incoming(key, now() + PairingStateMachine.PROMPT_WINDOW_MS)) }
                        prompts.emit(PairingPrompt(id, name, key))
                    }
                    PairingAction.EnteredPaired -> {
                        val current = link ?: continue
                        val peer = current.session.peer
                        trust.store(
                            TrustedDeviceEntity(
                                id = id,
                                name = peer.deviceName ?: id.take(NAME_FALLBACK_CHARS),
                                type = (peer.deviceType ?: DeviceType.PHONE).wireName,
                                certDer = current.certDer,
                                certSha256 = CertificateFactory.sha256Fingerprint(current.certDer),
                                protocolVersion = peer.protocolVersion ?: IdentityPackets.PROTOCOL_VERSION,
                                pairedAt = now(),
                                lastSeen = now(),
                                lastAddress = current.session.remoteAddress.hostAddress,
                            ),
                        )
                        publish(id) { it.copy(paired = true, pairing = PairingUi.Idle) }
                    }
                    PairingAction.DeleteTrust -> trust.remove(id)
                    PairingAction.Unpaired -> publish(id) { it.copy(paired = false, pairing = PairingUi.Idle) }
                    PairingAction.Rejected ->
                        publish(id) { it.copy(pairing = PairingUi.Failed("Rejected on the other device")) }
                    PairingAction.Cancelled ->
                        publish(id) { it.copy(pairing = PairingUi.Failed("Cancelled on the other device")) }
                    PairingAction.TimedOut ->
                        publish(id) { it.copy(pairing = PairingUi.Failed("No answer from the other device")) }
                    PairingAction.PromptExpired -> publish(id) { it.copy(pairing = PairingUi.Idle) }
                    is PairingAction.ClockSkew ->
                        publish(id) { it.copy(pairing = PairingUi.Failed("Clocks differ by ${action.skewSec} s")) }
                    is PairingAction.Error ->
                        publish(id) { it.copy(pairing = PairingUi.Failed(action.message)) }
                }
            }
            syncPlugins(device)
        }

        /** Sends [packet] to a paired device with a live link. The send gate applies. */
        override suspend fun sendPacket(
            deviceId: String,
            packet: NetworkPacket,
        ): Boolean {
            val device = devices[deviceId]
            val live = device?.link?.takeIf { it.isOpen }
            if (device == null || trust.get(deviceId) == null) {
                return false
            }
            if (live == null) {
                return false
            }
            if (!Capabilities.canSend(packet.type, live.session.peer.incomingCapabilities)) return false
            live.send(packet)
            return true
        }

        override suspend fun livePeer(deviceId: String): LivePeer? {
            val device = devices[deviceId] ?: return null
            if (trust.get(deviceId) == null) return null
            val live = device.link?.takeIf { it.isOpen } ?: return null
            return LivePeer(live.session.remoteAddress, live.session.peer.incomingCapabilities)
        }

        /**
         * Brings the device's plugins in line with its link and pairing state. Plugins load when a
         * link opens or pairing completes, and unload when the link closes or the pairing ends.
         */
        private suspend fun syncPlugins(device: Device) {
            val link = device.link?.takeIf { it.isOpen }
            val paired = trust.get(device.deviceId) != null
            val current = device.plugins
            if (link == null) {
                device.plugins = null
                current?.loaded?.destroy()
                return
            }
            if (current != null && current.link === link && current.paired == paired) return
            device.plugins = null
            current?.loaded?.destroy()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
            val instances =
                pluginHost.instantiate(link.session.peer, paired) { id ->
                    PluginContext(
                        deviceId = id,
                        scope = scope,
                        peerIncoming = { device.link?.session?.peer?.incomingCapabilities.orEmpty() },
                        sender = { packet ->
                            val live = device.link?.takeIf { it.isOpen }
                            if (live == null) {
                                false
                            } else {
                                live.send(packet)
                                true
                            }
                        },
                    )
                }
            val loaded =
                LoadedPlugins(scope, instances, onError = { Log.e(TAG, "plugin failed", it) })
            device.plugins = PluginSlot(link, paired, loaded)
            loaded.start()
        }

        private fun pairPacket(
            pair: Boolean,
            timestamp: Long?,
        ): NetworkPacket {
            val body = linkedMapOf<String, JsonElement>("pair" to JsonPrimitive(pair))
            if (timestamp != null) body["timestamp"] = JsonPrimitive(timestamp)
            return NetworkPacket(now(), PacketTypes.PAIR, JsonObject(body))
        }

        private fun verificationKey(
            link: DeviceLink,
            timestampSec: Long,
        ): String {
            val ours = local?.certificate?.publicKey?.encoded ?: return ""
            return VerificationKey.compute(ours, CertificateFactory.spkiOf(link.certDer), timestampSec)
        }

        private fun unpairedLinkCount(): Int =
            devices.values.count { it.link?.isOpen == true && trust.get(it.deviceId) == null }

        private fun publish(
            deviceId: String,
            transform: (DeviceState) -> DeviceState,
        ) {
            states.update { map ->
                val current =
                    map[deviceId]
                        ?: DeviceState(deviceId = deviceId, name = deviceId.take(NAME_FALLBACK_CHARS))
                map + (deviceId to transform(current))
            }
        }

        private fun now(): Long = System.currentTimeMillis()

        companion object {
            private const val TAG = "Tandem"
            const val MAX_UNPAIRED_LINKS = 42
            const val TICK_MS = 1_000L
            const val ANNOUNCE_INTERVAL_MS = 60_000L
            private const val PROMPT_BUFFER = 8
            private const val NAME_FALLBACK_CHARS = 8
        }
    }
