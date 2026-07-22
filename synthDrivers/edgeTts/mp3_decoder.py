# -*- coding: utf-8 -*-
from __future__ import annotations

import os
import subprocess
import threading
from collections.abc import Callable
from pathlib import Path

try:
    from logHandler import log
except Exception:
    import logging
    log = logging.getLogger("edgeTts")

SAMPLE_RATE = 24000
CHANNELS = 1
BITS_PER_SAMPLE = 16

AudioCallback = Callable[[bytes], None]


def _get_ffmpeg() -> str:
    """Return path to bundled ffmpeg.exe."""
    return str(Path(__file__).resolve().parent / "ffmpeg.exe")


def is_decoder_available() -> bool:
    return Path(_get_ffmpeg()).is_file()


def _spawn_ffmpeg() -> subprocess.Popen:
    """Spawn an ffmpeg process ready to accept MP3 input."""
    ffmpeg = _get_ffmpeg()
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startupinfo.wShowWindow = 0
    return subprocess.Popen(
        [
            ffmpeg,
            "-hide_banner", "-loglevel", "error",
            "-probesize", "32", "-analyzeduration", "0",
            "-fflags", "+nobuffer+fastseek+flush_packets",
            "-flags", "+low_delay",
            "-f", "mp3",
            "-i", "pipe:0",
            "-f", "s16le", "-ar", str(SAMPLE_RATE),
            "-ac", str(CHANNELS), "-acodec", "pcm_s16le",
            "-flush_packets", "1",
            "pipe:1",
        ],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        startupinfo=startupinfo, creationflags=subprocess.CREATE_NO_WINDOW,
    )


def decode_mp3_to_pcm(mp3_data: bytes) -> bytes:
    """One-shot decode: full MP3 buffer -> full PCM buffer."""
    if not mp3_data:
        return b""
    ffmpeg = _get_ffmpeg()
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startupinfo.wShowWindow = 0
    proc = subprocess.Popen(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error",
            "-i", "pipe:0",
            "-f", "s16le", "-ar", str(SAMPLE_RATE),
            "-ac", str(CHANNELS), "-acodec", "pcm_s16le",
            "pipe:1",
        ],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        startupinfo=startupinfo, creationflags=subprocess.CREATE_NO_WINDOW,
    )
    pcm, _ = proc.communicate(mp3_data, timeout=30)
    return pcm


class FfmpegPool:
    """Keeps one pre-spawned ffmpeg process ready for instant use.

    Eliminates the ~1s Windows process startup cost by having a replacement
    ready before it's needed.
    """

    def __init__(self) -> None:
        self._ready: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self._spawn_ready()

    def _spawn_ready(self) -> None:
        """Spawn a replacement ffmpeg in background."""
        def do_spawn():
            proc = _spawn_ffmpeg()
            with self._lock:
                if self._ready is None:
                    self._ready = proc
                else:
                    proc.kill()
        threading.Thread(target=do_spawn, daemon=True, name="edgeTts.ffmpegPool").start()

    def acquire(self) -> subprocess.Popen:
        """Get a ready ffmpeg process (instant if pool has one, else blocks to spawn)."""
        with self._lock:
            proc = self._ready
            self._ready = None
        if proc is not None and proc.poll() is None:
            self._spawn_ready()
            return proc
        self._spawn_ready()
        return _spawn_ffmpeg()

    def terminate(self) -> None:
        with self._lock:
            proc = self._ready
            self._ready = None
        if proc is not None:
            try:
                proc.kill()
                proc.wait(timeout=1)
            except Exception:
                pass


class StreamingMp3Decoder:
    """Per-request streaming decoder using a pre-spawned ffmpeg from the pool.

    Isolated per request — cancel kills this instance only, no leakage possible.
    """

    def __init__(self, pool: FfmpegPool, on_audio: AudioCallback | None = None) -> None:
        self._on_audio = on_audio
        self._process = pool.acquire()
        self._reader_thread: threading.Thread | None = None
        self._killed = False
        if self._on_audio:
            self._reader_thread = threading.Thread(
                target=self._read_output, daemon=True, name="edgeTts.mp3decode"
            )
            self._reader_thread.start()

    def _read_output(self) -> None:
        proc = self._process
        if proc is None or proc.stdout is None:
            return
        try:
            while True:
                chunk = proc.stdout.read(4096)
                if not chunk:
                    break
                if self._on_audio and not self._killed:
                    self._on_audio(chunk)
        except (OSError, ValueError):
            pass

    def feed(self, mp3_data: bytes) -> None:
        if self._killed:
            return
        try:
            self._process.stdin.write(mp3_data)
            self._process.stdin.flush()
        except (OSError, BrokenPipeError):
            pass

    def finish(self) -> None:
        """Close stdin and wait for all remaining PCM to be delivered."""
        try:
            if self._process.stdin and not self._process.stdin.closed:
                self._process.stdin.close()
        except OSError:
            pass
        if self._reader_thread:
            self._reader_thread.join(timeout=10)

    def close(self) -> None:
        """Immediately kill (used on cancel)."""
        self._killed = True
        try:
            self._process.kill()
            self._process.wait(timeout=1)
        except Exception:
            pass
