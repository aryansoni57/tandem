# SPDX-License-Identifier: GPL-3.0-or-later
"""Unit tests for the pure parts of tools/mockpeer.py. Run: python3 -m unittest discover -s tools/tests"""

import hashlib
import json
import pathlib
import socket
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import mockpeer  # noqa: E402

DEVICE_ID = "0123456789abcdef0123456789abcdef"


class FramingTest(unittest.TestCase):
    def test_encode_is_one_newline_terminated_json_object(self):
        data = mockpeer.encode_packet(mockpeer.build_packet("kdeconnect.ping", {}, packet_id=7))
        self.assertTrue(data.endswith(b"\n"))
        self.assertEqual(data.count(b"\n"), 1)
        self.assertEqual(json.loads(data), {"id": 7, "type": "kdeconnect.ping", "body": {}})

    def test_payload_size_omitted_without_payload(self):
        pkt = mockpeer.build_packet("kdeconnect.ping", {})
        self.assertNotIn("payloadSize", pkt)
        self.assertNotIn("payloadTransferInfo", pkt)

    def test_payload_size_needs_port(self):
        with self.assertRaises(mockpeer.ProtocolError):
            mockpeer.build_packet("kdeconnect.share.request", {}, payload_size=4)
        pkt = mockpeer.build_packet("kdeconnect.share.request", {}, payload_size=4, payload_port=1739)
        self.assertEqual(pkt["payloadTransferInfo"], {"port": 1739})

    def test_round_trip(self):
        pkt = mockpeer.build_packet("kdeconnect.ping", {"message": "héllo"}, packet_id=1)
        self.assertEqual(mockpeer.decode_packet(mockpeer.encode_packet(pkt).strip()), pkt)

    def test_decode_accepts_numeric_string_id_and_unknown_keys(self):
        obj = mockpeer.decode_packet(b'{"id":"12","type":"x","body":{},"extra":true}')
        self.assertEqual(obj["id"], "12")

    def test_decode_rejects_bad_packets(self):
        bad = [
            b"not json",
            b"[]",
            b'{"body":{}}',
            b'{"type":"","body":{}}',
            b'{"type":"x"}',
            b'{"type":"x","body":[]}',
            b'{"type":"x","body":{},"payloadSize":5}',
            b'{"type":"x","body":{},"payloadSize":5,"payloadTransferInfo":{}}',
            b'{"type":"x","body":{},"payloadSize":-2,"payloadTransferInfo":{"port":1}}',
            b"\xff\xfe",
        ]
        for raw in bad:
            with self.subTest(raw=raw), self.assertRaises(mockpeer.ProtocolError):
                mockpeer.decode_packet(raw)

    def test_splitter_handles_partial_and_multiple_lines(self):
        splitter = mockpeer.LineSplitter(limit=100)
        self.assertEqual(splitter.feed(b'{"a":1}\n{"b'), [b'{"a":1}'])
        self.assertEqual(splitter.feed(b'":2}\n\n'), [b'{"b":2}'])

    def test_splitter_drops_overlong_line_and_keeps_connection(self):
        splitter = mockpeer.LineSplitter(limit=10)
        self.assertEqual(splitter.feed(b"x" * 8), [])
        self.assertEqual(splitter.feed(b"x" * 20 + b"\n" + b"ok\n"), [b"ok"])
        self.assertEqual(splitter.dropped, 1)

    def test_read_line_does_not_consume_past_newline(self):
        left, right = socket.socketpair()
        try:
            left.sendall(b'{"type":"x"}\n\x16\x03\x01')
            self.assertEqual(mockpeer.read_line(right, 1024), b'{"type":"x"}')
            self.assertEqual(right.recv(3), b"\x16\x03\x01")
        finally:
            left.close()
            right.close()

    def test_read_line_enforces_limit_and_eof(self):
        left, right = socket.socketpair()
        try:
            left.sendall(b"a" * 50 + b"\n")
            with self.assertRaises(mockpeer.ProtocolError):
                mockpeer.read_line(right, 10)
        finally:
            left.close()
            right.close()
        left, right = socket.socketpair()
        left.close()
        with self.assertRaises(ConnectionError):
            mockpeer.read_line(right, 10)
        right.close()


class VerificationCodeTest(unittest.TestCase):
    def test_unsigned_compare_treats_high_bytes_as_large(self):
        self.assertGreater(mockpeer.unsigned_compare(b"\x80", b"\x01"), 0)
        self.assertLess(mockpeer.unsigned_compare(b"\x01", b"\x01\x00"), 0)
        self.assertEqual(mockpeer.unsigned_compare(b"ab", b"ab"), 0)

    def test_code_matches_rule_from_plan(self):
        ours, peer, ts = b"\x30\x59" + b"A" * 20, b"\x30\x59" + b"B" * 20, 1732030000
        # Larger SPKI first: peer (B) > ours (A), so B comes first.
        digest = hashlib.sha256(peer + ours + b"1732030000").hexdigest()
        self.assertEqual(mockpeer.verification_key(ours, peer, ts), digest[:8].upper())

    def test_code_is_symmetric_and_timestamp_sensitive(self):
        ours, peer = b"\x01" * 30, b"\x02" * 30
        code = mockpeer.verification_key(ours, peer, 100)
        self.assertEqual(code, mockpeer.verification_key(peer, ours, 100))
        self.assertNotEqual(code, mockpeer.verification_key(ours, peer, 101))
        self.assertRegex(code, r"^[0-9A-F]{8}$")


class IdentityParsingTest(unittest.TestCase):
    def test_minimal_udp_discovery_packet_is_accepted(self):
        obj = mockpeer.decode_packet(
            json.dumps({"type": "kdeconnect.identity", "body": {"deviceId": DEVICE_ID, "tcpPort": 1716}})
        )
        peer = mockpeer.parse_identity(obj)
        self.assertEqual(peer.device_id, DEVICE_ID)
        self.assertEqual(peer.tcp_port, 1716)
        self.assertEqual(peer.name, "unnamed")

    def test_tcp_port_and_protocol_version_as_strings(self):
        body = {"deviceId": DEVICE_ID, "tcpPort": "1717", "protocolVersion": "8"}
        peer = mockpeer.parse_identity({"type": "kdeconnect.identity", "body": body})
        self.assertEqual(peer.tcp_port, 1717)
        self.assertEqual(peer.protocol_version, 8)

    def test_invalid_device_ids_rejected(self):
        for value in ["short", "x" * 40, "has space" + "a" * 30, None, 12]:
            with self.subTest(value=value), self.assertRaises(mockpeer.ProtocolError):
                mockpeer.parse_identity({"type": "kdeconnect.identity", "body": {"deviceId": value}})

    def test_wrong_packet_type_rejected(self):
        with self.assertRaises(mockpeer.ProtocolError):
            mockpeer.parse_identity({"type": "kdeconnect.ping", "body": {"deviceId": DEVICE_ID}})

    def test_name_filter(self):
        self.assertEqual(mockpeer.filter_name('Pixel "9" (Pro), v2.0!'), "Pixel 9 Pro v20")
        self.assertEqual(len(mockpeer.filter_name("a" * 50)), 32)

    def test_target_mismatch_rejected(self):
        with self.assertRaises(mockpeer.ProtocolError):
            mockpeer.check_connection_targets({"targetDeviceId": "other"}, DEVICE_ID)
        with self.assertRaises(mockpeer.ProtocolError):
            mockpeer.check_connection_targets({"targetProtocolVersion": 7}, DEVICE_ID)
        mockpeer.check_connection_targets({"targetDeviceId": DEVICE_ID, "targetProtocolVersion": "8"}, DEVICE_ID)
        mockpeer.check_connection_targets({}, DEVICE_ID)

    def test_capability_lists(self):
        body = {"deviceId": DEVICE_ID, "incomingCapabilities": ["a", 3], "outgoingCapabilities": "nope"}
        peer = mockpeer.parse_identity({"type": "kdeconnect.identity", "body": body})
        self.assertEqual(peer.incoming, ["a"])
        self.assertEqual(peer.outgoing, [])

    def test_body_helpers(self):
        self.assertEqual(mockpeer.body_int({"n": "42"}, "n"), 42)
        self.assertIsNone(mockpeer.body_int({"n": True}, "n"))
        self.assertTrue(mockpeer.body_bool({"b": "true"}, "b"))
        self.assertIsNone(mockpeer.body_bool({"b": 1}, "b"))


class IdentityFilesTest(unittest.TestCase):
    def setUp(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("cryptography not installed")
        self._tmp = tempfile.TemporaryDirectory()
        self.state = pathlib.Path(self._tmp.name) / "state"

    def tearDown(self):
        self._tmp.cleanup()

    def test_created_once_and_reloaded(self):
        first = mockpeer.load_or_create_identity(self.state, DEVICE_ID, "Test Desk")
        again = mockpeer.load_or_create_identity(self.state)
        self.assertEqual(first.device_id, DEVICE_ID)
        self.assertEqual(again.cert_der, first.cert_der)
        self.assertEqual(mockpeer.cert_common_name(first.cert_der), DEVICE_ID)
        self.assertEqual(again.spki_der, mockpeer.spki_of_cert_der(first.cert_der))

    def test_key_file_is_private(self):
        mockpeer.load_or_create_identity(self.state, DEVICE_ID)
        self.assertEqual((self.state / "key.pem").stat().st_mode & 0o777, 0o600)

    def test_other_device_id_refused(self):
        mockpeer.load_or_create_identity(self.state, DEVICE_ID)
        with self.assertRaises(mockpeer.MockPeerError):
            mockpeer.load_or_create_identity(self.state, "f" * 32)

    def test_pair_store_round_trip(self):
        store = mockpeer.PairStore(self.state)
        self.state.mkdir(parents=True, exist_ok=True)
        peer = mockpeer.PeerIdentity(device_id=DEVICE_ID, name="Phone", protocol_version=8)
        store.add(peer, b"der")
        self.assertEqual(store.get(DEVICE_ID)["name"], "Phone")
        store.remove(DEVICE_ID)
        self.assertIsNone(store.get(DEVICE_ID))


if __name__ == "__main__":
    unittest.main()
