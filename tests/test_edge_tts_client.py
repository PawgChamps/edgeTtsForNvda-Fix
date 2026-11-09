# -*- coding: utf-8 -*-
"""Tests for EdgeTtsStreamer connection recovery, timeouts and clock skew."""
import email.utils
import importlib
import os
import struct
import sys
import threading
import time
import types
import unittest
from unittest import mock

_PKG_DIR = os.path.join(os.path.dirname(__file__), "..", "synthDrivers", "edgeTts")

# Load the client as part of a stub package so the NVDA-only __init__.py is skipped.
if "edgeTts" not in sys.modules:
    _pkg = types.ModuleType("edgeTts")
    _pkg.__path__ = [_PKG_DIR]
    sys.modules["edgeTts"] = _pkg
client = importlib.import_module("edgeTts.edge_tts_client")


def _audio_frame(payload: bytes = b"MP3DATA") -> bytes:
    header = b"Path:audio\r\n"
    return struct.pack(">H", len(header)) + header + payload


class FakeWs:
    """Replays a script of server events; runs silent once the script ends."""

    def __init__(self, websocket, events):
        self._websocket = websocket
        self._events = list(events)
        self.sent = []
        self.closed = False

    def send(self, msg):
        self.sent.append(msg)

    def settimeout(self, _):
        pass

    def ping(self):
        if self.closed:
            raise self._websocket.WebSocketConnectionClosedException("closed")

    def close(self):
        self.closed = True

    def recv_data(self):
        ws = self._websocket
        if not self._events:
            time.sleep(0.01)
            raise ws.WebSocketTimeoutException("timed out")
        kind, value = self._events.pop(0)
        if kind == "binary":
            return ws.ABNF.OPCODE_BINARY, value
        if kind == "text":
            return ws.ABNF.OPCODE_TEXT, value
        raise ws.WebSocketConnectionClosedException("Connection to remote host was lost.")


GOOD = [("binary", _audio_frame()), ("text", "X-RequestId:1\r\nPath:turn.end\r\n\r\n{}")]
DEAD = [("closed", None)]


class StreamerTestCase(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(client, "FfmpegPool")
        patcher.start()
        self.addCleanup(patcher.stop)
        self.streamer = client.EdgeTtsStreamer()
        self.websocket = self.streamer._get_ws_module()
        for name, value in {
            "decode_mp3_to_pcm": lambda data: data,
            "_trim_leading_silence": lambda pcm: pcm,
        }.items():
            p = mock.patch.object(client, name, value)
            p.start()
            self.addCleanup(p.stop)

    def connections(self, *scripts):
        socks = [FakeWs(self.websocket, s) for s in scripts]
        p = mock.patch.object(self.streamer, "_connect", side_effect=socks)
        connect = p.start()
        self.addCleanup(p.stop)
        return connect, socks

    def speak(self, cancel_event=None):
        return self.streamer.speak("Hello", "en-US-AriaNeural", cancel_event=cancel_event)


class TestRecovery(StreamerTestCase):
    def test_success_reuses_connection(self):
        connect, _ = self.connections(GOOD + GOOD)
        self.assertEqual(self.speak(), b"MP3DATA")
        self.assertEqual(self.speak(), b"MP3DATA")
        self.assertEqual(connect.call_count, 1)

    def test_dead_reused_socket_retries_on_fresh_connection(self):
        connect, socks = self.connections(DEAD, GOOD)
        self.assertEqual(self.speak(), b"MP3DATA")
        self.assertEqual(connect.call_count, 2)
        self.assertTrue(socks[0].closed)

    def test_no_retry_once_audio_has_started(self):
        connect, _ = self.connections([("binary", _audio_frame())] + DEAD, GOOD)
        with self.assertRaisesRegex(client.EdgeTtsError, "closed"):
            self.speak()
        self.assertEqual(connect.call_count, 1)

    def test_connect_failure_retries_once(self):
        good = FakeWs(self.websocket, GOOD)
        with mock.patch.object(
            self.streamer, "_connect", side_effect=[OSError("network down"), good]
        ) as connect:
            self.assertEqual(self.speak(), b"MP3DATA")
        self.assertEqual(connect.call_count, 2)

    def test_connect_failure_twice_raises(self):
        with mock.patch.object(
            self.streamer, "_connect", side_effect=[OSError("a"), OSError("b")]
        ):
            with self.assertRaisesRegex(client.EdgeTtsError, "Could not connect"):
                self.speak()


class TestTimeouts(StreamerTestCase):
    def setUp(self):
        super().setUp()
        for name in ("_FIRST_AUDIO_TIMEOUT", "_STREAM_STALL_TIMEOUT"):
            p = mock.patch.object(client, name, 0.2)
            p.start()
            self.addCleanup(p.stop)

    def test_silent_socket_times_out_and_retries(self):
        connect, _ = self.connections([], GOOD)
        self.assertEqual(self.speak(), b"MP3DATA")
        self.assertEqual(connect.call_count, 2)

    def test_two_silent_sockets_raise_instead_of_hanging(self):
        self.connections([], [])
        start = time.monotonic()
        with self.assertRaisesRegex(client.EdgeTtsError, "No audio received"):
            self.speak()
        self.assertLess(time.monotonic() - start, 3)

    def test_stall_mid_stream_raises_without_retry(self):
        connect, _ = self.connections([("binary", _audio_frame())], GOOD)
        with self.assertRaisesRegex(client.EdgeTtsError, "stalled"):
            self.speak()
        self.assertEqual(connect.call_count, 1)

    def test_slow_playback_backpressure_is_not_a_stall(self):
        class SlowDecoder:
            fed = []

            def __init__(self, pool, on_audio=None):
                pass

            def feed(self, data):
                time.sleep(0.3)  # longer than the 0.2s stall timeout
                self.fed.append(data)

            def finish(self):
                pass

            def close(self):
                pass

        frames = [("binary", _audio_frame(b"A%d" % i)) for i in range(3)]
        connect, _ = self.connections(frames + GOOD[1:])
        with mock.patch.object(client, "StreamingMp3Decoder", SlowDecoder):
            self.streamer.speak("Long passage", "en-US-AriaNeural", on_audio=lambda pcm: None)
        self.assertEqual(SlowDecoder.fed, [b"A0", b"A1", b"A2"])
        self.assertEqual(connect.call_count, 1)

    def test_control_characters_are_replaced(self):
        ssml = client._build_ssml("a\x0bb\x0cc\x00d\te\nf & g", "voice")
        self.assertIn("a b c d\te\nf &amp; g", ssml)

    def test_cancel_while_waiting(self):
        self.connections([])
        cancel = threading.Event()
        threading.Timer(0.05, cancel.set).start()
        with self.assertRaises(client.EdgeTtsCancelled):
            self.speak(cancel_event=cancel)


class TestClockSkew(StreamerTestCase):
    def _bad_status(self, headers):
        return self.websocket.WebSocketBadStatusException(
            "Handshake status 403 Forbidden", 403, resp_headers=headers
        )

    def test_403_adjusts_clock_skew_and_reconnects(self):
        server_date = email.utils.formatdate(time.time() + 3600, usegmt=True)
        ok = FakeWs(self.websocket, [])
        with mock.patch.object(
            self.websocket,
            "create_connection",
            side_effect=[self._bad_status({"date": server_date}), ok],
        ) as create:
            self.assertIs(self.streamer._connect(), ok)
        self.assertEqual(create.call_count, 2)
        self.assertAlmostEqual(self.streamer._clock_skew, 3600, delta=5)
        expected = client.generate_sec_ms_gec_v2(self.streamer._clock_skew)
        self.assertIn(f"Sec-MS-GEC={expected}", create.call_args_list[1].args[0])

    def test_403_without_date_header_is_raised(self):
        with mock.patch.object(
            self.websocket, "create_connection", side_effect=[self._bad_status({})]
        ):
            with self.assertRaises(self.websocket.WebSocketBadStatusException):
                self.streamer._connect()

    def test_repeated_403_is_raised_after_one_retry(self):
        date = email.utils.formatdate(time.time(), usegmt=True)
        with mock.patch.object(
            self.websocket,
            "create_connection",
            side_effect=[self._bad_status({"date": date}), self._bad_status({"date": date})],
        ) as create:
            with self.assertRaises(self.websocket.WebSocketBadStatusException):
                self.streamer._connect()
        self.assertEqual(create.call_count, 2)


if __name__ == "__main__":
    unittest.main()
