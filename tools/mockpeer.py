#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fake KDE Connect v8 desktop peer for testing the Tandem app.

Subcommands (run `mockpeer.py <subcommand> --help` for options):

  verify-key  <our_spki.der> <peer_spki.der> <timestamp_sec>
      Prints the 8-character pairing code (same rule as protocol/VerificationKey.kt).
  announce    <deviceId> <deviceName> <tcpPort> [--to HOST] [--discovery-port N]
      Sends one UDP identity packet.
  listen      Answers the app's UDP identity, accepts TCP, runs TLS, logs every packet.
  pair        Sends or answers a pairing request and shows the verification code.
  send        Sends one packet (kdeconnect.ping by default) over a paired TLS link.

Standard library only, except listen, pair and send, which need the `cryptography`
package for the self-signed EC P-256 certificate. verify-key and announce do not.

Exit codes: 0 ok, 1 runtime error, 2 usage error, 3 timeout, 4 rejected or not paired,
130 interrupted.

Roles (PLAN.md 2.2): a connection we accept over TCP is a TLS client; a connection we
open to the app is a TLS server. Both sides then exchange a secure identity line.
"""

import argparse
import base64
import ctypes
import datetime
import hashlib
import json
import os
import pathlib
import queue
import re
import secrets
import socket
import ssl
import sys
import threading
import time
from dataclasses import dataclass, field

DISCOVERY_PORT = 1716
TCP_PORTS = range(1716, 1765)
PROTOCOL_VERSION = 8
# Advertised like a desktop with the Phase 2 plugins, so the app loads them for this peer.
# Packets outside pair and identity are logged by `listen`; `send` can send any of them.
CAPABILITIES = [
    "kdeconnect.ping",
    "kdeconnect.pair",
    "kdeconnect.battery",
    "kdeconnect.battery.request",
    "kdeconnect.clipboard",
    "kdeconnect.clipboard.connect",
    "kdeconnect.share.request",
    "kdeconnect.share.request.update",
]
PAYLOAD_PORTS = range(1739, 1765)
PAYLOAD_CHUNK = 256 * 1024
PAYLOAD_IDLE_TIMEOUT = 30.0
PAYLOAD_ACCEPT_TIMEOUT = 10.0
SHARE_TYPE = "kdeconnect.share.request"
SHARE_UPDATE_TYPE = "kdeconnect.share.request.update"
NAME_MAX_BYTES = 200
MAX_IDENTITY_LINE = 512 * 1024
MAX_PACKET_LINE = 32 * 1024 * 1024
HANDSHAKE_TIMEOUT = 10.0
SECURE_IDENTITY_TIMEOUT = 5.0
PAIR_MAX_SKEW_SEC = 1800
DIAL_INTERVAL_SEC = 1.0
DEFAULT_STATE_DIR = "~/.config/mockpeer"
DEVICE_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{32,38}$")
NAME_STRIP = "\"',;:.!?()[]<>"
NAME_MAX = 32

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_USAGE = 2
EXIT_TIMEOUT = 3
EXIT_REJECTED = 4
EXIT_INTERRUPTED = 130


class MockPeerError(Exception):
    """Failure with a message for the user and the exit code to return."""

    def __init__(self, message, code=EXIT_ERROR):
        super().__init__(message)
        self.code = code


class ProtocolError(MockPeerError):
    """A packet or identity that does not follow the protocol."""


# ---------------------------------------------------------------- logging

def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", file=sys.stderr, flush=True)


def show_packet(direction, label, obj):
    print(f"[{time.strftime('%H:%M:%S')}] {direction} {label}", flush=True)
    print(json.dumps(obj, indent=2, ensure_ascii=False), flush=True)


# ---------------------------------------------------------------- framing (pure)

def build_packet(ptype, body, *, payload_size=None, payload_port=None, packet_id=None):
    """Returns the packet as a dict. payloadSize is omitted unless given."""
    if not isinstance(ptype, str) or not ptype:
        raise ProtocolError("packet type must be a non-empty string")
    if not isinstance(body, dict):
        raise ProtocolError("body must be a JSON object")
    pkt = {
        "id": int(time.time() * 1000) if packet_id is None else packet_id,
        "type": ptype,
        "body": body,
    }
    if payload_size is not None:
        if payload_port is None:
            raise ProtocolError("payloadTransferInfo.port is required when payloadSize is set")
        pkt["payloadSize"] = payload_size
        pkt["payloadTransferInfo"] = {"port": payload_port}
    return pkt


def encode_packet(pkt):
    """One JSON object, UTF-8, newline terminated."""
    data = (json.dumps(pkt, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
    if len(data) > MAX_PACKET_LINE:
        raise ProtocolError(f"packet is {len(data)} bytes, limit is {MAX_PACKET_LINE}")
    return data


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def decode_packet(raw):
    """Validates one line (bytes or str) and returns the packet dict."""
    if isinstance(raw, (bytes, bytearray)):
        try:
            text = bytes(raw).decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ProtocolError(f"packet is not UTF-8: {exc}") from None
    else:
        text = raw
    try:
        obj = json.loads(text)
    except ValueError as exc:
        raise ProtocolError(f"packet is not JSON: {exc}") from None
    if not isinstance(obj, dict):
        raise ProtocolError("packet is not a JSON object")
    ptype = obj.get("type")
    if not isinstance(ptype, str) or not ptype:
        raise ProtocolError("packet has no type")
    if not isinstance(obj.get("body"), dict):
        raise ProtocolError(f"{ptype}: body must be a JSON object")
    if "payloadSize" in obj:
        size = obj["payloadSize"]
        if not _is_int(size) or size < -1:
            raise ProtocolError(f"{ptype}: payloadSize must be an integer >= -1")
        info = obj.get("payloadTransferInfo")
        if not isinstance(info, dict) or not _is_int(info.get("port")):
            raise ProtocolError(f"{ptype}: payloadSize without payloadTransferInfo.port")
    return obj


class LineSplitter:
    """Splits a byte stream into newline-terminated lines. Over-long lines are dropped."""

    def __init__(self, limit):
        self.limit = limit
        self.dropped = 0
        self._buf = bytearray()
        self._skipping = False

    def feed(self, data):
        out = []
        start = 0
        while True:
            end = data.find(b"\n", start)
            if end < 0:
                if not self._skipping:
                    self._buf += data[start:]
                    if len(self._buf) > self.limit:
                        self._buf.clear()
                        self._skipping = True
                return out
            if self._skipping:
                self._skipping = False
                self.dropped += 1
            else:
                self._buf += data[start:end]
                if len(self._buf) > self.limit:
                    self.dropped += 1
                elif self._buf.strip():
                    out.append(bytes(self._buf))
            self._buf.clear()
            start = end + 1


def read_line(sock, limit):
    """Reads one line one byte at a time, so nothing after the newline is consumed.

    Needed before TLS: a buffered reader would swallow the start of the ClientHello.
    Returns the line without its newline (possibly empty).
    """
    out = bytearray()
    while True:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("peer closed the connection while a line was expected")
        if chunk == b"\n":
            return bytes(out)
        out += chunk
        if len(out) > limit:
            raise ProtocolError(f"line longer than {limit} bytes")


# ---------------------------------------------------------------- body and identity parsing (pure)

def body_int(body, key):
    """Integer from a JSON number or a numeric string; None otherwise."""
    value = body.get(key)
    if _is_int(value):
        return value
    if isinstance(value, str) and re.fullmatch(r"-?\d+", value):
        return int(value)
    return None


def body_bool(body, key):
    value = body.get(key)
    if isinstance(value, bool):
        return value
    if value in ("true", "false"):
        return value == "true"
    return None


def _string_list(value):
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def valid_device_id(value):
    return isinstance(value, str) and DEVICE_ID_RE.match(value) is not None


def filter_name(raw):
    """Same rule as DeviceInfo::filterName: drop punctuation, cut to 32 characters."""
    return "".join(ch for ch in str(raw) if ch not in NAME_STRIP)[:NAME_MAX]


@dataclass
class PeerIdentity:
    device_id: str
    name: str = "unnamed"
    device_type: str = "desktop"
    protocol_version: int | None = None
    incoming: list = field(default_factory=list)
    outgoing: list = field(default_factory=list)
    tcp_port: int | None = None


def parse_identity(obj):
    """Parses a kdeconnect.identity packet (UDP, plaintext TCP or secure). Only deviceId is required."""
    if obj["type"] != "kdeconnect.identity":
        raise ProtocolError(f"expected kdeconnect.identity, got {obj['type']}")
    body = obj["body"]
    device_id = body.get("deviceId")
    if not valid_device_id(device_id):
        raise ProtocolError(f"invalid deviceId {device_id!r}")
    dtype = body.get("deviceType")
    return PeerIdentity(
        device_id=device_id,
        name=filter_name(body.get("deviceName", "")) or "unnamed",
        device_type=dtype if isinstance(dtype, str) else "desktop",
        protocol_version=body_int(body, "protocolVersion"),
        incoming=_string_list(body.get("incomingCapabilities")),
        outgoing=_string_list(body.get("outgoingCapabilities")),
        tcp_port=body_int(body, "tcpPort"),
    )


def check_connection_targets(body, our_device_id):
    """targetDeviceId and targetProtocolVersion, when present, must name us (PLAN 2.2)."""
    if "targetDeviceId" in body and body["targetDeviceId"] != our_device_id:
        raise ProtocolError(f"targetDeviceId {body['targetDeviceId']!r} is not our id")
    if "targetProtocolVersion" in body and body_int(body, "targetProtocolVersion") != PROTOCOL_VERSION:
        raise ProtocolError("targetProtocolVersion is not 8")


def identity_body(device_name, device_id, *, target_id=None, tcp_port=None):
    body = {
        "deviceId": device_id,
        "deviceName": device_name,
        "deviceType": "desktop",
        "protocolVersion": PROTOCOL_VERSION,
        "incomingCapabilities": CAPABILITIES,
        "outgoingCapabilities": CAPABILITIES,
    }
    if target_id is not None:
        body["targetDeviceId"] = target_id
        body["targetProtocolVersion"] = PROTOCOL_VERSION
    if tcp_port is not None:
        body["tcpPort"] = tcp_port
    return body


# ---------------------------------------------------------------- pairing code (pure)

def unsigned_compare(a: bytes, b: bytes) -> int:
    for x, y in zip(a, b):
        if x != y:
            return x - y
    return len(a) - len(b)


def verification_key(ours: bytes, peer: bytes, timestamp_sec: int) -> str:
    """8-char pairing code: SHA-256 over the larger SPKI DER, the smaller, then the timestamp."""
    first, second = (peer, ours) if unsigned_compare(ours, peer) < 0 else (ours, peer)
    digest = hashlib.sha256(first + second + str(timestamp_sec).encode("ascii")).hexdigest()
    return digest[:8].upper()


# ---------------------------------------------------------------- certificates and state

def _cryptography():
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
    except ImportError:
        raise MockPeerError(
            "listen, pair and send need the 'cryptography' package (pip install cryptography). "
            "verify-key and announce do not."
        ) from None
    return x509, hashes, serialization, ec, NameOID


def cert_common_name(der):
    x509, _, _, _, name_oid = _cryptography()
    attrs = x509.load_der_x509_certificate(der).subject.get_attributes_for_oid(name_oid.COMMON_NAME)
    return attrs[0].value if attrs else None


def spki_of_cert_der(der):
    x509, _, serialization, _, _ = _cryptography()
    cert = x509.load_der_x509_certificate(der)
    return cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


@dataclass
class LocalIdentity:
    device_id: str
    name: str
    cert_path: pathlib.Path
    key_path: pathlib.Path
    cert_der: bytes
    spki_der: bytes


def load_or_create_identity(state_dir, device_id=None, name=None):
    """Loads identity.json, cert.pem and key.pem from state_dir, creating them if missing."""
    x509, hashes, serialization, ec, name_oid = _cryptography()
    state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    meta_path = state_dir / "identity.json"
    cert_path = state_dir / "cert.pem"
    key_path = state_dir / "key.pem"
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            stored_id = meta["deviceId"]
            stored_name = meta.get("deviceName", "Mock Desktop")
        except (ValueError, KeyError, OSError) as exc:
            raise MockPeerError(f"{meta_path} is unreadable ({exc}); delete the state dir") from None
        if device_id and device_id != stored_id:
            raise MockPeerError(
                f"{state_dir} belongs to device {stored_id}; use another --state-dir or delete it"
            )
        device_id = stored_id
        new_name = filter_name(name) if name else stored_name
        if new_name != stored_name:
            meta_path.write_text(json.dumps({"deviceId": device_id, "deviceName": new_name}), encoding="utf-8")
    else:
        device_id = device_id or secrets.token_hex(16)
        if not valid_device_id(device_id):
            raise MockPeerError("--device-id must be 32 to 38 characters of [a-zA-Z0-9_-]", EXIT_USAGE)
        new_name = filter_name(name or "Mock Desktop")
        key = ec.generate_private_key(ec.SECP256R1())
        subject = x509.Name([
            x509.NameAttribute(name_oid.COMMON_NAME, device_id),
            x509.NameAttribute(name_oid.ORGANIZATIONAL_UNIT_NAME, "KDE Connect"),
            x509.NameAttribute(name_oid.ORGANIZATION_NAME, "KDE"),
        ])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(key.public_key())
            .serial_number(1)
            .not_valid_before(now - datetime.timedelta(days=365))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256())
        )
        key_pem = key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        )
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(key_pem)
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        meta_path.write_text(json.dumps({"deviceId": device_id, "deviceName": new_name}), encoding="utf-8")
        log(f"created identity {device_id} in {state_dir}")

    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    attrs = cert.subject.get_attributes_for_oid(name_oid.COMMON_NAME)
    if not attrs or attrs[0].value != device_id:
        raise MockPeerError(f"{cert_path} CN does not match deviceId {device_id}")
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    spki = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return LocalIdentity(device_id, new_name, cert_path, key_path, cert_der, spki)


class PairStore:
    """paired.json in the state dir: peers we completed pairing with."""

    def __init__(self, state_dir):
        self.path = state_dir / "paired.json"

    def all(self):
        if not self.path.exists():
            return []
        try:
            records = json.loads(self.path.read_text(encoding="utf-8"))
        except ValueError as exc:
            raise MockPeerError(f"{self.path} is not valid JSON: {exc}") from None
        return records if isinstance(records, list) else []

    def get(self, device_id):
        for record in self.all():
            if record.get("deviceId") == device_id:
                return record
        return None

    def add(self, peer, cert_der):
        records = [r for r in self.all() if r.get("deviceId") != peer.device_id]
        records.append({
            "deviceId": peer.device_id,
            "name": peer.name,
            "deviceType": peer.device_type,
            "protocolVersion": peer.protocol_version,
            "certDer": base64.b64encode(cert_der).decode("ascii"),
            "pairedAt": int(time.time()),
        })
        self._save(records)

    def remove(self, device_id):
        self._save([r for r in self.all() if r.get("deviceId") != device_id])

    def _save(self, records):
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(records, handle, indent=2)
        os.replace(tmp, self.path)


# ---------------------------------------------------------------- TLS

_LIBSSL = None
_KEEP_ALIVE = []
_VERIFY_CB = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.c_void_p)


def _openssl():
    global _LIBSSL
    if _LIBSSL is None:
        try:
            import _ssl
            lib = ctypes.CDLL(_ssl.__file__)
        except (ImportError, OSError) as exc:
            raise MockPeerError(f"cannot load OpenSSL through _ssl: {exc}") from None
        lib.SSL_CTX_get_verify_mode.argtypes = [ctypes.c_void_p]
        lib.SSL_CTX_get_verify_mode.restype = ctypes.c_int
        lib.SSL_CTX_set_verify.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        lib.SSL_CTX_set_verify.restype = None
        _LIBSSL = lib
    return _LIBSSL


def _accept_any_client_certificate(ctx):
    """Makes a TLS server request a client certificate and accept any self-signed one.

    The stdlib cannot ask for a client cert it does not trust (the app's cert is self-signed
    and unknown in advance), so the OpenSSL verify callback is replaced through ctypes.
    In CPython the SSL_CTX pointer follows PyObject_HEAD in _SSLContext. The offset is
    checked against the verify mode before the callback is set.
    """
    lib = _openssl()
    ptr = ctypes.c_void_p.from_address(id(ctx) + 2 * ctypes.sizeof(ctypes.c_void_p)).value
    if not ptr or lib.SSL_CTX_get_verify_mode(ptr) != int(ssl.CERT_OPTIONAL):
        raise MockPeerError("cannot hook OpenSSL peer verification on this Python build")
    callback = _VERIFY_CB(lambda ok, store: 1)
    _KEEP_ALIVE.append(callback)
    lib.SSL_CTX_set_verify(ptr, int(ssl.CERT_OPTIONAL), ctypes.cast(callback, ctypes.c_void_p))


def _tls_context(ident, server_side):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER if server_side else ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(str(ident.cert_path), str(ident.key_path))
    if server_side:
        ctx.verify_mode = ssl.CERT_OPTIONAL
        _accept_any_client_certificate(ctx)
    else:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


def wrap_tls(sock, ident, server_side):
    ctx = _tls_context(ident, server_side)
    try:
        return ctx.wrap_socket(sock, server_side=server_side)
    except ssl.SSLError as exc:
        raise MockPeerError(f"TLS handshake failed: {exc}") from None


# ---------------------------------------------------------------- node (sockets, links)

class Link:
    def __init__(self, ssock, peer, outbound, remote, cert_der):
        self.ssock = ssock
        self.peer = peer
        self.outbound = outbound
        self.remote = remote
        self.cert_der = cert_der
        self.tls_version = ssock.version()
        self._send_lock = threading.Lock()

    @property
    def label(self):
        role = "outbound" if self.outbound else "inbound"
        return f"{self.peer.name} ({self.peer.device_id}) {self.remote[0]}:{self.remote[1]} {role}"

    def send(self, ptype, body, payload_size=None, payload_port=None):
        pkt = build_packet(ptype, body, payload_size=payload_size, payload_port=payload_port)
        data = encode_packet(pkt)
        with self._send_lock:
            self.ssock.sendall(data)
        show_packet("->", self.label, pkt)

    def close(self):
        try:
            self.ssock.close()
        except OSError:
            pass


def parse_host_port(text, default_port):
    host, sep, port = text.rpartition(":")
    if not sep:
        return text, default_port
    if not port.isdigit() or not host:
        raise MockPeerError(f"bad address {text!r}, expected HOST or HOST:PORT", EXIT_USAGE)
    return host, int(port)


class Node:
    """Listens on UDP discovery and a TCP port, announces, and runs one TLS link per peer."""

    def __init__(self, ident, *, bind="0.0.0.0", discovery_port=DISCOVERY_PORT, tcp_port=None,
                 on_link=None, on_packet=None):
        self.ident = ident
        self.bind = bind
        self.discovery_port = discovery_port
        self.preferred_tcp_port = tcp_port
        self.tcp_port = None
        self.on_link = on_link or (lambda link: None)
        self.on_packet = on_packet or (lambda link, obj: None)
        self.links = {}
        self._links_lock = threading.Lock()
        self._dial_times = {}
        self._stop = threading.Event()
        self._server = None
        self._udp = None

    # -- lifecycle

    def start(self):
        self._listen_tcp()
        self._bind_udp()
        log(f"listening: TCP {self.bind}:{self.tcp_port}, UDP {self.bind}:{self.discovery_port}")
        threading.Thread(target=self._accept_loop, daemon=True).start()
        threading.Thread(target=self._udp_loop, daemon=True).start()

    def stop(self):
        self._stop.set()
        for sock in (self._server, self._udp):
            if sock is not None:
                sock.close()
        with self._links_lock:
            links = list(self.links.values())
            self.links.clear()
        for link in links:
            link.close()

    def _listen_tcp(self):
        # An explicit port is used as given (0 = any free port, for tests); otherwise 1716-1764.
        candidates = [self.preferred_tcp_port] if self.preferred_tcp_port is not None else list(TCP_PORTS)
        for port in candidates:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind((self.bind, port))
                sock.listen(8)
            except OSError:
                sock.close()
                continue
            sock.settimeout(0.5)
            self._server = sock
            self.tcp_port = sock.getsockname()[1]
            return
        where = f"port {self.preferred_tcp_port}" if self.preferred_tcp_port is not None else "1716-1764"
        raise MockPeerError(f"no free TCP port in {where} on {self.bind}")

    def _bind_udp(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            sock.bind((self.bind, self.discovery_port))
        except OSError as exc:
            sock.close()
            raise MockPeerError(
                f"cannot bind UDP {self.bind}:{self.discovery_port}: {exc}. "
                "Stop kdeconnectd or pass --discovery-port"
            ) from None
        sock.settimeout(0.5)
        self._udp = sock
        self.discovery_port = sock.getsockname()[1]

    # -- discovery

    def announce(self, targets, every):
        """Sends our identity to each target now, then every `every` seconds (0 = once)."""
        threading.Thread(target=self._announce_loop, args=(targets, every), daemon=True).start()

    def _announce_loop(self, targets, every):
        while not self._stop.is_set():
            for host, port in targets:
                self._send_identity(host, port)
            if every <= 0:
                return
            self._stop.wait(every)

    def _send_identity(self, host, port):
        body = identity_body(self.ident.name, self.ident.device_id, tcp_port=self.tcp_port)
        pkt = build_packet("kdeconnect.identity", body)
        try:
            self._udp.sendto(encode_packet(pkt), (host, port))
        except OSError as exc:
            log(f"announce to {host}:{port} failed: {exc}")
            return
        show_packet("-> udp", f"{host}:{port}", pkt)

    def _udp_loop(self):
        while not self._stop.is_set():
            try:
                data, addr = self._udp.recvfrom(65536)
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                obj = decode_packet(data.strip())
            except ProtocolError as exc:
                log(f"ignored UDP packet from {addr[0]}:{addr[1]}: {exc}")
                continue
            show_packet("<- udp", f"{addr[0]}:{addr[1]}", obj)
            if obj["type"] != "kdeconnect.identity":
                continue
            try:
                peer = parse_identity(obj)
            except ProtocolError as exc:
                log(f"ignored identity from {addr[0]}: {exc}")
                continue
            if peer.device_id == self.ident.device_id:
                continue
            if peer.tcp_port not in TCP_PORTS:
                log(f"ignored identity from {peer.name}: tcpPort missing or outside 1716-1764")
                continue
            now = time.monotonic()
            key = (peer.device_id, addr[0])
            if now - self._dial_times.get(key, 0.0) < DIAL_INTERVAL_SEC:
                continue
            self._dial_times[key] = now
            log(f"discovered {peer.name} ({peer.device_id}) at {addr[0]}:{peer.tcp_port}, connecting")
            threading.Thread(
                target=self._handshake,
                args=(None, (addr[0], peer.tcp_port), True, peer.device_id),
                daemon=True,
            ).start()

    def dial(self, host, port):
        threading.Thread(target=self._handshake, args=(None, (host, port), True, None), daemon=True).start()

    # -- TCP

    def _accept_loop(self):
        while not self._stop.is_set():
            try:
                conn, addr = self._server.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            threading.Thread(target=self._handshake, args=(conn, addr, False, None), daemon=True).start()

    def _handshake(self, sock, addr, outbound, expected_id):
        direction = "outbound" if outbound else "inbound"
        try:
            if sock is None:
                sock = socket.create_connection(addr, timeout=HANDSHAKE_TIMEOUT)
            link = self._establish(sock, addr, outbound, expected_id)
        except (MockPeerError, OSError, ValueError) as exc:
            log(f"{direction} {addr[0]}:{addr[1]} failed: {exc}")
            if sock is not None:
                sock.close()
            return
        self._register(link)

    def _establish(self, sock, addr, outbound, expected_id):
        """Wire order of PLAN 2.2. The dialer (outbound) sends the plaintext identity first and
        becomes the TLS server. The acceptor reads that plaintext identity first and becomes the
        TLS client, and it sends nothing before the TLS handshake. Both sides then exchange the
        secure identity."""
        sock.settimeout(HANDSHAKE_TIMEOUT)
        plain = None
        if outbound:
            plain_out = build_packet("kdeconnect.identity", identity_body(
                self.ident.name, self.ident.device_id, target_id=expected_id))
            sock.sendall(encode_packet(plain_out))
            show_packet("-> plaintext", f"{addr[0]}:{addr[1]}", plain_out)
        else:
            plain = self._read_identity(sock, addr, "plaintext")
            check_connection_targets(plain["body"], self.ident.device_id)
            peer_plain = parse_identity(plain)
            if expected_id and peer_plain.device_id != expected_id:
                raise ProtocolError(f"expected deviceId {expected_id}, peer says {peer_plain.device_id}")

        ssock = wrap_tls(sock, self.ident, server_side=outbound)
        der = ssock.getpeercert(binary_form=True)
        if not der:
            raise MockPeerError("peer sent no certificate (nothing to pin or to show a code for)")

        secure_out = build_packet("kdeconnect.identity", identity_body(self.ident.name, self.ident.device_id))
        ssock.sendall(encode_packet(secure_out))
        show_packet("-> secure", f"{addr[0]}:{addr[1]}", secure_out)
        secure = self._read_identity(ssock, addr, "secure", SECURE_IDENTITY_TIMEOUT)
        sec = parse_identity(secure)
        if plain is not None and sec.device_id != peer_plain.device_id:
            raise ProtocolError("secure identity deviceId differs from plaintext identity")
        if expected_id and sec.device_id != expected_id:
            raise ProtocolError(f"expected deviceId {expected_id}, peer says {sec.device_id}")
        if cert_common_name(der) != sec.device_id:
            raise ProtocolError(f"certificate CN does not match deviceId {sec.device_id}")
        if plain is not None and peer_plain.protocol_version is not None \
                and sec.protocol_version != peer_plain.protocol_version:
            raise ProtocolError("secure identity protocolVersion differs from plaintext identity")
        if sec.protocol_version is not None and sec.protocol_version < PROTOCOL_VERSION:
            log(f"warning: {sec.name} speaks protocol {sec.protocol_version}, we expect 8")
        ssock.settimeout(None)
        return Link(ssock, sec, outbound, addr, der)

    def _read_identity(self, sock, addr, stage, timeout=HANDSHAKE_TIMEOUT):
        sock.settimeout(timeout)
        while True:
            raw = read_line(sock, MAX_IDENTITY_LINE)
            if raw.strip():
                break
        obj = decode_packet(raw)
        show_packet(f"<- {stage}", f"{addr[0]}:{addr[1]}", obj)
        return obj

    def _register(self, link):
        with self._links_lock:
            old = self.links.get(link.peer.device_id)
            self.links[link.peer.device_id] = link
        if old is not None:
            old.close()
        log(f"linked {link.label}, TLS {link.tls_version}")
        threading.Thread(target=self._reader, args=(link,), daemon=True).start()
        try:
            self.on_link(link)
        except MockPeerError as exc:
            log(f"link handler failed: {exc}")

    def _reader(self, link):
        splitter = LineSplitter(MAX_PACKET_LINE)
        try:
            while not self._stop.is_set():
                data = link.ssock.recv(65536)
                if not data:
                    break
                for raw in splitter.feed(data):
                    try:
                        obj = decode_packet(raw)
                    except ProtocolError as exc:
                        log(f"bad packet from {link.label}: {exc}")
                        continue
                    show_packet("<-", link.label, obj)
                    self.on_packet(link, obj)
        except (OSError, ValueError) as exc:
            log(f"read from {link.label} stopped: {exc}")
        finally:
            with self._links_lock:
                if self.links.get(link.peer.device_id) is link:
                    del self.links[link.peer.device_id]
            link.close()
            log(f"link closed: {link.label}")


# ---------------------------------------------------------------- pairing

class PairFlow:
    """Pairing state for one run. Sends or answers kdeconnect.pair; sets `done` when finished."""

    def __init__(self, ident, store, *, policy, timeout, want_request, only_peer):
        self.ident = ident
        self.store = store
        self.policy = policy
        self.timeout = timeout
        self.want_request = want_request
        self.only_peer = only_peer
        self.done = threading.Event()
        self.result = None
        self.codes = []  # every verification code shown, in order (tests compare both sides)
        self._pending = {}
        self._lock = threading.Lock()

    def _finish(self, result):
        with self._lock:
            if self.result is None:
                self.result = result
        self.done.set()

    def _code(self, link, timestamp):
        code = verification_key(self.ident.spki_der, spki_of_cert_der(link.cert_der), timestamp)
        self.codes.append(code)
        return code

    def on_link(self, link):
        if not self.want_request:
            return
        if self.only_peer and link.peer.device_id != self.only_peer:
            return
        if self.store.get(link.peer.device_id):
            log(f"already paired with {link.peer.name}")
            self._finish("paired")
            return
        with self._lock:
            if link.peer.device_id in self._pending:
                return
            timestamp = int(time.time())
            self._pending[link.peer.device_id] = timestamp
        link.send("kdeconnect.pair", {"pair": True, "timestamp": timestamp})
        log(f"pairing request sent to {link.peer.name}. Verification code: {self._code(link, timestamp)}")
        timer = threading.Timer(self.timeout, self._timed_out, args=(link.peer.device_id,))
        timer.daemon = True
        timer.start()

    def _timed_out(self, device_id):
        with self._lock:
            if self._pending.pop(device_id, None) is None:
                return
        log(f"no answer within {self.timeout:g} s, pairing cancelled")
        self._finish("timeout")

    def on_packet(self, link, obj):
        if obj["type"] != "kdeconnect.pair":
            return
        body = obj["body"]
        flag = body_bool(body, "pair")
        timestamp = body_int(body, "timestamp")
        peer = link.peer
        if flag is None:
            log("pair packet without a pair flag, ignored")
            return
        if flag is False:
            with self._lock:
                pending = self._pending.pop(peer.device_id, None) is not None
            if pending:
                log(f"{peer.name} rejected the pairing")
                self._finish("rejected")
            elif self.store.get(peer.device_id):
                self.store.remove(peer.device_id)
                log(f"{peer.name} unpaired us; trust removed")
            return
        if timestamp is None:
            with self._lock:
                pending = self._pending.pop(peer.device_id, None) is not None
            if not pending:
                log("accept without a pending request, ignored")
                return
            self.store.add(peer, link.cert_der)
            log(f"paired with {peer.name} ({peer.device_id})")
            self._finish("paired")
            return
        skew = int(time.time()) - timestamp
        if abs(skew) > PAIR_MAX_SKEW_SEC:
            link.send("kdeconnect.pair", {"pair": False})
            log(f"request rejected: clock differs by {skew} s (limit {PAIR_MAX_SKEW_SEC} s)")
            self._finish("rejected")
            return
        log(f"pairing request from {peer.name} ({peer.device_id}). Verification code: {self._code(link, timestamp)}")
        if self._decide(peer.name):
            link.send("kdeconnect.pair", {"pair": True})
            self.store.add(peer, link.cert_der)
            log(f"paired with {peer.name} ({peer.device_id})")
            self._finish("paired")
        else:
            link.send("kdeconnect.pair", {"pair": False})
            self._finish("rejected")

    def _decide(self, name):
        if self.policy == "accept":
            return True
        if self.policy == "reject":
            return False
        sys.stderr.write(f"Accept pairing with {name}? [y/N] (answer within {self.timeout:g} s): ")
        sys.stderr.flush()
        answers = queue.Queue()
        threading.Thread(target=lambda: answers.put(sys.stdin.readline()), daemon=True).start()
        try:
            answer = answers.get(timeout=self.timeout)
        except queue.Empty:
            log(f"no answer within {self.timeout:g} s, rejecting")
            return False
        return answer.strip().lower() in ("y", "yes")


# ---------------------------------------------------------------- commands

def _state_dir(args):
    return pathlib.Path(args.state_dir).expanduser()


def _identity(args):
    return load_or_create_identity(_state_dir(args), args.device_id, args.name)


def _node(args, ident, **handlers):
    return Node(
        ident,
        bind=args.bind,
        discovery_port=args.discovery_port,
        tcp_port=args.tcp_port,
        **handlers,
    )


def _start(args, node):
    node.start()
    for text in args.connect or []:
        host, port = parse_host_port(text, DISCOVERY_PORT)
        node.dial(host, port)
    if not args.no_announce:
        targets = [parse_host_port(t, DISCOVERY_PORT) for t in (args.announce or ["255.255.255.255"])]
        node.announce(targets, args.announce_every)


def cmd_verify_key(args):
    keys = []
    for path in (args.our_spki, args.peer_spki):
        try:
            keys.append(pathlib.Path(path).read_bytes())
        except OSError as exc:
            raise MockPeerError(f"cannot read {path}: {exc.strerror}") from None
    if not keys[0] or not keys[1]:
        raise MockPeerError("key file is empty; pass SubjectPublicKeyInfo DER")
    print(verification_key(keys[0], keys[1], args.timestamp))
    return EXIT_OK


def cmd_announce(args):
    body = identity_body(filter_name(args.device_name), args.device_id, tcp_port=args.tcp_port)
    data = encode_packet(build_packet("kdeconnect.identity", body))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            sock.sendto(data, (args.to, args.discovery_port))
        except OSError as exc:
            raise MockPeerError(f"cannot send to {args.to}:{args.discovery_port}: {exc}") from None
    print(f"sent identity to {args.to}:{args.discovery_port}")
    return EXIT_OK


def cmd_listen(args):
    ident = _identity(args)
    store = PairStore(_state_dir(args))

    def on_link(link):
        status = "paired" if store.get(link.peer.device_id) else "NOT paired"
        log(f"{link.peer.name} is {status}")

    node = _node(args, ident, on_link=on_link)
    _start(args, node)
    try:
        if args.duration > 0:
            time.sleep(args.duration)
        else:
            threading.Event().wait()
    finally:
        node.stop()
    return EXIT_OK


def cmd_pair(args):
    ident = _identity(args)
    store = PairStore(_state_dir(args))
    flow = PairFlow(
        ident, store,
        policy=args.on_request,
        timeout=args.timeout,
        want_request=args.request,
        only_peer=args.peer,
    )
    node = _node(args, ident, on_link=flow.on_link, on_packet=flow.on_packet)
    _start(args, node)
    try:
        flow.done.wait(args.wait or None)
    finally:
        time.sleep(0.5)  # let the last accept or reject reach the peer
        node.stop()
    if flow.result is None:
        log("no pairing outcome before --wait ran out")
        return EXIT_TIMEOUT
    return {"paired": EXIT_OK, "timeout": EXIT_TIMEOUT, "rejected": EXIT_REJECTED}[flow.result]


def cmd_send(args):
    try:
        body = json.loads(args.body)
    except ValueError as exc:
        raise MockPeerError(f"--body is not JSON: {exc}", EXIT_USAGE) from None
    if not isinstance(body, dict):
        raise MockPeerError("--body must be a JSON object", EXIT_USAGE)
    ident = _identity(args)
    store = PairStore(_state_dir(args))
    paired = {r["deviceId"] for r in store.all() if not args.peer or r["deviceId"] == args.peer}
    if not paired:
        raise MockPeerError("no paired device matches; run `pair` first", EXIT_REJECTED)
    sent = threading.Event()

    def on_link(link):
        if link.peer.device_id not in paired or sent.is_set():
            return
        link.send(args.type, body)
        sent.set()

    node = _node(args, ident, on_link=on_link)
    _start(args, node)
    try:
        if not sent.wait(args.timeout):
            log(f"no paired link within {args.timeout:g} s")
            return EXIT_TIMEOUT
        log(f"sent {args.type}; listening {args.wait:g} s for replies")
        time.sleep(args.wait)
    finally:
        node.stop()
    return EXIT_OK


# ---------------------------------------------------------------- payloads (share)

def truncate_utf8(name, limit=NAME_MAX_BYTES):
    """Shortens name to limit UTF-8 bytes, keeping a short extension (same rule as FileNames.kt)."""
    if len(name.encode("utf-8")) <= limit:
        return name
    dot = name.rfind(".")
    ext = name[dot:] if dot > 0 and 1 <= len(name) - dot - 1 <= 16 else ""
    base = name[: len(name) - len(ext)]
    budget = limit - len(ext.encode("utf-8"))
    out = ""
    for ch in base:
        if len((out + ch).encode("utf-8")) > budget:
            break
        out += ch
    return (out or "shared-file") + ext


def sanitize_filename(raw):
    """A safe leaf name for a peer's filename: no directories, no control or forbidden characters,
    no leading dots, never empty. Mirrors protocol/share/FileNames.kt."""
    if not isinstance(raw, str):
        return "shared-file"
    leaf = raw.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    cleaned = "".join(
        "_" if (ord(c) < 0x20 or ord(c) == 0x7F or c in '<>:"|?*') else c for c in leaf
    )
    trimmed = cleaned.strip().lstrip(".").rstrip(". ")
    if not trimmed or set(trimmed) == {"_"}:
        return "shared-file"
    return truncate_utf8(trimmed)


def unique_name(name, exists):
    """Returns name, or "base (n).ext" with the first n that exists() does not report."""
    if not exists(name):
        return name
    dot = name.rfind(".")
    has_ext = dot > 0 and 1 <= len(name) - dot - 1 <= 16
    base, ext = (name[:dot], name[dot:]) if has_ext else (name, "")
    n = 1
    while exists(f"{base} ({n}){ext}"):
        n += 1
    return f"{base} ({n}){ext}"


def bind_payload_listener(bind="0.0.0.0", ports=PAYLOAD_PORTS):
    """First free port in the payload range. Returns (port, listening socket)."""
    for port in ports:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((bind, port))
        except OSError:
            sock.close()
            continue
        sock.listen(1)
        return port, sock
    raise MockPeerError(f"no free payload port in {ports.start}-{ports.stop - 1}")


def serve_payload(listener, ident, source, size, *, expected_der=None,
                  accept_timeout=PAYLOAD_ACCEPT_TIMEOUT, chunk=PAYLOAD_CHUNK):
    """Accepts one peer on listener, runs TLS as server and writes size bytes from source (size -1: to EOF).
    With expected_der, the peer's certificate must equal it. Returns the bytes written."""
    listener.settimeout(accept_timeout)
    try:
        conn, _ = listener.accept()
    except socket.timeout:
        raise MockPeerError("no payload connection before the accept timeout") from None
    conn.settimeout(PAYLOAD_IDLE_TIMEOUT)
    ssock = wrap_tls(conn, ident, server_side=True)
    sent = 0
    try:
        if expected_der is not None and ssock.getpeercert(binary_form=True) != expected_der:
            raise MockPeerError("payload peer certificate is not the paired device's")
        while size < 0 or sent < size:
            data = source.read(chunk if size < 0 else min(chunk, size - sent))
            if not data:
                if size < 0:
                    break
                raise MockPeerError(f"source ended after {sent} of {size} bytes")
            ssock.sendall(data)
            sent += len(data)
        return sent
    except OSError as exc:
        raise MockPeerError(f"payload send failed after {sent} bytes: {exc}") from None
    finally:
        ssock.close()


def receive_payload(host, port, ident, sink, size, *, expected_der=None, chunk=PAYLOAD_CHUNK):
    """Connects to the advertised payload port, runs TLS as client and copies the payload to sink.
    size -1 reads to EOF. A stream that ends early, or a certificate that is not expected_der, is an error."""
    try:
        raw = socket.create_connection((host, port), timeout=PAYLOAD_ACCEPT_TIMEOUT)
    except OSError as exc:
        raise MockPeerError(f"cannot connect to payload port {host}:{port}: {exc}") from None
    raw.settimeout(PAYLOAD_IDLE_TIMEOUT)
    ssock = wrap_tls(raw, ident, server_side=False)
    got = 0
    try:
        if expected_der is not None and ssock.getpeercert(binary_form=True) != expected_der:
            raise MockPeerError("payload certificate is not the paired device's")
        while size < 0 or got < size:
            data = ssock.recv(chunk if size < 0 else min(chunk, size - got))
            if not data:
                if size < 0:
                    break
                raise MockPeerError(f"payload ended after {got} of {size} bytes")
            sink.write(data)
            got += len(data)
        return got
    except OSError as exc:
        raise MockPeerError(f"payload receive failed after {got} bytes: {exc}") from None
    finally:
        ssock.close()


def _paired_cert(store, device_id):
    record = store.get(device_id)
    if record is None:
        return None
    return base64.b64decode(record["certDer"])


def file_sha256(path):
    """sha256 of a file, read in chunks."""
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(PAYLOAD_CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def cmd_send_file(args):
    path = pathlib.Path(args.file).expanduser()
    if not path.is_file():
        raise MockPeerError(f"not a readable file: {args.file}")
    size = path.stat().st_size
    name = args.file_name or path.name
    digest = file_sha256(path)
    body = {
        "filename": name,
        "numberOfFiles": 1,
        "totalPayloadSize": size,
        "lastModified": int(path.stat().st_mtime * 1000),
    }
    ident = _identity(args)
    store = PairStore(_state_dir(args))
    paired = {r["deviceId"] for r in store.all() if not args.peer or r["deviceId"] == args.peer}
    if not paired:
        raise MockPeerError("no paired device matches; run `pair` first", EXIT_REJECTED)
    started = threading.Event()
    finished = threading.Event()
    outcome = {}

    def worker(link):
        try:
            port, listener = bind_payload_listener(args.bind)
            with listener:
                link.send(SHARE_TYPE, body, payload_size=size, payload_port=port)
                log(f"offered {name} ({size} bytes, sha256 {digest}) on payload port {port}")
                with path.open("rb") as handle:
                    outcome["sent"] = serve_payload(listener, ident, handle, size,
                                                    expected_der=_paired_cert(store, link.peer.device_id))
            log(f"sent {outcome['sent']} bytes to {link.peer.name}")
        except MockPeerError as exc:
            outcome["error"] = str(exc)
            log(f"send failed: {exc}")
        finally:
            finished.set()

    def on_link(link):
        if link.peer.device_id not in paired or started.is_set():
            return
        started.set()
        threading.Thread(target=worker, args=(link,), daemon=True).start()

    node = _node(args, ident, on_link=on_link)
    _start(args, node)
    try:
        if not started.wait(args.timeout):
            log(f"no paired link within {args.timeout:g} s")
            return EXIT_TIMEOUT
        if not finished.wait(args.wait or None):
            log("transfer did not finish before --wait ran out")
            return EXIT_TIMEOUT
    finally:
        node.stop()
    return EXIT_ERROR if "error" in outcome else EXIT_OK


def cmd_receive(args):
    out_dir = pathlib.Path(args.out_dir).expanduser()
    out_dir.mkdir(parents=True, exist_ok=True)
    ident = _identity(args)
    store = PairStore(_state_dir(args))
    lock = threading.Lock()
    handled = [0]
    done = threading.Event()

    def finish_one():
        with lock:
            handled[0] += 1
            if args.count and handled[0] >= args.count:
                done.set()

    def receive_file(link, obj, name, size, port):
        def exists(candidate):
            return (out_dir / candidate).exists()

        with lock:
            target_name = unique_name(sanitize_filename(name), exists)
            target = out_dir / target_name
            target.touch()
        try:
            with target.open("r+b") as handle:
                got = receive_payload(link.remote[0], port, ident, handle, size,
                                      expected_der=_paired_cert(store, link.peer.device_id))
            digest = file_sha256(target)
            log(f"received {target} ({got} bytes, sha256 {digest}) from {link.peer.name}")
        except (MockPeerError, OSError) as exc:
            target.unlink(missing_ok=True)
            log(f"receive of {name} failed: {exc}")
        finally:
            finish_one()

    def on_packet(link, obj):
        if obj.get("type") != SHARE_TYPE:
            return
        if store.get(link.peer.device_id) is None:
            log(f"share from unpaired {link.peer.name} ignored")
            return
        body = obj.get("body") or {}
        if isinstance(body.get("url"), str) and body["url"]:
            with lock, (out_dir / "links.txt").open("a", encoding="utf-8") as handle:
                handle.write(body["url"] + "\n")
            log(f"link from {link.peer.name}: {body['url']}")
            finish_one()
        elif isinstance(body.get("text"), str) and body["text"]:
            with lock:
                target = out_dir / unique_name("text.txt", lambda n: (out_dir / n).exists())
                target.write_text(body["text"], encoding="utf-8")
            log(f"text from {link.peer.name} saved to {target}")
            finish_one()
        elif isinstance(body.get("filename"), str) and "payloadSize" in obj:
            port = (obj.get("payloadTransferInfo") or {}).get("port")
            if not isinstance(port, int):
                log("file packet without payloadTransferInfo.port ignored")
                return
            threading.Thread(target=receive_file, args=(link, obj, body["filename"], obj["payloadSize"], port),
                             daemon=True).start()
        else:
            log("share packet with no url, text or file ignored")

    node = _node(args, ident, on_link=lambda link: log(f"linked {link.peer.name}"), on_packet=on_packet)
    _start(args, node)
    try:
        if args.count:
            done.wait(args.duration or None)
        elif args.duration > 0:
            time.sleep(args.duration)
        else:
            threading.Event().wait()
    finally:
        time.sleep(0.5)
        node.stop()
    return EXIT_OK


# ---------------------------------------------------------------- CLI

SEND_EXAMPLES = """examples (the app answers, and replies show up in the listen window):
  ping with a message:      --type kdeconnect.ping --body '{"message":"hi"}'
  battery status:           --type kdeconnect.battery --body '{"currentCharge":80,"isCharging":true,"thresholdEvent":0}'
  low battery alert:        --type kdeconnect.battery --body '{"currentCharge":12,"isCharging":false,"thresholdEvent":1}'
  ask the app for battery:  --type kdeconnect.battery.request --body '{"request":true}'
  clipboard text:           --type kdeconnect.clipboard --body '{"content":"copied on desktop"}'
  clipboard on connect:     --type kdeconnect.clipboard.connect --body '{"content":"copied on desktop","timestamp":1732030000000}'
"""


def _add_node_options(parser, *, announce=True):
    group = parser.add_argument_group("identity and network")
    group.add_argument("--state-dir", default=DEFAULT_STATE_DIR,
                       help="where identity and paired devices live (default: %(default)s)")
    group.add_argument("--device-id", help="id for a new identity (32-38 chars); ignored if the state dir exists")
    group.add_argument("--name", help="device name, filtered like the app does (new identities default to 'Mock Desktop')")
    group.add_argument("--bind", default="0.0.0.0", help="address for UDP and TCP (default: %(default)s)")
    group.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT,
                       help="UDP discovery port (default: %(default)s)")
    group.add_argument("--tcp-port", type=int,
                       help="TCP port to use; default is the first free port in 1716-1764")
    group.add_argument("--connect", action="append", metavar="HOST[:PORT]", default=[],
                       help="also dial this address once (repeatable), e.g. 127.0.0.1:1716 after adb forward")
    if announce:
        group.add_argument("--announce", action="append", metavar="HOST[:PORT]", default=[],
                           help="UDP identity targets (repeatable; default 255.255.255.255:1716)")
        group.add_argument("--announce-every", type=float, default=10.0, metavar="SEC",
                           help="re-announce interval, 0 for once (default: %(default)s)")
        group.add_argument("--no-announce", action="store_true", help="do not send UDP identity")


def build_parser():
    parser = argparse.ArgumentParser(
        prog="mockpeer.py",
        description="Fake KDE Connect v8 desktop for testing the Tandem app.",
        epilog="Exit codes: 0 ok, 1 error, 2 usage, 3 timeout, 4 rejected or not paired, 130 interrupted.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True, metavar="SUBCOMMAND")

    vk = sub.add_parser("verify-key", help="print the 8-character pairing code",
                        description="Prints the pairing code for two SubjectPublicKeyInfo DER files.")
    vk.add_argument("our_spki", help="our public key, SPKI DER")
    vk.add_argument("peer_spki", help="peer public key, SPKI DER")
    vk.add_argument("timestamp", type=int, help="pairing timestamp in seconds")
    vk.set_defaults(handler=cmd_verify_key)

    an = sub.add_parser("announce", help="send one UDP identity packet",
                        description="Sends one UDP identity packet and exits.")
    an.add_argument("device_id")
    an.add_argument("device_name")
    an.add_argument("tcp_port", type=int)
    an.add_argument("--to", default="255.255.255.255", metavar="HOST", help="target (default: broadcast)")
    an.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    an.set_defaults(handler=cmd_announce)

    ls = sub.add_parser("listen", help="answer discovery, accept TLS links, log every packet",
                        description="Answers the app's UDP identity by dialing its TCP port, accepts "
                                    "inbound TCP, runs TLS with the right role, and logs packets as JSON. "
                                    "Runs until Ctrl-C unless --duration is set.")
    _add_node_options(ls)
    ls.add_argument("--duration", type=float, default=0.0, metavar="SEC", help="stop after SEC (0 = run forever)")
    ls.set_defaults(handler=cmd_listen)

    pr = sub.add_parser("pair", help="pair with the app (request or answer)",
                        description="Pairs with a linked app. With --request, sends the request to the first "
                                    "linked peer. Otherwise waits for a request and answers it per --on-request. "
                                    "The 8-character code is printed on both sides and must match.")
    _add_node_options(pr)
    pr.add_argument("--request", action="store_true", help="send the pairing request (default: wait for one)")
    pr.add_argument("--on-request", choices=["ask", "accept", "reject"], default="ask",
                    help="answer to an incoming request (default: ask on stdin)")
    pr.add_argument("--peer", metavar="DEVICE_ID", help="only pair with this device id")
    pr.add_argument("--timeout", type=float, default=30.0, metavar="SEC",
                    help="wait for the peer's answer to our request, and the prompt window (default: %(default)s)")
    pr.add_argument("--wait", type=float, default=0.0, metavar="SEC",
                    help="give up after SEC without an outcome (0 = wait forever)")
    pr.set_defaults(handler=cmd_pair)

    sd = sub.add_parser("send", help="send one packet over a paired link",
                        description="Waits for a paired peer to link, sends one packet, then listens for replies.",
                        epilog=SEND_EXAMPLES, formatter_class=argparse.RawDescriptionHelpFormatter)
    _add_node_options(sd)
    sd.add_argument("--peer", metavar="DEVICE_ID", help="paired device id (default: any paired device)")
    sd.add_argument("--type", default="kdeconnect.ping", help="packet type (default: %(default)s)")
    sd.add_argument("--body", default="{}", help="JSON object for the body (default: %(default)s)")
    sd.add_argument("--timeout", type=float, default=30.0, metavar="SEC",
                    help="wait for a paired link (default: %(default)s)")
    sd.add_argument("--wait", type=float, default=3.0, metavar="SEC",
                    help="listen for replies after sending (default: %(default)s)")
    sd.set_defaults(handler=cmd_send)

    sf = sub.add_parser("send-file", help="send a file to a paired app over a TLS payload socket",
                        description="Waits for a paired link, advertises a payload port in a kdeconnect.share.request, "
                                    "and serves the file over TLS as the TLS server (plan 2.5). Prints the sha256.")
    _add_node_options(sf)
    sf.add_argument("file", help="file to send")
    sf.add_argument("--file-name", dest="file_name", help="filename to announce (default: the file's name)")
    sf.add_argument("--peer", metavar="DEVICE_ID", help="paired device id (default: any paired device)")
    sf.add_argument("--timeout", type=float, default=30.0, metavar="SEC",
                    help="wait for a paired link (default: %(default)s)")
    sf.add_argument("--wait", type=float, default=120.0, metavar="SEC",
                    help="wait for the transfer to finish (0 = forever; default: %(default)s)")
    sf.set_defaults(handler=cmd_send_file)

    rv = sub.add_parser("receive", help="receive shares from a paired app and save them",
                        description="Accepts links and texts as JSON, and downloads files over TLS from the payload "
                                    "port the app announces. Files are saved under --out-dir with sanitized, unique "
                                    "names. Every file is pinned to the paired certificate.")
    _add_node_options(rv)
    rv.add_argument("--out-dir", default=f"{DEFAULT_STATE_DIR}/received",
                    help="where to save files, texts and links (default: %(default)s)")
    rv.add_argument("--count", type=int, default=0,
                    help="exit after this many shares (0 = run until --duration or Ctrl-C)")
    rv.add_argument("--duration", type=float, default=0.0, metavar="SEC",
                    help="stop after SEC (0 = run forever); with --count, the wait for the shares")
    rv.set_defaults(handler=cmd_receive)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.handler(args)
    except MockPeerError as exc:
        print(f"mockpeer: error: {exc}", file=sys.stderr)
        return exc.code
    except KeyboardInterrupt:
        return EXIT_INTERRUPTED


if __name__ == "__main__":
    sys.exit(main())
