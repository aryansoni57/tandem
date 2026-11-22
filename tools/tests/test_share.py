# SPDX-License-Identifier: GPL-3.0-or-later
"""Share support in mockpeer: filename sanitization, unique names, and payloads over TLS on loopback.
Run: python3 -m unittest discover -s tools/tests
Needs the `cryptography` package; the TLS tests are skipped without it.
"""
import io
import pathlib
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import mockpeer  # noqa: E402

TMP_DIR = pathlib.Path(__file__).resolve().parents[2] / "tmp"
TMP_DIR.mkdir(exist_ok=True)


class SanitizeFilenameTest(unittest.TestCase):
    def test_plain_name_is_kept(self):
        self.assertEqual(mockpeer.sanitize_filename("photo 1.jpg"), "photo 1.jpg")

    def test_traversal_keeps_only_the_leaf(self):
        self.assertEqual(mockpeer.sanitize_filename("../../etc/passwd"), "passwd")
        self.assertEqual(mockpeer.sanitize_filename("..\\..\\evil.sh"), "evil.sh")

    def test_absolute_paths_keep_only_the_leaf(self):
        self.assertEqual(mockpeer.sanitize_filename("/data/app/notes.txt"), "notes.txt")
        self.assertEqual(mockpeer.sanitize_filename("C:\\Users\\me\\win.txt"), "win.txt")

    def test_dot_and_empty_names_fall_back(self):
        for raw in ("..", ".", "", "/", None, 7):
            self.assertEqual(mockpeer.sanitize_filename(raw), "shared-file", raw)

    def test_leading_and_trailing_dots_are_removed(self):
        self.assertEqual(mockpeer.sanitize_filename(".bashrc"), "bashrc")
        self.assertEqual(mockpeer.sanitize_filename("name. "), "name")

    def test_odd_characters_become_underscores(self):
        self.assertEqual(mockpeer.sanitize_filename('a<b>c:d"e|f?g*.txt'), "a_b_c_d_e_f_g_.txt")
        self.assertEqual(mockpeer.sanitize_filename("tab\there\x00nul.txt"), "tab_here_nul.txt")

    def test_unicode_is_kept(self):
        self.assertEqual(mockpeer.sanitize_filename("résumé 日本.pdf"), "résumé 日本.pdf")

    def test_long_names_keep_the_extension_within_the_byte_limit(self):
        result = mockpeer.sanitize_filename("x" * 400 + ".mp4")
        self.assertLessEqual(len(result.encode("utf-8")), mockpeer.NAME_MAX_BYTES)
        self.assertTrue(result.endswith(".mp4"))

    def test_long_multibyte_names_stay_valid(self):
        result = mockpeer.sanitize_filename("日" * 200 + ".txt")
        self.assertLessEqual(len(result.encode("utf-8")), mockpeer.NAME_MAX_BYTES)
        result.encode("utf-8")
        self.assertTrue(result.endswith(".txt"))


class UniqueNameTest(unittest.TestCase):
    def test_free_name_is_unchanged(self):
        self.assertEqual(mockpeer.unique_name("a.jpg", lambda n: False), "a.jpg")

    def test_duplicates_are_numbered_before_the_extension(self):
        taken = {"a.jpg", "a (1).jpg"}
        self.assertEqual(mockpeer.unique_name("a.jpg", taken.__contains__), "a (2).jpg")

    def test_names_without_extension_are_numbered(self):
        self.assertEqual(mockpeer.unique_name("README", lambda n: n == "README"), "README (1)")

    def test_real_files_are_checked(self):
        with tempfile.TemporaryDirectory(dir=TMP_DIR) as tmp:
            folder = pathlib.Path(tmp)
            (folder / "x.bin").write_bytes(b"1")
            name = mockpeer.unique_name("x.bin", lambda n: (folder / n).exists())
            self.assertEqual(name, "x (1).bin")


class PayloadOverTlsTest(unittest.TestCase):
    """Sender is the TLS server (plan 2.5); receiver is the TLS client, pinned to the sender's cert."""

    def setUp(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("cryptography not installed")
        self._tmp = tempfile.TemporaryDirectory(dir=TMP_DIR)
        base = pathlib.Path(self._tmp.name)
        self.sender = mockpeer.load_or_create_identity(base / "s", "s" * 32, "Sender")
        self.receiver = mockpeer.load_or_create_identity(base / "r", "r" * 32, "Receiver")
        self.stranger = mockpeer.load_or_create_identity(base / "x", "x" * 32, "Stranger")

    def tearDown(self):
        self._tmp.cleanup()

    def _serve_in_thread(self, source, size, **kwargs):
        port, listener = mockpeer.bind_payload_listener("127.0.0.1", range(21000, 21100))
        result = {}

        def run():
            try:
                with listener:
                    result["sent"] = mockpeer.serve_payload(listener, self.sender, source, size, **kwargs)
            except mockpeer.MockPeerError as exc:
                result["error"] = exc

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        return port, thread, result

    def test_large_payload_streams_identical_bytes(self):
        data = bytes(range(256)) * (3 * 4096 + 7)  # about 3 MB, not a multiple of the chunk size
        port, thread, result = self._serve_in_thread(io.BytesIO(data), len(data),
                                                     expected_der=self.receiver.cert_der)
        sink = io.BytesIO()
        got = mockpeer.receive_payload("127.0.0.1", port, self.receiver, sink, len(data),
                                       expected_der=self.sender.cert_der)
        thread.join(10)
        self.assertEqual(got, len(data))
        self.assertEqual(sink.getvalue(), data)
        self.assertEqual(result.get("sent"), len(data))

    def test_zero_byte_payload(self):
        port, thread, result = self._serve_in_thread(io.BytesIO(b""), 0)
        sink = io.BytesIO()
        self.assertEqual(mockpeer.receive_payload("127.0.0.1", port, self.receiver, sink, 0), 0)
        thread.join(10)
        self.assertEqual(result.get("sent"), 0)

    def test_unknown_size_reads_until_eof(self):
        data = b"unknown length" * 1000
        port, thread, _ = self._serve_in_thread(io.BytesIO(data), -1)
        sink = io.BytesIO()
        self.assertEqual(mockpeer.receive_payload("127.0.0.1", port, self.receiver, sink, -1), len(data))
        thread.join(10)
        self.assertEqual(sink.getvalue(), data)

    def test_short_source_is_an_error_on_both_sides(self):
        port, thread, result = self._serve_in_thread(io.BytesIO(b"short"), 100)
        with self.assertRaises(mockpeer.MockPeerError):
            mockpeer.receive_payload("127.0.0.1", port, self.receiver, io.BytesIO(), 100)
        thread.join(10)
        self.assertIn("error", result)

    def test_receiver_rejects_a_sender_with_another_certificate(self):
        port, thread, _ = self._serve_in_thread(io.BytesIO(b"data"), 4)
        with self.assertRaises(mockpeer.MockPeerError):
            mockpeer.receive_payload("127.0.0.1", port, self.receiver, io.BytesIO(), 4,
                                     expected_der=self.stranger.cert_der)
        thread.join(10)

    def test_sender_rejects_a_receiver_that_is_not_the_paired_one(self):
        port, thread, result = self._serve_in_thread(io.BytesIO(b"data"), 4,
                                                     expected_der=self.stranger.cert_der)
        with self.assertRaises(mockpeer.MockPeerError):
            mockpeer.receive_payload("127.0.0.1", port, self.receiver, io.BytesIO(), 4)
        thread.join(10)
        self.assertIn("error", result)

    def test_payload_port_is_taken_from_the_range(self):
        port, listener = mockpeer.bind_payload_listener("127.0.0.1", range(21200, 21300))
        try:
            self.assertTrue(21200 <= port < 21300)
        finally:
            listener.close()


if __name__ == "__main__":
    unittest.main()
