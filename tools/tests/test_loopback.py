# SPDX-License-Identifier: GPL-3.0-or-later
"""In-process loopback test: two mockpeer nodes on 127.0.0.1 run the TLS handshake, pair, and exchange a ping.

Run: python3 -m unittest discover -s tools/tests
Needs the `cryptography` package; skipped without it.
"""

import pathlib
import socket
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import mockpeer  # noqa: E402


class LoopbackPairingTest(unittest.TestCase):
    def setUp(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("cryptography not installed")
        self._tmp = tempfile.TemporaryDirectory()
        base = pathlib.Path(self._tmp.name)
        self.ident_a = mockpeer.load_or_create_identity(base / "a", "a" * 32, "Desk A")
        self.ident_b = mockpeer.load_or_create_identity(base / "b", "b" * 32, "Desk B")
        self.store_a = mockpeer.PairStore(base / "a")
        self.store_b = mockpeer.PairStore(base / "b")
        self.pings = []
        self.pinged = threading.Event()

        def on_packet_b(link, obj):
            if obj["type"] == "kdeconnect.ping":
                self.pings.append(obj)
                self.pinged.set()

        self.flow_a = mockpeer.PairFlow(self.ident_a, self.store_a, policy="accept", timeout=10,
                                        want_request=True, only_peer=None)
        self.flow_b = mockpeer.PairFlow(self.ident_b, self.store_b, policy="accept", timeout=10,
                                        want_request=False, only_peer=None)
        self.pings_a = []

        def on_packet_a(link, obj):
            self.flow_a.on_packet(link, obj)
            if obj["type"] == "kdeconnect.ping":
                self.pings_a.append(obj)

        self.node_a = mockpeer.Node(self.ident_a, bind="127.0.0.1", discovery_port=0, tcp_port=0,
                                    on_link=self.flow_a.on_link, on_packet=on_packet_a)
        self.node_b = mockpeer.Node(self.ident_b, bind="127.0.0.1", discovery_port=0, tcp_port=0,
                                    on_link=self.flow_b.on_link, on_packet=lambda l, o: (
                                        self.flow_b.on_packet(l, o), on_packet_b(l, o)))

    def tearDown(self):
        self.node_a.stop()
        self.node_b.stop()
        self._tmp.cleanup()

    def test_pair_then_ping_both_ways(self):
        self.node_a.start()
        self.node_b.start()
        self.node_a.dial("127.0.0.1", self.node_b.tcp_port)  # A is TCP client: TLS server role
        self.assertTrue(self.flow_a.done.wait(10), "requester never finished")
        self.assertTrue(self.flow_b.done.wait(10), "answerer never finished")
        self.assertEqual(self.flow_a.result, "paired")
        self.assertEqual(self.flow_b.result, "paired")
        self.assertEqual(self.flow_a.codes, self.flow_b.codes, "both sides must show the same code")
        self.assertIsNotNone(self.store_a.get("b" * 32))
        self.assertIsNotNone(self.store_b.get("a" * 32))

        # B (accepted TCP, TLS client role) pings A over the same link.
        deadline = time.monotonic() + 5
        while "a" * 32 not in self.node_b.links and time.monotonic() < deadline:
            time.sleep(0.05)
        self.node_b.links["a" * 32].send("kdeconnect.ping", {"message": "hi"})
        deadline = time.monotonic() + 5
        while not self.pings_a and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertEqual(len(self.pings_a), 1)
        self.assertEqual(self.pings_a[0]["body"], {"message": "hi"})

    def test_inbound_connection_gets_identity_and_tls(self):
        self.node_a.start()
        self.node_b.start()
        self.node_a.dial("127.0.0.1", self.node_b.tcp_port)
        self.flow_a.done.wait(10)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and ("b" * 32 not in self.node_a.links):
            time.sleep(0.05)
        link_a = self.node_a.links.get("b" * 32)
        link_b = self.node_b.links.get("a" * 32)
        self.assertIsNotNone(link_a)
        self.assertIsNotNone(link_b)
        self.assertTrue(link_a.outbound)
        self.assertFalse(link_b.outbound)
        self.assertEqual(link_a.tls_version, "TLSv1.2")
        self.assertEqual(link_b.cert_der, self.ident_a.cert_der)


class AppWireOrderTest(unittest.TestCase):
    """Raw sockets play the app or the stock desktop, so the wire order follows PLAN 2.2 and not
    the mock's own behaviour. Mock-to-mock tests cannot catch a wrong order: both sides agree."""

    def setUp(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("cryptography not installed")
        self._tmp = tempfile.TemporaryDirectory()
        base = pathlib.Path(self._tmp.name)
        self.ident_a = mockpeer.load_or_create_identity(base / "a", "a" * 32, "Desk A")
        self.ident_b = mockpeer.load_or_create_identity(base / "b", "b" * 32, "Desk B")
        self.flow = mockpeer.PairFlow(self.ident_b, mockpeer.PairStore(base / "b"), policy="accept",
                                      timeout=10, want_request=False, only_peer=None)
        self.node_b = mockpeer.Node(self.ident_b, bind="127.0.0.1", discovery_port=0, tcp_port=0,
                                    on_link=self.flow.on_link, on_packet=self.flow.on_packet)
        self.node_b.start()

    def tearDown(self):
        self.node_b.stop()
        self._tmp.cleanup()

    def _plain_from_a(self):
        body = mockpeer.identity_body("Desk A", "a" * 32, target_id="b" * 32)
        return mockpeer.encode_packet(mockpeer.build_packet("kdeconnect.identity", body))

    def test_acceptor_sends_nothing_before_tls(self):
        # Like the app dialing us (flow B): we are the acceptor, so the first byte we send is a TLS record.
        sock = socket.create_connection(("127.0.0.1", self.node_b.tcp_port), timeout=5)
        try:
            sock.sendall(self._plain_from_a())
            first = sock.recv(1)
        finally:
            sock.close()
        self.assertEqual(first, b"\x16", "acceptor must start TLS without a plaintext line first")

    def test_dialer_sends_plaintext_first_then_tls_server(self):
        # Like the app accepting us (flow A): we dial, so our first line is the plaintext identity.
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        server.settimeout(5)
        node_a = mockpeer.Node(self.ident_a, bind="127.0.0.1", discovery_port=0, tcp_port=0)
        node_a.start()
        try:
            node_a.dial("127.0.0.1", server.getsockname()[1])
            conn, _ = server.accept()
            conn.settimeout(5)
            line = mockpeer.read_line(conn, mockpeer.MAX_IDENTITY_LINE)
            obj = mockpeer.decode_packet(line)
            self.assertEqual(obj["type"], "kdeconnect.identity")
            self.assertEqual(obj["body"]["deviceId"], "a" * 32)
            conn.close()
        finally:
            node_a.stop()
            server.close()

    def test_app_style_dialer_completes_pairing_handshake(self):
        # Raw app-style dialer: plaintext identity, TLS server, then secure identity both ways.
        sock = socket.create_connection(("127.0.0.1", self.node_b.tcp_port), timeout=5)
        try:
            sock.sendall(self._plain_from_a())
            tls = mockpeer.wrap_tls(sock, self.ident_a, server_side=True)
            tls.sendall(mockpeer.encode_packet(mockpeer.build_packet(
                "kdeconnect.identity", mockpeer.identity_body("Desk A", "a" * 32))))
            secure = mockpeer.decode_packet(mockpeer.read_line(tls, mockpeer.MAX_IDENTITY_LINE))
            self.assertEqual(secure["body"]["deviceId"], "b" * 32)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and "a" * 32 not in self.node_b.links:
                time.sleep(0.05)
            self.assertIn("a" * 32, self.node_b.links)
            tls.close()
        finally:
            sock.close()


if __name__ == "__main__":
    unittest.main()
