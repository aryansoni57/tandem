# Tandem

A KDE Connect protocol-compatible Android client, written in Kotlin with Jetpack Compose (Material 3).
Works with the stock `kdeconnect-kde` desktop daemon (protocol 8). Not affiliated with KDE.

Status: Phases 0 to 13 are implemented in code (scaffold, pairing, ping, battery, clipboard, share, notification sync, remote input, media control, presenter, run commands, find my phone, telephony, SMS, SFTP). Phases 3, 4, 6, 7, 8, 9, 10, 11, 12 and 13 are not yet run against a real desktop. See `PLAN.md`.

## Modules

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | JVM | Packets, identity, pairing state machine, verification key, capabilities |
| `sftp` | JVM | Apache MINA SSHD server that shares chosen storage folders (password login, no shell) |
| `transport` | JVM | UDP discovery, TCP listener, TLS 1.2 upgrade, handshake flows, packet channel |
| `testing/mockpeer` | JVM | Scriptable fake desktop used by tests, plus a small CLI |
| `core` | Android library | Identity (Keystore), trust store (Room), device manager, foreground service |
| `plugins` | Android library | Plugins: ping, battery, clipboard, share, notifications, mousepad, mpris, presenter, runcommand, findmyphone, telephony, sms, sftp |
| `designsystem` | Android library | Material 3 theme |
| `app` | Android application | Activity, devices and pairing screens, onboarding |
| `build-logic` | Gradle included build | Convention plugins (`tandem.*`) |

`protocol` and `transport` have no Android dependencies, so most protocol tests run on the JVM.

## Requirements

- JDK 17 or newer (the build pins Java and Kotlin targets to 17).
- Android SDK with platform 36 and build-tools. Create `local.properties` with `sdk.dir=/path/to/Sdk`.
  The file is git-ignored.
- Gradle is provided by the wrapper (`./gradlew`, Gradle 9.1.0).

## Build and test

```sh
./gradlew :protocol:test :transport:test     # JVM unit tests (protocol, handshake flows)
./gradlew test                               # all unit tests
./gradlew check                              # ktlint, detekt, Android lint, unit tests
./gradlew assembleDebug                      # debug APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug                  # install on a connected device
```

## Manual interop checks (Phase 1)

Run against a desktop with stock KDE Connect (`kdeconnect-cli`). The plan lists the full script in
`PLAN.md` section 3. Short form:

1. Phone and desktop on the same LAN. On the desktop: `kdeconnect-cli --refresh; kdeconnect-cli -l`.
2. Open Tandem, finish onboarding, and tap the desktop in the device list. Pair and compare the 8-character codes.
3. Logs: `adb logcat -s Tandem:*`.

## Manual interop checks (Phase 2)

Discovery, TLS, pairing and ping are verified on a physical phone (see "Test on a physical phone" below).
The emulator path was not verified. The checks below cover ping, battery and clipboard after pairing:

1. Ping: `python3 tools/mockpeer.py send --type kdeconnect.ping --body '{"message":"hi"}'`.
   The phone shows a "Ping from ..." notification. Ping the other way from the device screen.
2. Battery: the phone sends its battery on connect and on change. Ask it again with
   `send --type kdeconnect.battery.request --body '{"request":true}'`. Send
   `kdeconnect.battery` with a charge to see it on the device screen.
3. Clipboard, phone to peer: copy text on the phone, then tap "Send clipboard" on the device screen,
   or the quick-settings tile. The mock logs a `kdeconnect.clipboard` packet.
4. Clipboard, peer to phone: `send --type kdeconnect.clipboard --body '{"content":"from laptop"}'`
   writes the text to the phone clipboard. A `kdeconnect.clipboard.connect` with an older timestamp
   must not change it.

The mock advertises the battery and clipboard capabilities, so the app loads those plugins for it.
Run `send --help` for the full example list.

## Manual interop checks (Phase 4)

Grant notification access first: Sync screen, "Open settings", enable Tandem. Then:

1. Post a notification on the phone (a chat message). It appears on the desktop with its app icon.
2. Dismiss it on the desktop. It disappears on the phone, and the reverse.
3. Reply from the desktop popup. The reply is sent in the messaging app.
4. Press an action button on the desktop. It runs on the phone.
5. Turn an app off in the Sync screen. Its notifications no longer reach the desktop.
6. Disconnect, post 3 notifications, reconnect. The desktop asks for the list and shows each once.

Limits: `clipboard.file` is receive only. The app list shows Sync and Hidden; Preview only is in the data but has no control yet.

## Manual interop checks (Phase 6)

Remote input drives the desktop; nothing needs an accessibility service on the phone.

1. Remote tab, Trackpad: drag with one finger. The desktop pointer moves. Tap once for a left click, twice
   quickly for a double click. Left, Middle and Right buttons sit under the pad.
2. Two fingers up or down scroll the page. Fingers moving up scroll down; flip `TrackpadLogic.scrollAmount`
   if your desktop feels reversed.
3. Hold one finger still for about half a second, then drag: the left button is held (drag and drop) and
   released when you lift.
4. Keyboard mode: type text and send. It appears in the focused desktop window. A new line is sent as Enter.
5. Turn on Ctrl, then send `c`: the desktop gets Ctrl+C. The key buttons (Esc, Tab, Enter, arrows, Home,
   End, PgUp, PgDn, Backspace, Delete) also use the held modifiers. Modifiers reset after each send.
6. With a mock peer, `python3 tools/mockpeer.py` logs the `kdeconnect.mousepad.request` packets.

## Manual interop checks (Phase 7)

1. On the desktop, start a media player (Firefox with a video, or VLC). Open Home or the Media screen on the phone.
   The title, artist, position and volume show within a second or two.
2. Play and pause, Next and Previous, drag the position slider, change the volume. Each acts on the desktop player.
3. The position advances once per second while playing. Pause on the desktop and it stops.
4. With two players open, the chips under the controls list both. Tap one: the screen switches to it.
5. Lock the phone and pull down the quick settings: a media card shows the laptop's track with play, pause,
   previous, next and a seek bar. Each control acts on the desktop.
6. Close the player: the Home media card disappears, the Media screen reads "Nothing playing", and the
   media notification goes away.

Limits: no album art, shuffle or loop control. The notification service starts when media appears; Android may
refuse that start while the app is in the background, and the next player change retries.

## Manual interop checks (Phase 8)

1. Open a slide deck on the desktop. Remote tab, Presenter icon. Tap Start slideshow: the desktop gets F5.
2. Back and Next send Page Up and Page Down. The volume keys do the same while this screen is open:
   volume down is next, volume up is previous, and the system volume does not change.
3. Hold the pad and turn the phone: a red dot appears on the desktop and follows where the phone points.
   Let go and it disappears. Turn right moves it right; point higher moves it up.
4. A phone without a rotation sensor still works: hold, then drag on the pad.
5. End slideshow sends Escape. Leaving the screen hides the dot and gives the volume keys back.

Limits: the desktop does not report the slide count, so the screen counts slides from the keys sent ("Slide N",
no total) and starts again at 1 on Start slideshow. The dot's speed is `GyroMath.SENSITIVITY`.

## Manual interop checks (Phase 9)

1. On the desktop, add a command in KDE Connect settings, Run Command plugin (for example "Lock screen",
   `loginctl lock-session`). Open Remote, then the commands icon on the phone: the list shows it.
2. Tap a command: it runs on the desktop and a "sent to laptop" message shows. Commands that look like power
   or data loss (shutdown, reboot, `rm -rf`, logout) ask for a confirmation first.
3. "Add commands on laptop" opens the desktop's own dialog for adding commands.
4. Add or remove a command on the desktop, then reopen the screen: the list refreshes.
5. Disconnect the laptop: the list empties and the screen says there are no commands.

Limits: icons and the "ask first" flag are guessed from the command's name and text. The desktop's command output
and finished packets are not shown. Device controls (Android 11 power menu tiles) are not built.

## Manual interop checks (Phase 10)

1. Desktop: KDE Connect, Find My Phone. Click Ring. The phone rings on the alarm stream at full volume (even on
   silent), vibrates and blinks the flashlight, and a full-screen "is looking for this phone" screen shows.
2. Stop it from the phone (button, swipe, or the notification action), or click Ring again on the laptop. The
   alarm volume is put back. It also stops by itself after three minutes.
3. Phone to laptop: Devices, the laptop, "Ring laptop". The desktop plays its Find This Device sound. If the
   laptop's "Find this device" plugin is off, the app says it cannot ring.
4. With the phone locked, the full-screen screen should show over the lock screen. On Android 14 and newer the
   system may refuse that to a non-alarm app; the notification still shows and tapping it opens the screen.

Limits: Do Not Disturb can still silence the alarm stream on some phones, and there is no toggle for DND access.


Verified on an A015 (Android) with Tandem, the host on the same Wi-Fi (`192.168.29.0/24`).
Replace `SERIAL` with the phone's `adb devices` serial and `PHONE_IP` with its Wi-Fi address
(`adb -s SERIAL shell ip -4 addr show wlan0`).

```sh
ADB="adb -s SERIAL"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant app.tandem android.permission.POST_NOTIFICATIONS   # else no "Ping from" notification
$ADB shell monkey -p app.tandem -c android.intent.category.LAUNCHER 1

# Host: the mock shares UDP 1716 with a running kdeconnectd (SO_REUSEADDR). Firewall must allow
# inbound UDP 1716 and TCP 1716-1764 (ufw: `sudo ufw allow 1714:1764/udp`, `sudo ufw allow 1714:1764/tcp`).
# Wait for the phone's broadcast, then answer its pairing request with y on stdin.
mkfifo tmp/answer.fifo; (sleep 400 > tmp/answer.fifo &)
python3 tools/mockpeer.py pair --on-request ask --timeout 90 --wait 300 \
    --announce 255.255.255.255 --announce PHONE_IP:1716 --announce-every 3 < tmp/answer.fifo
# Tap the device in Tandem and tap Pair. Compare the 8-character codes on both screens, then:
echo y > tmp/answer.fifo
# Pairing is stored in ~/.config/mockpeer/paired.json. Note the phone's deviceId from the
# "pairing request from ..." line, then ping and wait for replies (battery arrives on connect):
python3 tools/mockpeer.py send --peer DEVICE_ID --type kdeconnect.ping --body '{"message":"hi"}' --timeout 90 --wait 20 \
    --announce 255.255.255.255 --announce PHONE_IP:1716 --announce-every 3
$ADB exec-out screencap -p > tmp/shots/phone.png
```

Notes: `pair` exits after one outcome, so restrict it with `--peer` when another KDE Connect device is
on the LAN. The `pair` run answers only what you type. Use `--on-request accept` to skip the prompt.
A host `kdeconnectd` on 1716 pairs with the phone too; it can reject the mock's request.

## Fake desktop (`tools/mockpeer.py`)

A stand-in KDE Connect v8 desktop, so discovery and pairing can be tested without a real desktop.
Standard library, plus `cryptography` for listen, pair and send (`pip install cryptography`).
`verify-key` and `announce` need nothing extra. Each run logs every packet as pretty JSON on stdout
and events on stderr. Identity and paired devices live in `~/.config/mockpeer` (`--state-dir` to change).

| Subcommand | Does |
|---|---|
| `listen` | Answers the app's UDP identity by dialing its TCP port; accepts TCP; TLS with the right role; logs packets |
| `pair` | Sends (`--request`) or answers (`--on-request ask\|accept\|reject`) a pairing request; prints the 8-character code |
| `send` | Sends one packet (`--type`, default `kdeconnect.ping`; `--body` JSON) over a paired link. Phase 2 examples: `send --help` |
| `announce` | Sends one UDP identity packet |
| `verify-key` | Prints the pairing code for two SPKI DER files |

Exit codes: 0 ok, 1 error, 2 usage, 3 timeout, 4 rejected or not paired, 130 interrupted.
`mockpeer.py <subcommand> --help` lists every option.

```sh
# Same LAN as the phone: wait for the app's broadcast, answer it, pair, then ping.
python3 tools/mockpeer.py listen                       # Ctrl-C to stop; announces every 10 s
python3 tools/mockpeer.py pair --request --timeout 30  # run after the app is linked, or
python3 tools/mockpeer.py pair --on-request ask        # answer the app's request (y/N, 30 s window)
python3 tools/mockpeer.py send --type kdeconnect.ping --body '{"message":"hi"}'

# Two mockpeers on one host (loopback, custom ports, no broadcast).
python3 tools/mockpeer.py pair --state-dir /tmp/b --bind 127.0.0.1 --no-announce \
    --discovery-port 19717 --tcp-port 1741 --on-request accept --wait 30
python3 tools/mockpeer.py pair --state-dir /tmp/a --bind 127.0.0.1 --no-announce \
    --discovery-port 19716 --tcp-port 1740 --connect 127.0.0.1:1741 --request

python3 tools/mockpeer.py verify-key our_spki.der peer_spki.der 1732030000
python3 tools/mockpeer.py announce 0123456789abcdef0123456789abcdef "Test" 1716
```

**Pointing the real app at it**

- **Phone on the same Wi-Fi.** Run `listen` or `pair` on the desktop. The mock broadcasts to
  `255.255.255.255:1716` and the phone connects to the TCP port the mock reports (first free in
  1716-1764). If the UDP bind on 1716 fails, the mock stops with an error; stop `kdeconnectd` first, or use
  `--discovery-port` (only useful when the app can reach that port). AP client isolation blocks broadcast.
  Then use the app's "Add by IP" with the desktop's LAN address.
- **Android emulator.** The emulator NAT blocks broadcast. From the app, use "Add by IP" with
  `10.0.2.2` (the host, seen from the emulator). Run `listen --bind 0.0.0.0`, which listens on
  1716 or the next free port; use `--tcp-port` to pin it.
- **adb forward** (the mock dials the app). Find the app's TCP port from `adb logcat -s Tandem:*`
  or `adb shell ss -ltn` (the app listens in 1716-1764). Then:
  `adb forward tcp:17640 tcp:<appPort>` and
  `python3 tools/mockpeer.py send --connect 127.0.0.1:17640`.
- **adb reverse** (the app dials the mock). `adb reverse tcp:1716 tcp:1716` makes the device's
  `127.0.0.1:1716` reach the host's mock on 1716. Use it with the app's "Add by IP" as `127.0.0.1`.

**What is checked:** `tools/tests` (run `cd tools && python3 -m unittest discover -s tests`) covers
packet framing, the pairing code, identity parsing and an in-process pairing and ping over TLS.

## License

GPL-3.0-or-later. See `LICENSE`. The protocol was implemented from observed behaviour and the
interface facts in `PLAN.md`. No code was copied from the reference repositories.
