# Tandem for Android: implementation plan

A KDE Connect protocol-compatible Android client, written in Kotlin and Jetpack Compose with a Material 3 (Material You) UI.

- **Target:** pair and work with stock `kdeconnect-kde` (desktop daemon `kdeconnectd`, protocol version 8) and coexist with the stock KDE Connect Android app on the network.
- **Scope now:** the Android app only. The desktop is always the stock KDE build.
- **v1 plugins:** discovery, TLS pairing, battery, ping, clipboard, share (files, text, links), notification sync.
- **Later:** SMS, telephony, remote input (trackpad and keyboard), media control (MPRIS), presenter, run command, find my phone, SFTP.
- **UI source of truth:** ``design/tandem-material.html` in this repo`.

**Status (code):** phases 0 to 4 phase 6 (trackpad, scroll, clicks, drag, keyboard with special keys and modifiers) and phase 7 (now playing, play/pause, next/previous, seek, volume, player picker, media3 lock-screen and quick-settings session) are implemented. Phase 7 lacks album art and shuffle/loop. Phase 8 (gyro laser dot, slide keys, F5 and Esc, volume keys) is implemented; slide count is local only. Phase 9 (command list, run, add on laptop, confirm for risky commands) is implemented; no command output and no device controls. Phase 10 (ring this phone, ring the laptop) is implemented; no DND-access toggle. Phase 11 (call alerts with name and photo, mute from laptop, missed calls, pause media) and phase 12 (conversation list, conversations, send, incoming push; no MMS) are implemented; the SMS Play-policy risk is open. Phase 13 (SFTP: MINA server, chosen top-level folders, password login, mount from the laptop) is implemented with Android 11 "all files access" instead of SAF roots; Android 8 to 10 is not supported. Phase 3 lacks `clipboard.file` send. Phase 4 has no Preview-only control in the UI and no notification-access step in onboarding. Phases 3 and 4 have unit tests only; their manual scripts are not yet run. Phase 5 is done in code: dynamic color with seed fallback, navigation rail and a Devices list-detail layout on wide screens, predictive back, R8, a generated baseline profile (`app/src/release/generated/baselineProfiles`), screenshot tests (light, dark, 200% font, tablet), and an accessibility audit test (names and 48 dp targets). TalkBack and a wallpaper-colour walk-through still need a person with the phone. Phases 0 to 13 are all implemented in code (their screens use sample data).

All reference paths below are relative to `/run/data/Development/kde_connect/reference/`:
- `A/` = `kdeconnect-android/src/main/java/org/kde/kdeconnect/`
- `K/` = `kdeconnect-kde/`

The references are read-only. We read them to learn the protocol and its behaviour. We never copy code from them (see section 8).

---

## 0. Key decisions (summary)

| Topic | Decision | Reason |
|---|---|---|
| Name / applicationId | "Tandem", `app.tandem` (placeholder until the owner picks a domain) | Must not use the "KDE Connect" name or `org.kde.kdeconnect_tp` id. Both apps must be installable side by side. |
| Protocol version | Announce `protocolVersion = 8` only. Refuse peers below 8. | Desktop refuses below 8 (`K/core/backends/lan/lanlinkprovider.cpp` `deviceDiscovered`, `tcpPacketReceived`: "using too old protocol version"). |
| minSdk | **26** (Android 8.0) | See section 1.6. |
| targetSdk / compileSdk | Latest stable (36 at time of writing; move to 37 when stable) | Play requires a current target. The reference already targets 37. |
| Key type | EC P-256 (`secp256r1`), self-signed X.509 cert, CN = deviceId, O = `KDE`, OU = `KDE Connect` | Matches both references (`A/helpers/security/RsaHelper.kt`, `A/helpers/security/SslHelper.kt`, `K/core/sslhelper.cpp`). The desktop calls `setPeerVerifyName(deviceId)`, so CN must equal deviceId. |
| Key storage | AndroidKeyStore, non-extractable key. Fallback: software key wrapped by a Keystore AES-GCM key. | Hardware-backed when possible. The fallback covers OEM Keystore/Conscrypt TLS bugs. A self-test chooses between them (section 2.3). |
| TLS version | Cap at TLS 1.2 in v1, behind a build flag. Show the *negotiated* version in the UI. | The reference caps at 1.2: `SSLContext.getInstance("TLSv1.2")`, comment "1.3 seems to cause issues" (`A/helpers/security/SslHelper.kt`). The mockup's hard-coded "TLS 1.3" label must come from the session. |
| Transport | LAN only (UDP broadcast, mDNS, manual IP). No Bluetooth. | The Bluetooth backend is experimental in both references. Defer it indefinitely. |
| DI | Hilt (KSP) | Standard. Injects into Services, ViewModels and the NotificationListener. |
| Serialization | kotlinx.serialization `JsonObject` bodies plus typed body data classes per plugin | Lenient parsing. Unknown fields are preserved. |
| Persistence | Room for devices, transfers, clipboard history and notification app rules. Preferences DataStore for settings. | Section 1.4. |
| License | GPL-3.0-or-later | Section 8. |

---

## 1. Architecture

### 1.1 Module layout

```
kde_connect/
├─ settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├─ build-logic/                    # convention plugins (android-library, kotlin-jvm, compose, hilt)
├─ protocol/        (Kotlin/JVM)   # pure protocol: packets, identity, pairing FSM, capabilities, verification key
├─ transport/       (Kotlin/JVM)   # sockets: UDP discovery, TCP server, TLS wrapping, line framing, payload sockets
├─ core/            (Android lib)  # DeviceManager, Device, PluginHost, ConnectionService (FGS), NSD, keystore identity, Room, DataStore
├─ plugins/         (Android lib)  # one package per plugin (battery, ping, clipboard, share, notifications, ...)
├─ designsystem/    (Android lib)  # theme, dynamic color, shapes, typography, shared Compose components
├─ app/             (Android app)  # Activities, navigation, screens, ViewModels, QS tiles, share target
├─ testing/mockpeer (Kotlin/JVM)   # scriptable fake KDE Connect peer for JVM and instrumented tests
└─ tools/
   ├─ mockpeer.py                  # standalone Python peer for manual and wire-level tests
   └─ interop/                     # scripts that drive kdeconnect-cli / qdbus on the desktop
```

Rules:
- `protocol` and `transport` have **no Android dependencies**. This keeps about 80% of protocol tests on the fast JVM, and `mockpeer` can reuse them.
- `transport` takes a `KeyMaterialProvider` (an `X509KeyManager` plus our certificate) and a `TrustPolicy` interface. The Android Keystore lives in `core`.
- `plugins` depends on `core` only through the `Plugin` API (`core/.../plugin/`). Plugins never touch sockets.
- Split `plugins` into per-plugin modules only if build times hurt. They will not in v1.

### 1.2 Packages (inside `app.tandem`)

| Module | Package | Contents |
|---|---|---|
| protocol | `app.tandem.protocol` | `NetworkPacket`, `PacketCodec`, `PacketTypes`, `Identity`, `DeviceType`, `DeviceIdValidator`, `DeviceNameFilter`, `PairingStateMachine`, `VerificationKey`, `Capabilities` |
| protocol | `app.tandem.protocol.body` | `@Serializable` typed bodies: `BatteryBody`, `ClipboardBody`, `ShareRequestBody`, `NotificationBody`, ... |
| transport | `app.tandem.transport` | `LanDiscovery` (UDP), `TcpListener`, `TlsUpgrader`, `LineReader`, `PacketChannel`, `PayloadServer`, `PayloadClient`, `PortRange` |
| core | `app.tandem.core.device` | `DeviceManager`, `Device`, `DeviceLink`, `DeviceState` |
| core | `app.tandem.core.identity` | `LocalIdentity`, `KeystoreIdentity`, `WrappedSoftwareIdentity`, `CertificateFactory` |
| core | `app.tandem.core.trust` | `TrustStore` (Room), `PinnedTrustManager` |
| core | `app.tandem.core.plugin` | `Plugin`, `PluginDescriptor`, `PluginRegistry`, `PluginHost`, `PluginContext` |
| core | `app.tandem.core.service` | `ConnectionService` (FGS), `BootReceiver`, `NetworkMonitor`, `NsdDiscovery` |
| core | `app.tandem.core.data` | Room DB, DAOs, DataStore |
| plugins | `app.tandem.plugins.<name>` | One package per plugin |
| app | `app.tandem.ui.<screen>` | Compose screens and ViewModels |

### 1.3 Plugin registration

There is no reflection or annotation indexing (the reference uses `@LoadablePlugin` plus classindex, `A/plugins/PluginFactory.kt`). We use an explicit list instead:

```kotlin
// core: the plugin API
data class PluginDescriptor(
    val id: String,                       // "battery"
    val incoming: Set<String>,            // packet types we handle
    val outgoing: Set<String>,            // packet types we send
    val requiredPermissions: List<String>,
    val optionalPermissions: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    val listenToUnpaired: Boolean = false,
    val minSdk: Int = 26,
    val factory: (PluginContext) -> Plugin,
)

interface Plugin {
    suspend fun onCreate() {}
    suspend fun onPacket(packet: NetworkPacket): Boolean   // true = handled
    suspend fun onDestroy() {}
}
```

- `PluginRegistry` is a Hilt `@Multibinds Set<PluginDescriptor>`. Each plugin package contributes one descriptor with `@IntoSet`.
- **Our identity capabilities** are `incomingCapabilities = ∪ descriptor.incoming` and `outgoingCapabilities = ∪ descriptor.outgoing`, taken over plugins that are enabled and whose `minSdk` the device meets. Rebuild them when the user toggles a plugin, then reconnect so the peer sees the new identity.
- **Per-device load rule.** This mirrors `PluginFactory.pluginsForCapabilities` in `A/plugins/PluginFactory.kt`. Load plugin P for device D only if both of these hold:
  - `D.outgoing ∩ P.incoming ≠ ∅` or `D.incoming ∩ P.outgoing ≠ ∅`
  - D is paired, or P has `listenToUnpaired = true`
- **Send gate.** Only send type T if `T ∈ D.incomingCapabilities` (compare `Device.kt` lines ~142-145). `kdeconnect.identity` and `kdeconnect.pair` are exempt.
- **Missing permissions.** The plugin is loaded in a "needs permission" state. The UI shows a banner (as in the mockup's Calls & SMS screen). It is not silently dropped.

### 1.4 Persistence

| Data | Store | Notes |
|---|---|---|
| Own deviceId (32-char UUID hex, no dashes) | DataStore | Generated once. Matches `^[a-zA-Z0-9_-]{32,38}$` (`A/DeviceInfo.kt`, `K/core/deviceinfo.h`). |
| Own private key | AndroidKeyStore alias `tandem_identity_v1`, or a wrapped blob in `noBackupFilesDir` | Never backed up. |
| Own certificate | Keystore cert entry, or DER in `noBackupFilesDir` | Regenerate if expired or if the CN does not match the deviceId. Regenerating wipes all trust (same as the reference). |
| Trusted devices | Room `trusted_device(id PK, name, type, certDer BLOB, certSha256, protocolVersion, pairedAt, lastSeen, lastAddress)` | Peer certs are public, so a DB is fine. `lastAddress` lets us reconnect quickly without discovery. |
| Plugin enable flags per device | Room `plugin_setting(deviceId, pluginId, enabled, json)` | |
| Transfers history | Room `transfer(...)` | For the Files & links screen and the Home "Today" list. |
| Clipboard history | Room `clip(...)`, capped at 200 entries, opt-in | For the Clipboard screen. |
| Notification app rules | Room `notif_app_rule(package, mode: SYNC/PREVIEW_ONLY/HIDDEN, category)` | For the Notification sync screen. |
| Custom device IPs | DataStore string set | For "Add device by IP" (the mockup's OpenVPN row). |
| Settings | Preferences DataStore | |

Exclude the key, the cert, `trusted_device` and the deviceId from Auto Backup and device-to-device transfer (`dataExtractionRules`, `fullBackupContent`). A restored trust list without the matching key is useless and confusing.

### 1.5 Threading model (coroutines and Flow)

- `ConnectionService` owns `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- **Discovery.** One coroutine runs the UDP receive loop (blocking `DatagramSocket.receive` on `Dispatchers.IO`). One runs the TCP accept loop. Each accepted socket gets its own child job with a 10 s handshake timeout (`withTimeout`).
- **`DeviceLink`** (one per live TLS socket) has:
  - a **reader** coroutine: a blocking line read that emits into `MutableSharedFlow<NetworkPacket>(extraBufferCapacity = 64)`;
  - a **single writer** coroutine that drains a `Channel<Outgoing>(capacity = 256)`. Exactly one writer per socket means packets never interleave on the wire. The reference writes from multiple threads; we deliberately do not.
- **Payload transfers** run in their own coroutine on `Dispatchers.IO` and expose `StateFlow<TransferProgress>`. Cancelling the job closes the payload socket.
- **`Device`** exposes `StateFlow<DeviceState>`: reachable, pair state, battery, links, and the negotiated TLS version and cipher.
- **`DeviceManager`** exposes `StateFlow<List<DeviceSummary>>` to the UI.
- **Plugins** are given a `CoroutineScope` tied to the device-plugin lifetime and receive packets on `Dispatchers.Default`. A plugin that needs a main-thread Android API (clipboard, notifications) switches with `withContext(Dispatchers.Main)`.
- **UI** collects with `collectAsStateWithLifecycle`. ViewModels talk to `DeviceManager` through a bound-service-free repository: a Hilt singleton shared by the Service and the UI.

### 1.6 minSdk = 26: justification

- **Raised from the reference's 23.** 26 gives:
  - notification channels without compat branches;
  - the `startForegroundService` model;
  - `java.time` and `java.nio.file` without desugaring pain (the reference has explicit workarounds for `PosixFilePermission` crashes below 26: `A/../../../../../build.gradle.kts` lines ~105, ~258);
  - adaptive icons;
  - `NsdServiceInfo.setAttribute` (TXT records), which needs API 21+, so it is fine;
  - an AndroidKeyStore with reliable EC P-256 support. EC in Keystore exists from 23, but 23-25 has known bugs.
- **Market share.** API 26+ covers well over 95% of active devices.
- **Rejected: minSdk 29** (scoped storage only, `NotificationListenerService` improvements). It would drop Android 8/9 devices still common in the target audience. The extra code paths for 26-28 are limited to legacy Downloads writes, so it is not worth it.
- **Dynamic color** needs 31. Below that we fall back to a seed palette (section 4.4).

---

## 2. Protocol implementation

### 2.1 Packet format and framing

Wire format: one UTF-8 JSON object per line, terminated by `\n`. The serializer is `SerializedPacket` in `A/NetworkPacket.kt`; the desktop side is `K/core/networkpacket.cpp`.

```json
{"id":1732030000000,"type":"kdeconnect.share.request","body":{"filename":"a.jpg"},"payloadSize":4200000,"payloadTransferInfo":{"port":1739}}
```

| Field | Type | Rule |
|---|---|---|
| `id` | Long (ms since epoch) | We send `System.currentTimeMillis()`. On parse we accept a number or a numeric string, and ignore it otherwise. |
| `type` | String | Required. |
| `body` | Object | Required. It may be `{}`. |
| `payloadSize` | Long | **Omit the key** when there is no payload. Peers check for its presence (`@EncodeDefault(NEVER)` in `A/NetworkPacket.kt`). `-1` means unknown size: read until EOF. |
| `payloadTransferInfo` | Object `{"port": Int}` | Required whenever `payloadSize` is present. The reference throws otherwise. |

Implementation (`protocol/PacketCodec.kt`, `transport/LineReader.kt`):
- **Decoding:** `Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }`.
- **Body accessors:** `getInt` accepts a JSON number or a numeric string. Peers are inconsistent: Android sends `targetProtocolVersion` as a string (`A/backends/lan/LanLinkProvider.kt`, `udpPacketReceived`). `getBoolean` accepts `true`/`"true"`.
- **Line limits:** identity lines at most 512 KiB (`MAX_IDENTITY_PACKET_SIZE`). Normal packets at most 32 MiB (`MAX_PACKET_SIZE` in `A/backends/lan/LanLink.kt`). Drop over-long lines and keep the connection.
- **Before TLS: read unbuffered, byte by byte, up to `\n`.** A buffered reader would swallow the start of the TLS ClientHello. The reference makes the same point in a comment in `tcpPacketReceived`. After TLS, wrap the SSL input in a buffered stream.
- **Empty lines** are ignored. `SocketTimeoutException` on an idle socket does not close it. Only set read timeouts during the handshake.

### 2.2 Discovery

Constants, from `K/core/backends/lan/lanlinkprovider.h` and `A/backends/lan/LanLinkProvider.kt`:

| Constant | Value |
|---|---|
| UDP discovery port | 1716 |
| TCP listen range | 1716-1764 (first free port) |
| Payload port range | 1739-1764 |
| mDNS service type | `_kdeconnect._udp` (`K/core/backends/lan/avahidiscovery.cpp`) |
| mDNS service name | our deviceId |
| mDNS TXT records | `id`, `name`, `type`, `protocol` |
| Rate limit | 1 connection per second per IP and per deviceId (`MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE`) |
| Max unpaired links | 42 (`MAX_UNPAIRED_CONNECTIONS`) |

**Identity packet variants.** Field names are quoted from `K/core/deviceinfo.h`.

| Variant | Sent when | Body fields |
|---|---|---|
| UDP discovery | broadcast, unicast | Desktop: `deviceId`, `deviceName`, `protocolVersion`, `tcpPort`. Android reference: full identity plus `tcpPort`. |
| TCP connection packet (plaintext, first line on an outgoing TCP connection) | after receiving a UDP packet | Desktop: `deviceId`, `deviceName`, `protocolVersion`, `targetDeviceId`, `targetProtocolVersion` |
| Secure identity (first line after TLS, both directions) | protocol ≥ 8 | `deviceId`, `deviceName`, `deviceType` (`phone`/`tablet`/`desktop`/`laptop`/`tv`), `protocolVersion`, `incomingCapabilities` [String], `outgoingCapabilities` [String] |

What we send:
- **UDP:** full identity plus `tcpPort`. We include `deviceName` and capabilities because old Android peers reject packets without `deviceName` (`A/DeviceInfo.kt` `isValidIdentityPacket`).
- **Outgoing TCP:** full identity plus `targetDeviceId` and `targetProtocolVersion` (as an Int).
- **We accept** the minimal desktop forms (`isValidUdpDiscoveryPacket` only requires `type` and a valid `deviceId`). Never treat pre-TLS fields as trusted; only the secure identity counts.

Name filter (used for display and for our own name):
- Strip `"',;:.!?()[]<>` and cut to 32 chars (`DeviceInfo::filterName` in `K/core/deviceinfo.h`).
- The default name comes from `Settings.Global.DEVICE_NAME`, falling back to `Build.MODEL`.

**Flows.** We must support all three.

1. **We announce (UDP), the desktop connects to us:**
   1. Bind TCP on the first free port from 1716.
   2. Bind UDP on 1716 with `SO_REUSEADDR` and broadcast enabled.
   3. Send the identity plus `tcpPort` to `255.255.255.255:1716`, and to each custom IP.
   4. The desktop TCP-connects and sends the connection packet in plaintext.
   5. We validate: `targetDeviceId` (if present) must equal our id, and `targetProtocolVersion` (if present) must equal 8. We drop it if the address is not private and not a custom IP.
   6. **We are the TCP server, so we are the TLS CLIENT** (`clientMode = connectionStarted == Locally` in `A/.../LanLinkProvider.kt` `identityPacketReceived`; the desktop calls `startClientEncryption()` as TCP server, line ~573).
2. **The desktop announces, we connect:**
   1. Receive the UDP packet and check that `tcpPort` is within 1716-1764.
   2. TCP-connect to `sender:tcpPort` and write our identity plus `targetDeviceId` and `targetProtocolVersion`.
   3. **We are the TCP client, so we are the TLS SERVER.** Use `wantClientAuth` if the peer is untrusted and `needClientAuth` if trusted (`A/helpers/security/SslHelper.kt` `configureSslSocket`). The desktop calls `startServerEncryption()` when it is the TCP client (`tcpSocketConnected`).
   4. If the desktop fails to connect to us, it sends a UDP packet back to us (`recoverConnectionError`), so flow 2 also covers NAT or firewall asymmetry.
3. **mDNS (NSD):**
   1. Register `_kdeconnect._udp` with port = our TCP port and the TXT records above.
   2. Discover peers. On resolve, send a **unicast UDP identity** to `host:1716`; the peer then connects to us (`A/backends/lan/MdnsDiscovery.kt` around line 275). The desktop does the reverse: it TCP-connects directly on mDNS discovery (`deviceDiscovered`).
   3. Hold a `WifiManager.MulticastLock` only during discovery windows. It costs battery.
   4. Prefer private IPv4 over public IPv6 when resolving.

Re-announce triggers:
- service start;
- `ConnectivityManager.NetworkCallback` `onAvailable` / `onLinkPropertiesChanged`, debounced 200 ms (`NETWORK_CHANGE_DEBOUNCE_MS`);
- manual pull-to-refresh on the Devices screen;
- every 60 s while the app UI is open and an unpaired device list is visible.

The desktop has `kdeconnect-cli --refresh` for its side.

Duplicate connections: both sides may connect at once.
- Key links by deviceId.
- If a link exists and the new socket's peer cert equals the stored cert, **replace the socket** and close the old one (`LanLinkProvider.addOrUpdateLink`, `LanLink.reset`).
- If the cert differs, reject the new socket.

Downgrade protection: refuse a trusted device whose `protocolVersion` is lower than the stored one (`isProtocolDowngrade`).

### 2.3 TLS, certificates, trust

**Our certificate** (`core/identity/CertificateFactory.kt`):
- Generate the EC P-256 key in AndroidKeyStore with `KeyGenParameterSpec(PURPOSE_SIGN)` and digests `SHA256` and `SHA512`.
- Set `setCertificateSubject(X500Principal("CN=<deviceId>, OU=KDE Connect, O=KDE"))`, serial 1, notBefore = now − 1 year, notAfter = now + 10 years. Same validity as the reference; the 1-year back-dating tolerates clock skew. Keystore then self-signs the certificate, so **no BouncyCastle is needed**.
- If the Keystore self-test fails, generate the key in software (JCA `KeyPairGenerator("EC")`) and build the cert with BouncyCastle `bcpkix` (MIT license). Wrap the private key with a Keystore AES-256-GCM key and store it in `noBackupFilesDir`.
- **Self-test at first run and after OS updates.** Do an in-process loopback TLS 1.2 handshake: our cert as server, a throwaway client. If it fails, use the fallback and record `identityBackend` in DataStore.

**SSLContext setup** (`transport/TlsUpgrader.kt`, `core/trust/PinnedTrustManager.kt`):
- `KeyManager`: a custom `X509ExtendedKeyManager` that returns our alias, cert chain `[ourCert]` and the Keystore `PrivateKey`. Conscrypt delegates signing to Keystore.
- `TrustManager`:
  - **Untrusted peer:** accept any cert, then *after* the handshake require:
    - peer CN equals the deviceId from the plaintext identity (`SslHelper.getCommonNameFromCertificate`);
    - the cert parses as X.509.
  - **Trusted peer:** pin. The peer leaf cert DER must byte-equal the stored `certDer`. Use the SHA-256 comparison helper, constant time (compare `A/helpers/security/ConstantTimeCompare.kt` for the idea). CN must equal the deviceId. Ignore validity dates for pinned certs. The desktop does the same in effect: `setCaCertificates({stored})` + `VerifyPeer` (`K/core/backends/lan/lanlinkprovider.cpp` `configureSslSocket`).
- Server side: `wantClientAuth = true` for untrusted peers, `needClientAuth = true` for trusted ones.
- Protocols: `["TLSv1.2"]` in v1 (flag `TLS13_ENABLED=false`). Ciphers: platform defaults. Desktop OpenSSL and Conscrypt overlap on ECDHE-ECDSA-AES-GCM.
- Socket options: `keepAlive = true`; `soTimeout = 10 s` during the handshake, then 0.

**Post-handshake identity (protocol 8)** (`LanLinkProvider.identityPacketReceived`, desktop `encrypted()`):
- Immediately write our secure identity. The desktop waits only **1 s** for it.
- Read the peer's secure identity line with a 512 KiB limit and a 5 s timeout. Reject if `deviceId` or `protocolVersion` changed from the plaintext packet, or if the packet is invalid.
- Build `DeviceInfo` from the secure identity plus `peerCertificates[0]`.

**Fingerprints shown in the UI:**
- *Pairing verification key* (the 8-char code users compare). This matches `PairingHandler.getVerificationKey` in `A/PairingHandler.kt` and `PairingHandler::verificationKey` in `K/core/backends/pairinghandler.cpp`:
  ```
  a = ourCert.publicKey.encoded (SubjectPublicKeyInfo DER)
  b = peerCert.publicKey.encoded
  if unsignedCompare(a, b) < 0: swap(a, b)        # larger first
  key = upper(hex(SHA256(a ‖ b ‖ decimalString(pairTimestampSeconds)))[0:8])
  ```
- *Certificate SHA-256* (Connection & security screen): colon-separated hex of SHA-256 over the cert DER (`SslHelper.getCertificateHash`). The desktop shows the same with `kdeconnect-cli --encryption-info -d <id>`.

### 2.4 Pairing

Packet type `kdeconnect.pair`. The state machine (`protocol/PairingStateMachine.kt`) has four states: `NotPaired`, `Requested`, `RequestedByPeer`, `Paired`. It is pure Kotlin, driven by events, with timers injected for tests.

| Action | Body |
|---|---|
| Request | `{"pair": true, "timestamp": <unix seconds>}` |
| Accept | `{"pair": true}` (no timestamp; the desktop uses its absence to tell accept from request, `pairinghandler.cpp` ~line 41) |
| Reject / cancel / unpair | `{"pair": false}` |

Rules (both references):
- **Our timeout after sending a request:** 30 s. We then go to `NotPaired` and tell the user.
- **Prompt window after receiving a request:** we show the prompt for 25 s. The peer times out at 30 s.
- **Timestamp check:** reject requests whose `timestamp` is missing or more than ±1800 s from our clock. Show an error naming the clock difference.
- `pair:true` in state `Requested` means accepted: go to `Paired`.
- `pair:true` in state `Paired`: unpair, then treat it as a new request (reference behaviour).
- `pair:false`:
  - in `Requested` or `RequestedByPeer`: failed or cancelled;
  - in `Paired`: unpaired, so delete the trust row.
- **Entering `Paired`:** store the peer cert DER, name, type and protocolVersion in Room. Invalidate cached SSLContexts. Load the plugins. The current socket stays up; the next reconnection uses pinning.
- **Incoming request UI:** a high-priority notification with Accept/Reject actions and the 8-char key, plus an in-app bottom sheet if the app is in the foreground.
- **Unpaired devices** may only exchange `identity` and `pair` packets, plus plugins with `listenToUnpaired`. We have none in v1.

### 2.5 Payload streaming

Sender (`transport/PayloadServer.kt`), from `A/backends/lan/LanLink.kt` `sendPacket`/`sendPayload`:
1. Open a `ServerSocket` on the first free port in 1739-1764.
2. Set `payloadTransferInfo = {"port": N}` and `payloadSize`.
3. Write the packet line on the main link (through the writer channel).
4. `accept()` with a 10 s timeout, then wrap in TLS as **SERVER** with the trusted peer's pinned context (`needClientAuth`).
5. Stream with a 256 KiB buffer. Emit progress at most every 500 ms. On completion or cancel, close the socket and the source.

Receiver (`transport/PayloadClient.kt`):
1. When a packet arrives with `payloadTransferInfo.port`, connect to `<link remote address>:port` and wrap in TLS as **CLIENT** with pinned trust.
2. Read exactly `payloadSize` bytes, or until EOF if the size is -1.
3. Expose it to the plugin as a `PayloadSource` (an `InputStream` plus size plus `cancel()`).
4. **The plugin must consume or cancel the payload.** If it does neither, close it after 30 s so the sender's socket is not left hanging.

Notes:
- Payloads are only used with paired devices.
- One port per transfer. Concurrent transfers use different ports, and we limit concurrency to 4 to stay within 26 ports.
- Wi-Fi drops mid-transfer surface as `IOException`. Mark the transfer failed and offer a retry. There is no resume: the protocol has none.

### 2.6 Risks and how to test each against the real desktop

| Area | Risk | Test |
|---|---|---|
| Framing | Buffered reader eats TLS bytes | JVM test with `mockpeer` sending identity + ClientHello in one TCP segment. Wireshark on port 1716 shows the plaintext identity followed immediately by the TLS handshake. |
| Discovery | Android blocks broadcast on some APs (client isolation); emulator NAT | Physical device on the same LAN. `tcpdump -i any udp port 1716` on the desktop. Fallback: "Add by IP" on the desktop (KDE Connect settings) and in our app. |
| TLS roles | Swapped roles cause `SSLHandshakeException: wrong version number` | Integration test in `mockpeer` for both flows. Against the desktop: `journalctl --user -f -t kdeconnectd` with `QT_LOGGING_RULES="kdeconnect.*=true"`; look for "Starting client SSL" vs "Starting server SSL". |
| Keystore TLS | OEM Conscrypt cannot sign with a Keystore EC key during the handshake | Self-test at startup; instrumented test on at least 3 OEMs (Pixel, Samsung, Xiaomi). |
| Cert CN | Desktop `setPeerVerifyName(deviceId)` fails if CN ≠ deviceId | Unit test parsing our own cert. On the desktop, `kdeconnect-cli --encryption-info -d <id>`. |
| Verification key | Byte order or encoding mismatch | Golden test vector: generate two fixed keys in a JVM test and compute the key with Python (`tools/mockpeer.py verify-key`). Manual: compare the 8 chars shown on both screens. |
| Capabilities | Desktop does not load a plugin because a capability is missing | `qdbus org.kde.kdeconnect /modules/kdeconnect/devices/<id> org.kde.kdeconnect.device.loadedPlugins` (or `busctl --user`). |
| Payload | Port exhaustion, timeouts | Send 20 files at once from the desktop (`kdeconnect-cli --share f1 f2 ... -d <id>`) and from the phone. |

---

## 3. Phased roadmap

Each phase ends with a tagged build, its JVM and instrumented tests green in CI, and the manual script run against stock `kdeconnect-kde` (record the desktop version in the PR).

Shared manual-test setup (`tools/interop/README.md`):
- Desktop runs Plasma's KDE Connect. Run `kdeconnect-cli --list-devices` to see the state.
- Get our id with `kdeconnect-cli -l --id-name-only`.
- Logs: `QT_LOGGING_RULES="kdeconnect.*.debug=true" kdeconnectd --replace` in a terminal.
- Phone logs: `adb logcat -s Tandem:*`.

### Phase 0: scaffold and CI

**Definition of done**
- An empty app builds and launches with the M3 theme.
- `./gradlew check` runs ktlint (or spotless with ktlint), detekt, Android lint and unit tests.
- GitHub Actions runs `check` and `assembleDebug` on push.
- The modules exist with placeholder tests.

**Files**
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`
- `build-logic/convention/src/main/kotlin/{AndroidLibraryConventionPlugin,AndroidApplicationConventionPlugin,KotlinJvmConventionPlugin,ComposeConventionPlugin,HiltConventionPlugin}.kt`
- `protocol/build.gradle.kts`, `transport/build.gradle.kts`, `core/build.gradle.kts`, `plugins/build.gradle.kts`, `designsystem/build.gradle.kts`, `app/build.gradle.kts`, `testing/mockpeer/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/tandem/TandemApp.kt` (`@HiltAndroidApp`), `app/.../MainActivity.kt`
- `designsystem/.../theme/{Theme.kt,Color.kt,Type.kt,Shape.kt}`
- `app/src/main/res/xml/{data_extraction_rules.xml,backup_rules.xml}`
- `.github/workflows/ci.yml`, `.editorconfig`, `detekt.yml`, `LICENSE` (GPL-3.0), `README.md`, `REUSE.toml`

**Version catalog entries:** AGP, Kotlin 2.x, KSP, Compose BOM, material3, material3-adaptive, navigation-compose (type-safe routes), lifecycle, hilt, room, datastore, kotlinx-serialization-json, kotlinx-coroutines, bcpkix (fallback only), junit5 or junit4, kotest-assertions, turbine, mockk, robolectric, androidx-test, compose-ui-test.

**Manual test:** install, open the app, toggle system dark mode, change the wallpaper on Android 12+, and check the colors change.

### Phase 1: discovery and pairing

**Definition of done**
- The phone appears in `kdeconnect-cli -l` as "Tandem / phone".
- The desktop appears in the app's Devices screen.
- Pairing can be started from either side, and the 8-char keys match.
- After pairing, both sides show "paired + reachable".
- Reconnect works after Wi-Fi off/on and after a desktop `kdeconnectd` restart.
- Unpair works from both sides.
- A foreground service keeps the connection with the screen off for 30 minutes.
- No pinned-cert bypass: replace the desktop's cert by deleting `~/.config/kdeconnect/certificate.pem` and restarting `kdeconnectd`. The phone must refuse that device until it is re-paired.

**Files**
- protocol:
  - `NetworkPacket.kt`, `PacketCodec.kt`, `PacketTypes.kt`
  - `Identity.kt` (`toUdp`, `toConnection`, `toSecure`, validators)
  - `DeviceType.kt`, `DeviceNameFilter.kt`
  - `PairingStateMachine.kt`, `VerificationKey.kt`, `Capabilities.kt`
- transport:
  - `PortRange.kt`, `LineReader.kt` (unbuffered and buffered), `UdpDiscovery.kt`, `TcpListener.kt`
  - `TlsUpgrader.kt`, `HandshakeCoordinator.kt` (both flows plus the secure identity exchange)
  - `PacketChannel.kt` (reader and writer coroutines), `RateLimiter.kt`, `AddressFilter.kt`
- core:
  - `identity/{LocalIdentity,KeystoreIdentity,WrappedSoftwareIdentity,CertificateFactory,IdentitySelfTest}.kt`
  - `trust/{TrustStore,PinnedTrustManager,KeyManagerProvider}.kt`
  - `device/{DeviceManager,Device,DeviceLink,DeviceState}.kt`
  - `service/{ConnectionService,BootReceiver,NetworkMonitor,NsdDiscovery,MulticastLockHolder}.kt`
  - `data/{TandemDatabase,TrustedDeviceDao,TrustedDeviceEntity,SettingsStore}.kt`
  - `plugin/{Plugin,PluginDescriptor,PluginRegistry,PluginHost,PluginContext}.kt`
- app:
  - `ui/devices/{DevicesScreen,DevicesViewModel}.kt`
  - `ui/pairing/{PairingSheet,IncomingPairNotification}.kt`
  - `ui/onboarding/PermissionsScreen.kt` (notifications, battery optimization, local network)
- testing/mockpeer: `MockPeer.kt` (JVM; reuses protocol and transport, with a scriptable responder), `MockPeerCli.kt`
- tools: `mockpeer.py` (Python 3, `ssl` + `cryptography`; subcommands `announce`, `listen`, `pair`, `send`, `verify-key`)
- Tests:
  - `protocol/src/test/.../{PacketCodecTest,IdentityTest,PairingStateMachineTest,VerificationKeyTest}.kt`
  - `transport/src/test/.../{HandshakeFlowATest,HandshakeFlowBTest,LineReaderTest,DuplicateLinkTest}.kt`

**Manual test script**
1. Desktop: `kdeconnect-cli --refresh; kdeconnect-cli -l`. Expect `Tandem (phone)`, reachable, not paired.
2. Phone: Devices screen. Expect the desktop under "Available".
3. Phone: tap Pair. The desktop shows a notification with an 8-char key. Check it equals the phone's key, then accept on the desktop. Expect "paired" on both: `kdeconnect-cli -l` shows `(paired and reachable)`.
4. Unpair on the desktop: `kdeconnect-cli --unpair -d <id>`. The phone should show "not paired" within 2 s.
5. Pair from the desktop: `kdeconnect-cli --pair -d <id>`. Accept on the phone from the notification.
6. Toggle phone Wi-Fi off and on. The device should be reachable again within 10 s.
7. `kdeconnectd --replace`. The device should be reachable again within 10 s.
8. Lock the phone for 30 minutes. `kdeconnect-cli --ping -d <id>` must still succeed. Use Phase 2 ping, or check reachability for now.
9. Wireshark (`tcp.port in {1714..1764}`): the first TCP packet is plaintext JSON identity, then TLS 1.2. Nothing else in plaintext.
10. Delete the desktop's cert as described in the definition of done. The phone logs "certificate mismatch" and does not connect until it is re-paired.

### Phase 2: ping, battery, clipboard

**Definition of done**
- Ping works in both directions with an optional message.
- The phone's battery shows in the desktop applet (level, charging, low).
- The desktop's battery shows on the phone's Home card.
- Desktop to phone clipboard sync works in the background.
- Phone to desktop clipboard works via the in-app button, the QS tile and the share sheet.
- The `clipboard.connect` handshake on connect does not overwrite newer content.

**Packets** (field names from `A/plugins/*` and `K/plugins/*`):

| Type | Direction | Body |
|---|---|---|
| `kdeconnect.ping` | both | optional `message` (String) |
| `kdeconnect.battery` | both | `currentCharge` (Int 0-100), `isCharging` (Bool), `thresholdEvent` (Int: 0 none, 1 battery low). Send on connect and on change only (`A/plugins/battery/BatteryPlugin.kt`). |
| `kdeconnect.clipboard` | both | `content` (String) |
| `kdeconnect.clipboard.connect` | both, on connect | `content`, `timestamp` (Long ms of last local change). The receiver ignores it if `timestamp` is 0 or not newer than its own last change. |
| `kdeconnect.clipboard.file` | both (desktop-supported, `K/plugins/clipboard/clipboardplugin.cpp`) | `filename` plus payload. **Deferred to Phase 3.** Do not advertise it until implemented; the desktop checks `remoteCapabilities` before sending. |

**Files**
- `plugins/ping/{PingPlugin,PingDescriptor}.kt`
- `plugins/battery/{BatteryPlugin,BatteryMonitor}.kt` (sticky `ACTION_BATTERY_CHANGED` plus `ACTION_BATTERY_LOW`/`OKAY`)
- `plugins/clipboard/{ClipboardPlugin,ClipboardSync,ClipboardHistoryRepository}.kt`
- `app/.../clipboard/{ClipboardScreen,ClipboardViewModel,SendClipboardActivity}.kt` (`SendClipboardActivity` is transparent and focus-gaining: read, send, finish)
- `app/.../tiles/SendClipboardTileService.kt`
- `core/data/{ClipEntity,ClipDao}.kt`
- `protocol/body/{PingBody,BatteryBody,ClipboardBody}.kt` plus tests

**Clipboard constraint.** Since Android 10, background apps cannot read the clipboard; only the default IME or a focused app can. So phone to desktop sync is user-initiated: the button, the QS tile, the share sheet "Copy to laptop", or an optional notification action that opens `SendClipboardActivity`. Do **not** use the reference's `logcat`/`READ_LOGS` trick (`A/plugins/clipboard/ClipboardListener.kt`). It needs `adb pm grant` and is fragile. Skip text marked `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) when the "skip sensitive" setting is on (default on).

**Manual test script**
1. Desktop: `kdeconnect-cli --ping-msg "hi" -d <id>`. The phone shows a "hi" notification.
2. Phone: Device menu, Ping. The desktop shows a notification.
3. Plug and unplug the charger. The desktop battery applet updates within 2 s. Check with `qdbus org.kde.kdeconnect /modules/kdeconnect/devices/<id>/battery org.kde.kdeconnect.device.battery.charge`.
4. Copy text on the desktop with the phone locked, then unlock and paste. The text matches.
5. Phone: copy text, then use the QS tile "Send clipboard". Paste on the desktop.
6. Disconnect, copy on the desktop, reconnect. The phone gets the new clip via `clipboard.connect`. Repeat with a newer phone clip: the phone keeps its own.
7. `kdeconnect-cli --send-clipboard -d <id>`. The phone receives it.

### Phase 3: share files and links

**Definition of done**
- Sharing from any app via the Android share sheet sends files, text and URLs.
- Received files land in `Download/Tandem/` with the original name and mtime.
- Received URLs open in the browser, or become a notification when the app is in the background.
- Multi-file batches show combined progress and are cancellable.
- History appears in the Files & links screen.
- `clipboard.file` is supported.

**Packets**

| Type | Direction | Body |
|---|---|---|
| `kdeconnect.share.request` | both | Exactly one of three: `url` (String); `text` (String); or `filename` (String) plus payload, `open` (Bool, optional), `lastModified` (Long ms, optional), `creationTime` (Long ms, desktop-optional), `numberOfFiles` (Int), `totalPayloadSize` (Long) (`A/plugins/share/SharePlugin.kt`, `A/helpers/FilesHelper.kt`, `CompositeUploadFileJob.kt`). |
| `kdeconnect.share.request.update` | both | `numberOfFiles`, `totalPayloadSize`. Sent when a batch grows. |
| `kdeconnect.clipboard.file` | both | `filename` plus payload. Received images go to the clipboard via a `FileProvider` URI. |

**Files**
- `plugins/share/{SharePlugin,UploadJob,BatchUploadJob,ReceiveJob,BatchReceiveJob,DownloadsWriter,ShareNotifications}.kt`
- `DownloadsWriter`: MediaStore `Downloads` on API 29+; on 26-28, `Environment.DIRECTORY_DOWNLOADS` with `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`)
- `app/.../share/{ShareTargetActivity,ShareSheet,ShareViewModel}.kt`, plus a direct-share shortcut via `ShortcutManagerCompat` (pushed dynamic shortcuts with `androidx.sharetarget`)
- `app/.../files/{FilesScreen,FilesViewModel}.kt`, `core/data/{TransferEntity,TransferDao}.kt`
- `app/src/main/res/xml/{file_paths.xml,shortcuts.xml}`

**Manual test script**
1. Desktop: `kdeconnect-cli --share ~/test.jpg -d <id>`. The file appears in `Download/Tandem/test.jpg` with the same mtime (check with `adb shell stat`).
2. Desktop: `kdeconnect-cli --share https://kde.org -d <id>`. In the foreground the browser opens; in the background a notification with "Open" appears.
3. Desktop: `kdeconnect-cli --share-text "hello" -d <id>`. The text is copied, with a notification.
4. Phone: in Photos, select 3 images and share to Tandem, then the laptop. The desktop shows one batch, and the files land in `~/Downloads` (the desktop's `incoming_path`).
5. Phone: share a 2 GB video and cancel at 40%. Both sides stop, and the desktop deletes the partial file.
6. Share a link from Chrome. It opens on the desktop.
7. Desktop: copy a file in Dolphin. The phone receives `clipboard.file`.

### Phase 4: notification sync

**Definition of done**
- Phone notifications appear on the desktop with the app name, title, text and icon.
- Dismissing on the desktop dismisses on the phone (if clearable), and the reverse.
- Replying from the desktop to a messaging notification sends the reply.
- Desktop action buttons trigger the phone's actions.
- Per-app rules (sync / preview-only / hidden) and "only when screen off" work.
- No duplicate storm on reconnect.

**Packets** (phone acts like the desktop's `sendnotifications` peer; desktop `notifications` plugin, `K/plugins/notifications/`)

| Type | Direction | Body |
|---|---|---|
| `kdeconnect.notification` | phone → desktop | `id` (sbn key), `appName`, `title`, `text`, `ticker`, `isClearable` (Bool), `time` (**String** of ms, as the reference sends it), `silent` (Bool), `requestReplyId` (String UUID, only if RemoteInput exists), `actions` ([String] action titles), `payloadHash` (MD5 hex uppercase of PNG icon bytes; payload attached only when the hash changes), `groupName`, `conversation`. Dismissal: `{id, isCancel: true}`. |
| `kdeconnect.notification.request` | desktop → phone | `{request: true}` means send all current notifications. `{cancel: "<id>"}` means dismiss that notification. |
| `kdeconnect.notification.reply` | desktop → phone | `requestReplyId`, `message` |
| `kdeconnect.notification.action` | desktop → phone | `key` (notification id), `action` (action title) |

Field sources: `A/plugins/notifications/NotificationsPlugin.kt` (lines ~230-310, ~520-550) and the key list in `K/plugins/notifications/notification.cpp`.

**Files**
- `plugins/notifications/{NotificationsPlugin,TandemNotificationListener,NotificationMapper,IconCache,ReplyRegistry,ActionRegistry,AppRuleRepository}.kt`
- `core/data/{NotifAppRuleEntity,NotifAppRuleDao}.kt`
- `app/.../notifications/{NotificationSyncScreen,NotificationSyncViewModel,AppRuleRow}.kt`
- Manifest: a `<service>` for `TandemNotificationListener` with `BIND_NOTIFICATION_LISTENER_SERVICE` and the intent filter `android.service.notification.NotificationListenerService`

**Filtering.** Skip notifications that are:
- our own;
- `FLAG_FOREGROUND_SERVICE` or `FLAG_ONGOING_EVENT` (unless media; media goes to MPRIS later);
- group summaries;
- `VISIBILITY_SECRET`;
- from apps whose rule is HIDDEN.

PREVIEW_ONLY sends the app name and title only. The listener runs in the app process and forwards to `NotificationsPlugin` for each paired device that has the plugin loaded.

**Manual test script**
1. Grant notification access from onboarding.
2. Send yourself a Telegram/Signal/SMS message. It appears on the desktop within 2 s with the app icon.
3. Dismiss it on the desktop. It disappears on the phone.
4. Reply from the desktop popup. The reply is sent in the messaging app.
5. Trigger a notification with actions (for example, the alarm "Dismiss"). Click the action on the desktop and it runs on the phone.
6. Mark "Bank" as hidden. Its notifications never reach the desktop.
7. Disconnect, post 3 notifications, reconnect. The desktop sends `notification.request {request:true}` (watch the logs), all 3 appear once, with no duplicates.

### Phase 5: UI polish to match the mockup

**Definition of done**
- Every v1 screen in section 4 matches the mockup's layout, components and color roles.
- Dynamic color works on 31+, with a seed fallback below.
- Large-screen layout uses a list-detail pane on tablets.
- Accessibility pass: TalkBack labels, 48 dp targets, contrast checked.
- Predictive back works.
- Baseline profile and R8 are enabled.
- Screenshot tests (Roborazzi or Paparazzi) cover each screen in light and dark.

**Files**
- `designsystem/component/*`
- `app/.../home/*`, `app/.../security/*`
- `app/baselineprofile/` module
- `app/src/test/.../screenshots/*`

**Manual test script**
- Walk each screen against the mockup in light and dark, with 3 wallpaper colors.
- Font scale 200%.
- Tablet emulator.
- TalkBack navigation of Home → Pair → Send file.

### Phases 6+: later features

Each follows the same pattern: plugin package, screen, permissions onboarding, packet tests, manual script.

| Phase | Feature | Packets (field names) | Notes |
|---|---|---|---|
| 6 | Remote input (trackpad and keyboard) | Send `kdeconnect.mousepad.request`: `dx`, `dy` (Float); `singleclick`, `doubleclick`, `middleclick`, `rightclick`, `singlehold`, `singlerelease` (Bool); `scroll` (Bool) + `dy`; `key` (String); `specialKey` (Int, table in `K/plugins/mousepad/waylandremoteinput.cpp` `SpecialKeysMap`: 1 Backspace … 8 PgUp, 9 PgDn, 12 Enter, 14 Esc, 21+ F1…); `ctrl`, `alt`, `shift`, `super` (Bool); `sendAck`. Receive `kdeconnect.mousepad.keyboardstate` `{state}` and `kdeconnect.mousepad.echo` `{isAck,...}` | No accessibility service needed: the phone only sends. Pointer acceleration follows `A/plugins/mousepad/PointerAccelerationProfile*.kt` behaviour (reimplemented). |
| 7 | Media control | Send `kdeconnect.mpris.request`: `requestPlayerList`; `player` + `requestNowPlaying`, `requestVolume`; `player` + `action` (`Play`/`Pause`/`PlayPause`/`Next`/`Previous`/`Stop`); `setVolume` (Int); `Seek` (µs offset); `SetPosition` (ms); `setShuffle`; `setLoopStatus`; `albumArtUrl` (request art). Receive `kdeconnect.mpris`: `playerList`, `supportAlbumArtPayload`, `player`, `title`, `artist`, `album`, `isPlaying`, `pos`, `length`, `volume`, `canPause`, `canPlay`, `canGoNext`, `canGoPrevious`, `canSeek`, `loopStatus`, `shuffle`, `albumArtUrl`, `url`, `transferringAlbumArt` + payload | Media notification via `androidx.media3` `MediaSession` with a custom `Player` facade, so lock-screen and QS media controls work (mockup's QS media card). |
| 8 | Presenter | `kdeconnect.presenter` `{dx, dy}` and `{stop:true}`. Slide keys via `kdeconnect.mousepad.request` `specialKey` (PgUp 8, PgDn 9, F5 25, Esc 14) | Gyro pointer from `Sensor.TYPE_GAME_ROTATION_VECTOR`. Volume keys change slides while the screen is in the foreground. |
| 9 | Run command | Send `kdeconnect.runcommand.request` `{requestCommandList:true}`, `{key:"<id>"}`, `{setup:true}`. Receive `kdeconnect.runcommand` `{commandList: "<JSON string: id → {name, command}>", canAddCommand}` | `commandList` is a **JSON-encoded string**, not an object. Confirm dialog for destructive names (mockup). Device controls (`ControlsProviderService`, API 30+) are optional. |
| 10 | Find my phone, and ring laptop | Receive `kdeconnect.findmyphone.request` (empty body), then ring. Send the same type to ring the desktop (its `findthisdevice`). | Ring on the alarm stream at max volume, plus flashlight. Android 14+ restricts `USE_FULL_SCREEN_INTENT` (see section 5). |
| 11 | Telephony | Send `kdeconnect.telephony`: `event` (`ringing`/`talking`/`missedCall`), `phoneNumber`, `contactName`, `phoneThumbnail` (base64), `isCancel`. Receive `kdeconnect.telephony.request_mute` | `TelephonyCallback` (31+) or `PhoneStateListener`. Mute with `AudioManager` ringer adjust. |
| 12 | SMS | Receive `kdeconnect.sms.request_conversations`, `kdeconnect.sms.request_conversation` (`threadID`, `rangeStartTimestamp`, `numberToRequest`), `kdeconnect.sms.request_attachment` (`part_id`, `unique_identifier`), `kdeconnect.sms.request` (send: `addresses` [{address}], `messageBody`, `subID`, `attachments`, `version`). Send `kdeconnect.sms.messages` `{messages:[{event, body, addresses, date, type, read, thread_id, _id, sub_id, attachments}], version}` and `kdeconnect.sms.attachment_file` + payload | Keys from `A/helpers/SMSHelper.kt` (~lines 820-920). Biggest Play-policy risk (section 5). Test with the desktop's `kdeconnect-sms` app and `kdeconnect-cli --send-sms`. |
| 13 | SFTP | Receive `kdeconnect.sftp.request` `{startBrowsing:true}`. Reply `kdeconnect.sftp` `{ip, port, user, password, path, multiPaths, pathNames}` or `{errorMessage}` | Embedded Apache MINA SSHD (Apache-2.0, GPLv3-compatible) on a port in 1739-1764, random password per session. SAF roots chosen by the user; no `MANAGE_EXTERNAL_STORAGE` by default. Desktop mounts it with `sshfs`; check with `kdeconnect-cli --mount -d <id>; kdeconnect-cli --get-mount-point -d <id>`. |
| later | Reverse input (desktop controls phone), connectivity report, contacts sync, system volume, digitizer | `kdeconnect.mousepad.request` received; `kdeconnect.connectivity_report`; `kdeconnect.contacts.*`; `kdeconnect.systemvolume*` | Reverse input needs an `AccessibilityService` with `dispatchGesture` (`A/KdeConnectAccessibilityService.kt`). Play's Accessibility API policy applies. Do it last or ship it only on F-Droid. |

---

## 4. UI plan

### 4.1 Screen map (mockup → Compose)

| Mockup panel | Route | Composable | ViewModel / state | Key M3 components |
|---|---|---|---|---|
| Home ("Large top app bar · extended FAB") | `Home` | `HomeScreen` | `HomeViewModel` → `HomeUiState(device, battery, latency, network, quickActions, nowPlaying, todayTransfers)` | `LargeTopAppBar`, `ElevatedCard` (device + battery + latency), quick-action `FilledTonalButton` row (Send file, Clipboard, Trackpad, Lock PC), media mini card, `ListItem` history, `ExtendedFloatingActionButton` "Send" |
| Share sheet ("Direct-share target · modal bottom sheet") | `ShareTargetActivity` (separate task) | `ShareSheet` | `ShareViewModel(items, devices)` | `ModalBottomSheet`, device `ListItem`s, file summary card |
| Files & links | `Files` | `FilesScreen` | `FilesViewModel(transfers: Flow<PagingData>)` | `OutlinedTextField` (link to open), `LinearProgressIndicator`, `FilledCard` "Phone storage on laptop" (Phase 13 SFTP toggle) |
| Clipboard ("Search bar · filter chips · 3-line list") | `Clipboard` | `ClipboardScreen` | `ClipboardViewModel(query, filter, clips)` | `SearchBar`, `FilterChip` (All / From laptop / From phone), three-line `ListItem`, auto-sync `Switch` |
| Notification sync ("Segmented button · switches · badge") | `NotificationSync` | `NotificationSyncScreen` | `NotificationSyncViewModel(listenerGranted, rules, screenOffOnly)` | `SingleChoiceSegmentedButtonRow` (Notifications / Calls & SMS), `Switch` with icons, category `FilterChip`s, app rows with `Badge` |
| Calls & SMS ("Banner · list with tags · permissions") | `CallsSms` | `CallsSmsScreen` | `CallsSmsViewModel` | Permission banner (custom `Card` + two `TextButton`s), `Switch` rows, history list with `AssistChip` tags |
| Call alert (heads-up mirrored) | n/a (system notification) | n/a | n/a | `NotificationCompat.CallStyle` is not ours; this panel documents the desktop-side effect only |
| Trackpad & keyboard ("Touch surface · slider · FAB") | `Trackpad` | `TrackpadScreen` | `TrackpadViewModel` (gesture to packets, throttled at 60 Hz) | Custom `Modifier.pointerInput` surface, Left/Right `OutlinedButton`s, `Slider` (pointer speed), `FloatingActionButton` (keyboard) |
| Media & volume ("Shape-morphing play button · sliders") | `Media` | `MediaScreen` | `MediaViewModel(players, nowPlaying)` | Player `ExposedDropdownMenu`, seek `Slider`, morphing play/pause (`MaterialShapes` + `Morph` from graphics-shapes), volume `Slider`, `FilterChip` player switcher |
| Presentation remote ("Gyro pointer · large touch targets") | `Presenter` | `PresenterScreen` | `PresenterViewModel(timer, pointerActive)` | Large `Button` Back/Next, hold-to-point surface, timer in `TopAppBar` |
| Run commands ("List actions · basic dialog") | `RunCommands` | `RunCommandsScreen` | `RunCommandsViewModel(commands, canAdd)` | `ListItem` + trailing `IconButton`, `AlertDialog` confirm |
| Find my phone ("Full-screen intent · tertiary") | `FindMyPhoneActivity` (show-when-locked) | `FindMyPhoneScreen` | n/a | Full-bleed `tertiaryContainer` surface, swipe/tap to stop |
| Connection & security ("Radio list · filled card · switches") | `Security` | `SecurityScreen` | `SecurityViewModel(transport, tlsVersion, cipher, certSha256, verified)` | `RadioButton` list (Local Wi-Fi / custom IP or VPN), `Card` with fingerprint in monospace, `Switch` rows. The "Browser extension" rows are out of scope; omit them. |
| Devices & battery ("Elevated card · battery chart · tonal button") | `Devices` and `DeviceDetail/{id}` | `DevicesScreen`, `DeviceDetailScreen` | `DevicesViewModel`, `DeviceDetailViewModel` | `ElevatedCard`, battery sparkline (Canvas; the history lives in Room `battery_sample`, 12 h), `FilledTonalButton` (Ring laptop), `OutlinedButton` (Unpair), "Pair device" button |
| Quick Settings & notification | n/a | `TandemTileService` (connection), `SendClipboardTileService`, ongoing FGS notification with actions, transfer-complete notification, media notification | n/a | Android `TileService`, `NotificationCompat` |

### 4.2 Navigation graph

Use type-safe Navigation Compose (`@Serializable` routes) inside a `NavigationSuiteScaffold` (bottom bar on phones, rail on tablets).

```
Root
├─ Onboarding (first run): Welcome → Permissions → PairFirstDevice
└─ Main (NavigationSuiteScaffold: Home | Files | Remote | Devices)
   ├─ Home
   │   ├─ → Clipboard
   │   ├─ → Trackpad
   │   └─ → Media
   ├─ Files            → (Files & links)
   ├─ Remote           → Trackpad | Media | Presenter | RunCommands   (hub list; Phase 6+, hidden until a plugin is available)
   └─ Devices
       ├─ DeviceDetail/{deviceId}
       │   ├─ NotificationSync/{deviceId}
       │   ├─ CallsSms/{deviceId}
       │   ├─ Security/{deviceId}
       │   └─ PluginSettings/{deviceId}
       └─ AddByIp
Separate entry points: ShareTargetActivity, SendClipboardActivity, FindMyPhoneActivity, PairRequest deep link from notification (tandem://pair/{deviceId})
```

"Active device": Home and Remote act on the selected device. The selection is stored in DataStore and defaults to the most recently connected paired device. With several paired devices, a top-bar device switcher appears.

### 4.3 State holders

- Each screen has one `@HiltViewModel` exposing `StateFlow<UiState>` (an immutable data class) plus `fun onEvent(e: UiEvent)`.
- Repositories (`DeviceRepository`, `TransferRepository`, `ClipboardRepository`, `NotificationRuleRepository`) wrap `DeviceManager` and the DAOs. They are singletons shared with `ConnectionService`.
- One-off effects (snackbars, open URL) go through `Channel<UiEffect>` collected in `LaunchedEffect`.
- Permission state comes from `rememberPermissionState`-style helpers in `designsystem`. A small in-house wrapper replaces Accompanist, which is deprecated for permissions.

### 4.4 Theme

- `TandemTheme(dynamicColor = true)`:
  - On API 31+ use `dynamicLightColorScheme` / `dynamicDarkColorScheme`.
  - Otherwise use a generated scheme from **seed `#006A6A` (teal)**, matching the mockup's default teal wallpaper swatch. Generate it offline with Material Theme Builder and commit it as `Color.kt`.
- A settings toggle "Use wallpaper colors" (on by default), plus a manual seed picker with the mockup's 6 swatches: teal, blue, purple, red, amber, green.
- Color roles as in the mockup's "Color roles in use":
  - primary for main actions;
  - secondary/tertiary containers for chips and the find-my-phone surface;
  - `surfaceContainer*` for cards;
  - error for destructive actions (Unpair, Shut down).
- Typography: M3 default type scale (Roboto Flex if added), with `displaySmall` and `headlineSmall` used as in the mockup.
- Shapes: M3 defaults plus `MaterialShapes` for the media play button. Edge-to-edge is enabled (`enableEdgeToEdge()`).

### 4.5 Shared components (`designsystem/component`)

`DeviceStatusCard`, `BatteryPill`, `QuickActionButton`, `TransferRow` (with progress), `ClipRow`, `AppRuleRow`, `PermissionBanner`, `SectionHeader`, `FingerprintText` (monospace grouped hex), `BatterySparkline`, `MorphingPlayButton`, `TrackpadSurface`, `ConfirmDialog`, `EmptyState`, `DeviceSwitcher`.

---

## 5. Permissions and Android-version / Play-policy pitfalls

| Feature | Permissions | Pitfalls and decisions |
|---|---|---|
| Core networking | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS lock), `CHANGE_NETWORK_STATE` | **Local network permission:** the reference declares `ACCESS_LOCAL_NETWORK` and gates UDP broadcast on it for API ≥ `CINNAMON_BUN` (37) (`A/backends/lan/LanLinkProvider.kt` `broadcastUdpIdentityPacket`). Declare it now and request it at runtime on 37+. Without it, discovery is dead. |
| Foreground service | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` (33+), `RECEIVE_BOOT_COMPLETED` | Use `foregroundServiceType="connectedDevice"`, like the reference (`AndroidManifest.xml` ~line 108). Android 14 requires a matching type permission plus one prerequisite permission (`CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` satisfy it). Do **not** use `dataSync`: Android 15 caps it at 6 h per day and forbids starting it from `BOOT_COMPLETED`. The connectedDevice type is allowed from boot. Starting an FGS from the background is restricted (12+), so start it only from boot, from the UI, or from the `NetworkCallback` while the FGS is already running. Never start it from a random broadcast. On 13+, if notifications are denied the FGS still runs but its notification is hidden; explain this in onboarding. |
| Battery optimization | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (optional) | Doze kills idle sockets. The FGS mostly avoids that, but OEMs (Xiaomi, Samsung, Huawei) still kill apps. Onboarding links to the system dialog and to dontkillmyapp.com. Play allows this permission only for apps whose core function breaks without it. A companion app keeping a persistent link qualifies, but write the justification. Alternative: ship without the permission and only deep-link to the settings page (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). **Decision: use the settings deep-link (no permission) in v1.** |
| Clipboard | none | Background read is blocked on 10+ (section 3, Phase 2). On 12+ a "pasted from" toast appears when we read; acceptable. On 13+, mark clips we write as sensitive when the desktop indicates a password (not in the protocol, so skip). |
| Share (receive) | 26-28: `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`); 29+: none (MediaStore) | Opening a URL from the background is blocked (Android 10 background-activity-start). Use a notification with a `PendingIntent`. Android 14 tightens BAL from `PendingIntent`s; that is fine because the user taps it. |
| Share (send) | none (URIs granted by the sharing app) | Persist URI permission only for the transfer's duration. Large files: stream from `ContentResolver.openInputStream` and never copy into memory. |
| Notifications sync | Notification listener access (special access, the user enables it in Settings) | On Android 15+ OTP and sensitive notification content is redacted for listeners without `RECEIVE_SENSITIVE_NOTIFICATIONS` (a signature/role permission). We cannot get it, so document the limitation. Some OEMs revoke listener access after updates: detect with `NotificationManagerCompat.getEnabledListenerPackages` and show a banner. Android 13+ restricted settings: sideloaded APKs (not from a store) cannot enable notification listener or accessibility until the user allows "restricted settings" in App info. Explain this in onboarding for sideload/F-Droid installs. |
| Telephony (Phase 11) | `READ_PHONE_STATE`, `READ_CALL_LOG` (to get the number on 29+), `READ_CONTACTS` (name and photo) | `READ_CALL_LOG` is a Play restricted permission. The allowed use case is "connected device companion". Requires a Permissions Declaration Form and a video. Fallback: Play flavor without number, name only from the notification. |
| SMS (Phase 12) | `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS` | Play restricted. Allowed only for default SMS handlers or approved exceptions ("companion / connected device" is a listed exception, but approval is not guaranteed). **Decision: build flavors `play` (no SMS, telephony limited) and `foss` (full).** MMS reading needs its own content-provider code; sending MMS needs the APN-dependent library the reference uses (`android-smsmms`, GPL). We will write our own minimal MMS send or skip it. |
| Remote input (Phase 6) | none | The phone only sends. No accessibility needed. |
| Reverse input (later) | `BIND_ACCESSIBILITY_SERVICE` | Play's Accessibility API policy requires a prominent disclosure and the `isAccessibilityTool` decision. Restricted settings on sideload (13+). |
| Media control (Phase 7) | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` only if we run a media FGS; otherwise none | Use a `MediaSession` without our own FGS by attaching it to the existing connectedDevice FGS notification. Verify on Android 13+ that media controls show in QS. |
| Find my phone (Phase 10) | `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `CAMERA` (flashlight via `CameraManager.setTorchMode` needs no permission; omit `CAMERA`) | Android 14+ grants `USE_FULL_SCREEN_INTENT` by default only to calling and alarm apps. Check `NotificationManager.canUseFullScreenIntent()` and fall back to a high-priority notification plus a ringing sound. Use alarm-stream audio to bypass silent mode (`AudioAttributes.USAGE_ALARM`). DND may still block it, so offer a "Do Not Disturb access" toggle (`ACCESS_NOTIFICATION_POLICY`). |
| SFTP (Phase 13) | none by default (SAF trees). Optional `MANAGE_EXTERNAL_STORAGE` in `foss` only | `MANAGE_EXTERNAL_STORAGE` is Play restricted (file managers etc.), so do not use it in `play`. SAF-backed SFTP is slower; accept that. |
| Run command, presenter, ping, battery | none | Presenter gyro needs no permission. |
| Package visibility | Avoid `QUERY_ALL_PACKAGES` (the reference uses it) | We only need app labels and icons for packages that posted notifications. `StatusBarNotification.packageName` with `getApplicationInfo` works for those because the notification listener grants visibility. Use `<queries>` for browsers only. |

Other version pitfalls:
- **Android 12+:** exact alarms are not needed. `PendingIntent` must set `FLAG_IMMUTABLE`/`FLAG_MUTABLE` (mutable for RemoteInput reply only).
- **Android 14+:** every `registerReceiver` needs `RECEIVER_EXPORTED`/`NOT_EXPORTED`, and implicit intents to our own components must be explicit.
- **Android 15+:** edge-to-edge is enforced, so the Compose screens must handle insets. FGS from `BOOT_COMPLETED` is restricted for some types; connectedDevice is fine, but verify on each release.
- **Android 16 / 17:** re-verify local-network permission behaviour and the predictive-back defaults each release.
- **Emulator:** it sits behind NAT (10.0.2.x), so broadcast and mDNS do not reach the LAN. Interop testing needs a physical phone. The emulator is fine for UI tests and for `mockpeer` tests over `adb reverse tcp:1716 tcp:1716` (custom-IP flow only).

---

## 6. Testing strategy

### 6.1 Unit tests (JVM, `protocol`, `transport`, `plugins` mappers)

- **PacketCodec:**
  - round-trip;
  - `payloadSize` omitted when there is no payload;
  - `payloadTransferInfo` required when `payloadSize` is present;
  - numeric strings accepted;
  - unknown fields ignored;
  - 32 MiB limit;
  - newline termination.

  Golden fixtures go in `protocol/src/test/resources/packets/*.json`. They are captured with Wireshark/mockpeer from the real desktop. Captures are our own traffic, not copied code.
- **Identity:**
  - validators (deviceId regex, name filter);
  - minimal desktop UDP packet accepted;
  - `targetDeviceId` mismatch rejected;
  - protocol downgrade rejected.
- **PairingStateMachine:**
  - every transition in section 2.4;
  - timeouts with `TestCoroutineScheduler`;
  - timestamp skew at ±1799 s and ±1801 s;
  - request while paired.
- **VerificationKey:** fixed EC keys and timestamp, with the expected 8-char key computed independently by `tools/mockpeer.py verify-key`. Cross-language agreement is the test.
- **Capabilities:** the plugin load rule and the send gate.
- **Transport** (real sockets on localhost, JVM TLS with test keys):
  - flow A and flow B handshakes;
  - TLS roles;
  - secure identity exchange, plus the 1 s desktop deadline simulated by mockpeer;
  - pinned-cert mismatch rejection;
  - duplicate link replacement;
  - payload send and receive of 0 B, 1 B, 10 MB and unknown size (-1);
  - cancel mid-stream;
  - port-range exhaustion (26 concurrent).
- **Plugins:** body mapping (battery intent to body; notification to body with Robolectric `StatusBarNotification`; share packet to `DownloadsWriter` call). Use MockK for Android managers where Robolectric is insufficient.

### 6.2 Instrumented tests (`app/src/androidTest`, `core/src/androidTest`)

- Keystore identity: generate, check CN, loopback TLS self-test, regenerate on deviceId change.
- Room migrations (from schema export `core/schemas`).
- `ConnectionService` start/stop, FGS type declared, and restart after process death (`adb shell am kill app.tandem` while backgrounded, then check that `START_STICKY` brings the service back and links reconnect).
- End to end with `mockpeer` running on the test host via `adb reverse`, or in-process on the device: pair, ping, battery, clipboard, share 5 MB, notification post-and-dismiss (posting a notification from the test app).
- Compose UI tests: Devices list with fake repository; pairing sheet shows the key and buttons; Clipboard filters; NotificationSync rules toggling.
- Screenshot tests (Roborazzi, JVM) for each screen, light and dark.

### 6.3 Interoperability checklist against stock kdeconnect-kde

Run it per release, on at least the current Plasma 6 KDE Connect and the previous minor release. Record the versions.

| # | Check | Command / method | Pass |
|---|---|---|---|
| 1 | Phone discovered by desktop | `kdeconnect-cli --refresh && kdeconnect-cli -l` | listed, type phone |
| 2 | Desktop discovered by phone | Devices screen | listed |
| 3 | Pair from phone, keys match | visual | paired both sides |
| 4 | Pair from desktop | `kdeconnect-cli --pair -d ID` | paired |
| 5 | Reject pairing | desktop Reject | phone shows "rejected" |
| 6 | Unpair from either side | `kdeconnect-cli --unpair -d ID` | both unpaired |
| 7 | Reconnect after Wi-Fi toggle and after `kdeconnectd --replace` | manual | ≤ 10 s |
| 8 | mDNS-only discovery (UDP broadcast blocked) | block outgoing broadcast with `nft` on the desktop | still discovered |
| 9 | Manual IP | desktop "Add device by IP" and phone AddByIp | connects |
| 10 | Cert pin | replace desktop cert | phone refuses |
| 11 | Ping both directions | `kdeconnect-cli --ping-msg x -d ID` | notification |
| 12 | Battery both directions | plug/unplug; Home card | updates |
| 13 | Clipboard both directions and on connect | `--send-clipboard`, QS tile | text matches |
| 14 | Share file, text, url, multi-file, cancel | `--share`, `--share-text`, share sheet | files intact (sha256sum) |
| 15 | Notifications: post, dismiss both ways, reply, action | messaging app | all work |
| 16 | `kdeconnect-cli --list-notifications -d ID` | after posting | lists ours |
| 17 | Loaded plugins on desktop | `qdbus org.kde.kdeconnect /modules/kdeconnect/devices/ID org.kde.kdeconnect.device.loadedPlugins` | battery, ping, clipboard, share, notifications (plus later ones) |
| 18 | Coexistence with stock KDE Connect Android on the same LAN | install both on two phones | no interference; each pairs separately |
| 19 | Idle 1 h with screen off | `kdeconnect-cli -l` | still reachable |
| 20 | Encryption info | `kdeconnect-cli --encryption-info -d ID` | SHA-256 matches the Security screen |

Optional CI interop (Phase 5+): a container running `kdeconnectd` under `dbus-run-session` on a bridge network with the JVM `mockpeer` driving our `protocol`/`transport` code. It catches desktop protocol changes without a phone. The desktop has a loopback test mode (`m_testMode` in `lanlinkprovider.cpp`), but we treat it as a black box.

---

## 7. Open questions and risks (ranked)

| Rank | Risk / question | Impact | Mitigation / decision needed |
|---|---|---|---|
| 1 | OEM background killing despite FGS | Connection drops; core value broken | Onboarding to battery settings, dontkillmyapp links, reconnect on `NetworkCallback`, `lastAddress` fast reconnect. Measure on Xiaomi and Samsung early (Phase 1). |
| 2 | Play policy for SMS, call log, battery optimization, accessibility | Rejection or removal from Play | `play` vs `foss` flavors decided now. **Owner decision:** do we ship on Play at all, or F-Droid only first? |
| 3 | AndroidKeyStore EC key used for TLS on some OEMs | Handshake failures | Self-test plus wrapped-software fallback (section 2.3). |
| 4 | Local network permission on API 37+ | Discovery dead if denied | Runtime request with rationale. Manual-IP fallback works only if the permission also covers unicast; re-check when 37 is final. |
| 5 | Desktop protocol drift (for example, `clipboard.file` was added recently; protocol 9 may come) | Features break silently | Interop checklist per desktop release. Lenient parser. Advertise only implemented capabilities. Watch the `K/` git log for `networkpacket`, `deviceinfo`, `lanlinkprovider`. |
| 6 | TLS 1.3 vs 1.2 | Interop or handshake bugs | v1 caps at 1.2 like the reference. Test 1.3 behind the flag against the desktop in Phase 5 and flip it if it is clean. |
| 7 | Background clipboard read impossible | Users expect automatic phone-to-desktop sync | Clear UX (QS tile, button). An optional Tandem IME is a separate later project. |
| 8 | Android 15 redaction of sensitive notifications | OTPs do not appear on the desktop | Document it; nothing to fix. |
| 9 | Large transfers on flaky Wi-Fi | Failed transfers, no resume | Retry button. Keep a wake lock and Wi-Fi lock (`WifiManager.WifiLock` high-perf) only during transfers. |
| 10 | Emulator cannot do LAN discovery | Slow dev loop | `mockpeer` and `adb reverse` for automated work; a physical device for interop. |
| 11 | MMS send without the GPL library | SMS phase scope | Defer MMS send, ship SMS plus MMS read first. |
| 12 | Naming and trademark | Store rejection | Never present as "KDE Connect". Say "works with KDE Connect" in descriptions only, which is descriptive use. |

---

## 8. Licensing

- `kdeconnect-android` and `kdeconnect-kde` are licensed `GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL` (SPDX headers in every file, for example `A/NetworkPacket.kt`; texts in `K/LICENSES/`).
- **Rule for this project:**
  - Implement from the protocol's observable behaviour: packet types, field names, port numbers, handshake order and crypto construction. These are interface facts needed for interoperability.
  - **Do not copy code, comments, strings, resources, icons or layouts** from the references.
  - Contributors may read the references to settle behaviour questions, but write the code fresh without the reference open. Test against the real desktop rather than against reference code.
  - Strings and translations are written new (the reference's `values-*` translations are GPL too).
- **Our license: GPL-3.0-or-later.** Why:
  1. Several of us have read the GPL reference closely. A copyleft license removes any argument about derivative work if a structural similarity slips in.
  2. It keeps the option to reuse KDE code legally later (`GPL-3.0-only` is one of KDE's offered terms, and GPL-3.0-or-later code can be combined with it under GPL-3.0).
  3. It matches the ecosystem's expectations, and F-Droid inclusion is easy.
  4. It is compatible with our Apache-2.0 dependencies (AndroidX, Apache MINA SSHD, Kotlin) and MIT ones (BouncyCastle). GPL-2.0-only would not be compatible with Apache-2.0.
- Add `LICENSE` (GPL-3.0 text), SPDX headers `SPDX-License-Identifier: GPL-3.0-or-later` in every source file, `REUSE.toml`, and a generated third-party notices screen (`com.google.android.gms:oss-licenses` is not FOSS, so use the `licensee` or `aboutlibraries` Gradle plugin instead).
