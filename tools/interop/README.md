# Interop testing against stock kdeconnect-kde

Shared setup for the manual scripts in `PLAN.md` section 3.

- Desktop runs Plasma's KDE Connect. `kdeconnect-cli --list-devices` shows the state.
- Our id: `kdeconnect-cli -l --id-name-only`.
- Desktop logs: `QT_LOGGING_RULES="kdeconnect.*.debug=true" kdeconnectd --replace`.
- Phone logs: `adb logcat -s Tandem:*`.
- Record the desktop version in the PR (`kdeconnect-cli --version`).

## Phase 1: discovery and pairing

1. Desktop: `kdeconnect-cli --refresh; kdeconnect-cli -l`. Expect the phone, reachable, not paired.
2. Phone: Devices screen. Expect the desktop under "Available".
3. Phone: tap Pair. The desktop shows an 8-char key. Check it matches, accept on the desktop.
   `kdeconnect-cli -l` shows `(paired and reachable)`.
4. Desktop: `kdeconnect-cli --unpair -d <id>`. The phone shows "not paired" within 2 s.
5. Desktop: `kdeconnect-cli --pair -d <id>`. Accept on the phone.
6. Toggle phone Wi-Fi off and on. Reachable again within 10 s.
7. `kdeconnectd --replace`. Reachable again within 10 s.
8. Lock the phone for 30 minutes. `kdeconnect-cli --ping -d <id>` still succeeds.
9. Wireshark `tcp.port in {1714..1764}`: first TCP packet is plaintext JSON identity, then TLS 1.2.
10. Delete `~/.config/kdeconnect/certificate.pem`, restart `kdeconnectd`. The phone logs
    "certificate mismatch" and refuses the device until re-paired.
