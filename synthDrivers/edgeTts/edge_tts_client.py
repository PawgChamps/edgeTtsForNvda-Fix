# -*- coding: utf-8 -*-
from __future__ import annotations

import email.utils
import json
import os
import struct
import threading
import time
import urllib.request
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

try:
    from logHandler import log
except Exception:
    import logging
    log = logging.getLogger("edgeTts")

from .drm import TRUSTED_CLIENT_TOKEN, generate_sec_ms_gec_v2

CHROMIUM_VERSION = "143.0.3650.75"
SEC_MS_GEC_VERSION = f"2-{CHROMIUM_VERSION}"
VOICES_URL = (
    "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list"
    f"?trustedclienttoken={TRUSTED_CLIENT_TOKEN}"
)
WSS_URL = (
    "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    f"?TrustedClientToken={TRUSTED_CLIENT_TOKEN}"
)
OUTPUT_FORMAT = "audio-24khz-96kbitrate-mono-mp3"
SAMPLE_RATE = 24000
BITS_PER_SAMPLE = 16
CHANNELS = 1
USER_AGENT = (
    f"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    f"(KHTML, like Gecko) Chrome/{CHROMIUM_VERSION} Safari/537.36 Edg/{CHROMIUM_VERSION}"
)

_VOICE_CACHE_FILE = "voices_cache.json"
_VOICE_CACHE_MAX_AGE = 86400

_CONNECT_TIMEOUT = 10.0
# Edge streams the first audio within ~1s; silence past these means a dead socket.
_FIRST_AUDIO_TIMEOUT = 10.0
_STREAM_STALL_TIMEOUT = 10.0

from .mp3_decoder import decode_mp3_to_pcm, FfmpegPool, StreamingMp3Decoder

AudioCallback = Callable[[bytes], None]


def _generate_sec_ms_gec(clock_skew: float = 0) -> str:
    return generate_sec_ms_gec_v2(clock_skew)


def _random_hex(length: int = 32) -> str:
    return uuid.uuid4().hex[:length]


def _js_timestamp() -> str:
    return time.strftime("%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)", time.gmtime())


def _xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def _build_ssml(text: str, voice: str, rate: str = "+0%", pitch: str = "+0Hz", volume: str = "+0%") -> str:
    escaped = _xml_escape(text)
    return (
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
        f"<voice name='{voice}'>"
        f"<prosody pitch='{pitch}' rate='{rate}' volume='{volume}'>"
        f"{escaped}"
        "</prosody></voice></speak>"
    )


def _trim_leading_silence(pcm: bytes, threshold: int = 50) -> bytes:
    sample_size = 2
    max_trim = SAMPLE_RATE * sample_size // 4
    i = 0
    while i < min(len(pcm), max_trim) - sample_size:
        sample = struct.unpack_from("<h", pcm, i)[0]
        if abs(sample) > threshold:
            i -= i % sample_size
            return pcm[i:]
        i += sample_size
    return pcm


def _expand_voice_name(short_name: str) -> str:
    if short_name.startswith("Microsoft Server Speech"):
        return short_name
    parts = short_name.split("-", 2)
    if len(parts) >= 3:
        locale = f"{parts[0]}-{parts[1]}"
        name = parts[2]
        return f"Microsoft Server Speech Text to Speech Voice ({locale}, {name})"
    return short_name


def _cache_dir() -> Path:
    try:
        import globalVars  # type: ignore
        config_path = getattr(getattr(globalVars, "appArgs", None), "configPath", None)
        if config_path:
            d = Path(config_path) / "edgeTtsForNvda"
            d.mkdir(parents=True, exist_ok=True)
            return d
    except Exception:
        pass
    import tempfile
    d = Path(tempfile.gettempdir()) / "edgeTtsForNvda"
    d.mkdir(parents=True, exist_ok=True)
    return d


class EdgeTtsError(Exception):
    pass


class EdgeTtsCancelled(Exception):
    pass


class _StreamFailed(Exception):
    def __init__(self, message: str, got_audio: bool) -> None:
        super().__init__(message)
        self.got_audio = got_audio


def list_voices(force_refresh: bool = False) -> list[dict[str, Any]]:
    cache_path = _cache_dir() / _VOICE_CACHE_FILE
    if not force_refresh and cache_path.is_file():
        try:
            stat = cache_path.stat()
            if time.time() - stat.st_mtime < _VOICE_CACHE_MAX_AGE:
                data = json.loads(cache_path.read_text(encoding="utf-8"))
                if isinstance(data, list) and len(data) > 0:
                    return data
        except Exception:
            pass

    sec_gec = _generate_sec_ms_gec()
    url = f"{VOICES_URL}&Sec-MS-GEC={sec_gec}&Sec-MS-GEC-Version={SEC_MS_GEC_VERSION}"
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Encoding": "identity",
        "Accept-Language": "en-US,en;q=0.9",
    }
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        if cache_path.is_file():
            try:
                return json.loads(cache_path.read_text(encoding="utf-8"))
            except Exception:
                pass
        raise EdgeTtsError(f"Failed to fetch voice list: {exc}") from exc

    if not isinstance(data, list):
        raise EdgeTtsError("Voice list response is not a list.")

    try:
        cache_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass
    return data


class EdgeTtsStreamer:
    def __init__(self) -> None:
        self._clock_skew: float = 0
        self._ws_module = None
        self._ws = None
        self._ws_lock = threading.Lock()
        self._pool = FfmpegPool()

    def _get_ws_module(self) -> Any:
        if self._ws_module is not None:
            return self._ws_module
        ws_dir = Path(__file__).resolve().parent / "websocketClientRepo"
        import sys
        if str(ws_dir) not in sys.path:
            sys.path.insert(1, str(ws_dir))
        import websocket
        self._ws_module = websocket
        return websocket

    def _adjust_clock_skew(self, resp_headers: dict | None) -> bool:
        """Sync the token clock to the server's Date header (as edge-tts does)."""
        date = (resp_headers or {}).get("date")
        if not date:
            return False
        try:
            server_time = email.utils.parsedate_to_datetime(date).timestamp()
        except (TypeError, ValueError):
            return False
        self._clock_skew = server_time - time.time()
        return True

    def _connect(self, clock_retry: bool = True) -> Any:
        websocket = self._get_ws_module()
        connection_id = _random_hex(32)
        sec_gec = _generate_sec_ms_gec(self._clock_skew)
        ws_url = f"{WSS_URL}&ConnectionId={connection_id}&Sec-MS-GEC={sec_gec}&Sec-MS-GEC-Version={SEC_MS_GEC_VERSION}"
        headers = {
            "Pragma": "no-cache",
            "Cache-Control": "no-cache",
            "Origin": "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            "User-Agent": USER_AGENT,
            "Accept-Encoding": "gzip, deflate, br",
            "Accept-Language": "en-US,en;q=0.9",
        }
        try:
            ws = websocket.create_connection(
                ws_url,
                timeout=_CONNECT_TIMEOUT,
                header=[f"{k}: {v}" for k, v in headers.items()],
                suppress_origin=True,
            )
        except websocket.WebSocketBadStatusException as exc:
            # The token is time-based, so a skewed PC clock yields 403.
            if (
                clock_retry
                and exc.status_code == 403
                and self._adjust_clock_skew(exc.resp_headers)
            ):
                return self._connect(clock_retry=False)
            raise
        config_msg = (
            f"X-Timestamp:{_js_timestamp()}\r\n"
            f"Content-Type:application/json; charset=utf-8\r\n"
            f"Path:speech.config\r\n\r\n"
            f'{{"context":{{"synthesis":{{"audio":{{"metadataoptions":{{'
            f'"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"}},'
            f'"outputFormat":"{OUTPUT_FORMAT}"}}}}}}}}'
        )
        ws.send(config_msg)
        return ws

    def _get_connection(self, fresh: bool = False) -> Any:
        if fresh:
            self._close_connection()
        with self._ws_lock:
            if self._ws is not None:
                try:
                    self._ws.ping()
                    return self._ws
                except Exception:
                    try:
                        self._ws.close()
                    except Exception:
                        pass
                    self._ws = None
            ws = self._connect()
            self._ws = ws
            return ws

    def _close_connection(self) -> None:
        with self._ws_lock:
            if self._ws is not None:
                try:
                    self._ws.close()
                except Exception:
                    pass
                self._ws = None

    def terminate(self) -> None:
        self._close_connection()
        self._pool.terminate()

    def speak(
        self,
        text: str,
        voice: str,
        rate: int = 0,
        pitch: int = 0,
        volume: int = 0,
        on_audio: AudioCallback | None = None,
        cancel_event: threading.Event | None = None,
    ) -> bytes:
        if not text.strip():
            return b""

        websocket = self._get_ws_module()
        full_voice = _expand_voice_name(voice)
        rate_str = f"{rate:+d}%"
        pitch_str = f"{pitch:+d}Hz"
        volume_str = f"{volume:+d}%"

        ssml = _build_ssml(text, full_voice, rate_str, pitch_str, volume_str)
        decoder = StreamingMp3Decoder(self._pool, on_audio=on_audio) if on_audio else None
        mp3_parts: list[bytes] = []

        try:
            # A reused connection may be dead (server idle close, network
            # change) while still passing ping(); retry once on a fresh socket,
            # but only if no audio was played yet so nothing is spoken twice.
            for attempt in range(2):
                if cancel_event and cancel_event.is_set():
                    raise EdgeTtsCancelled()
                try:
                    ws = self._get_connection(fresh=attempt > 0)
                except (websocket.WebSocketException, OSError) as exc:
                    if attempt == 1:
                        raise EdgeTtsError(f"Could not connect to Edge TTS: {exc}") from exc
                    continue
                try:
                    self._stream(ws, websocket, ssml, decoder, mp3_parts, cancel_event)
                    break
                except _StreamFailed as exc:
                    self._close_connection()
                    if exc.got_audio or attempt == 1:
                        raise EdgeTtsError(str(exc)) from exc
                    log.debug(f"Edge TTS: {exc}; retrying on a new connection")

            if cancel_event and cancel_event.is_set():
                raise EdgeTtsCancelled()

            if decoder:
                decoder.finish()
                return b""
            else:
                mp3_data = b"".join(mp3_parts)
                if not mp3_data:
                    return b""
                pcm = decode_mp3_to_pcm(mp3_data)
                if not pcm:
                    return b""
                return _trim_leading_silence(pcm)

        except Exception:
            self._close_connection()
            if decoder:
                decoder.close()
            raise

    def _stream(
        self,
        ws: Any,
        websocket: Any,
        ssml: str,
        decoder: StreamingMp3Decoder | None,
        mp3_parts: list[bytes],
        cancel_event: threading.Event | None,
    ) -> None:
        """Sends one SSML request and pumps audio until turn.end."""
        got_audio = False
        try:
            ws.send(
                f"X-RequestId:{_random_hex(32)}\r\n"
                f"Content-Type:application/ssml+xml\r\n"
                f"X-Timestamp:{_js_timestamp()}Z\r\n"
                f"Path:ssml\r\n\r\n"
                f"{ssml}"
            )
            ws.settimeout(0.05)
            started = last_data = time.monotonic()

            while True:
                if cancel_event and cancel_event.is_set():
                    raise EdgeTtsCancelled()
                now = time.monotonic()
                if not got_audio and now - started > _FIRST_AUDIO_TIMEOUT:
                    raise _StreamFailed("No audio received from server", got_audio)
                if got_audio and now - last_data > _STREAM_STALL_TIMEOUT:
                    raise _StreamFailed("Audio stream stalled", got_audio)

                try:
                    opcode, data = ws.recv_data()
                except websocket.WebSocketTimeoutException:
                    continue
                last_data = time.monotonic()

                if opcode == websocket.ABNF.OPCODE_BINARY:
                    if len(data) < 2:
                        continue
                    header_len = struct.unpack(">H", data[:2])[0]
                    audio_data = data[2 + header_len:]
                    header_bytes = data[2:2 + header_len]
                    header_text = header_bytes.decode("utf-8", errors="replace")
                    if "Path:audio" in header_text and audio_data:
                        got_audio = True
                        if decoder:
                            decoder.feed(audio_data)
                        else:
                            mp3_parts.append(audio_data)

                elif opcode == websocket.ABNF.OPCODE_TEXT:
                    text_data = data if isinstance(data, str) else data.decode("utf-8", errors="replace")
                    if "Path:turn.end" in text_data:
                        return
        except websocket.WebSocketConnectionClosedException as exc:
            raise _StreamFailed("Connection closed by server", got_audio) from exc
        except (websocket.WebSocketException, OSError) as exc:
            raise _StreamFailed(f"WebSocket connection lost: {exc}", got_audio) from exc
