# -*- coding: utf-8 -*-
from __future__ import annotations

from collections import OrderedDict
from collections.abc import Iterator
from contextlib import suppress
import threading
from typing import Any

import synthDriverHandler
import config
from logHandler import log
from nvwave import WavePlayer
from speech.commands import (
    BreakCommand,
    IndexCommand,
    LangChangeCommand,
    PitchCommand,
    RateCommand,
    VolumeCommand,
)
from synthDriverHandler import VoiceInfo, synthDoneSpeaking, synthIndexReached

from .edge_tts_client import (
    EdgeTtsCancelled,
    EdgeTtsError,
    EdgeTtsStreamer,
    list_voices,
    SAMPLE_RATE,
    _trim_leading_silence,
)

try:
    import addonHandler
    addonHandler.initTranslation()
except Exception:
    def _(msg: str) -> str:
        return msg


_DEFAULT_VOICE = "en-US-AriaNeural"


class SynthDriver(synthDriverHandler.SynthDriver):
    name = "edgeTts"
    description = _("Edge TTS For NVDA")
    supportedSettings = (
        synthDriverHandler.SynthDriver.VoiceSetting(),
        synthDriverHandler.SynthDriver.RateSetting(),
        synthDriverHandler.SynthDriver.PitchSetting(),
        synthDriverHandler.SynthDriver.VolumeSetting(),
    )
    supportedCommands = {
        BreakCommand,
        IndexCommand,
        LangChangeCommand,
        RateCommand,
        PitchCommand,
        VolumeCommand,
    }
    supportedNotifications = {synthIndexReached, synthDoneSpeaking}

    @classmethod
    def check(cls) -> bool:
        return True

    def __init__(self) -> None:
        super().__init__()
        self._voiceData = self._load_voices()
        if not self._voiceData:
            raise RuntimeError(
                _("Edge TTS For NVDA could not load the voice list. Check your internet connection.")
            )
        self.availableVoices = self._build_available_voices()
        self._streamer = EdgeTtsStreamer()
        threading.Thread(
            target=self._prewarm_connection, daemon=True, name="edgeTts.prewarm"
        ).start()
        self._playerOutputDevice = self._current_output_device()
        self._player = self._create_wave_player(self._playerOutputDevice)
        self._speechQueue: list[tuple[list[Any], str, int, int, int, threading.Event]] = []
        self._speechLock = threading.Condition()
        self._activeCancelEvent: threading.Event | None = None
        self._shutdownEvent = threading.Event()
        self._worker = threading.Thread(
            name="edgeTts.speech", target=self._speech_loop, daemon=True
        )
        self._worker.start()
        self.__voice = self._initial_voice()
        self._rate = 50
        self._pitch = 50
        self._volume = 100

    def _prewarm_connection(self) -> None:
        try:
            self._streamer._get_connection()
        except Exception:
            pass

    def _load_voices(self) -> list[dict[str, Any]]:
        try:
            return list_voices()
        except Exception:
            log.exception("Edge TTS: failed to load voices")
            return []

    def _build_available_voices(self) -> OrderedDict[str, VoiceInfo]:
        voices: OrderedDict[str, VoiceInfo] = OrderedDict()
        voice_list = []
        for v in self._voiceData:
            short_name = v.get("ShortName", "")
            locale = v.get("Locale", "")
            friendly = v.get("FriendlyName", short_name)
            if not short_name:
                continue
            # FriendlyName: "Microsoft Adri Online (Natural) - Afrikaans (South Africa)"
            parts = friendly.split(" - ", 1)
            name = parts[0].replace("Microsoft ", "").replace(" Online", "")
            lang = parts[1] if len(parts) > 1 else locale
            label = f"{lang} - {name} - {locale}"
            voice_list.append((lang, name, label, short_name, locale))
        voice_list.sort()
        for _lang, _name, label, short_name, locale in voice_list:
            voices[short_name] = VoiceInfo(short_name, label, locale.replace("-", "_"))
        return voices

    def _initial_voice(self) -> str:
        try:
            configured = config.conf["speech"][self.name]["voice"]
            if configured in self.availableVoices:
                return configured
        except Exception:
            pass
        if _DEFAULT_VOICE in self.availableVoices:
            return _DEFAULT_VOICE
        for v in self._voiceData:
            if v.get("Locale", "").startswith("en-"):
                short = v.get("ShortName", "")
                if short in self.availableVoices:
                    return short
        return next(iter(self.availableVoices))

    def terminate(self) -> None:
        self.cancel()
        self._shutdownEvent.set()
        with self._speechLock:
            self._speechLock.notify_all()
        with suppress(Exception):
            self._streamer.terminate()
        with suppress(Exception):
            self._player.close()

    def speak(self, speechSequence: list[Any]) -> None:
        sequence = list(speechSequence)
        cancelEvent = threading.Event()
        with self._speechLock:
            if self._shutdownEvent.is_set():
                return
            self._speechQueue.append(
                (sequence, self.__voice, self._rate, self._pitch, self._volume, cancelEvent)
            )
            self._speechLock.notify()

    def cancel(self) -> None:
        with self._speechLock:
            if self._activeCancelEvent is not None:
                self._activeCancelEvent.set()
            for req in self._speechQueue:
                req[-1].set()
            self._speechQueue.clear()
            self._speechLock.notify_all()
        with suppress(Exception):
            self._player.stop()

    def pause(self, switch: bool) -> None:
        self._player.pause(switch)

    def _current_output_device(self) -> str:
        try:
            return str(config.conf["audio"]["outputDevice"])
        except Exception:
            return getattr(WavePlayer, "DEFAULT_DEVICE_KEY", "default")

    def _create_wave_player(self, output_device: str) -> WavePlayer:
        try:
            return WavePlayer(
                channels=1,
                samplesPerSec=SAMPLE_RATE,
                bitsPerSample=16,
                outputDevice=output_device,
            )
        except TypeError:
            return WavePlayer(channels=1, samplesPerSec=SAMPLE_RATE, bitsPerSample=16)

    def _ensure_output_device(self) -> None:
        device = self._current_output_device()
        if device == self._playerOutputDevice:
            return
        with suppress(Exception):
            self._player.close()
        self._playerOutputDevice = device
        self._player = self._create_wave_player(device)

    # ─── Speech Loop ───────────────────────────────────────────────────

    def _speech_loop(self) -> None:
        while not self._shutdownEvent.is_set():
            with self._speechLock:
                while not self._speechQueue and not self._shutdownEvent.is_set():
                    self._speechLock.wait()
                if self._shutdownEvent.is_set():
                    return
                request = self._speechQueue.pop(0)
                self._activeCancelEvent = request[-1]
            try:
                self._process_speech(*request)
            finally:
                with self._speechLock:
                    if self._activeCancelEvent is request[-1]:
                        self._activeCancelEvent = None

    def _process_speech(
        self,
        sequence: list[Any],
        voice: str,
        rate: int,
        pitch: int,
        volume: int,
        cancel_event: threading.Event,
    ) -> None:
        try:
            self._ensure_output_device()
            for kind, payload in self._iter_chunks(sequence, voice, rate, pitch, volume, cancel_event):
                if cancel_event.is_set():
                    return
                if kind == "text":
                    self._speak_text(*payload, cancel_event)
                elif kind == "break":
                    self._feed_silence(payload)
                elif kind == "index":
                    self._sync_player()
                    if not cancel_event.is_set():
                        synthIndexReached.notify(synth=self, index=payload)
            if not cancel_event.is_set():
                self._player.idle()
                synthDoneSpeaking.notify(synth=self)
        except EdgeTtsCancelled:
            pass
        except Exception:
            log.exception("Edge TTS speech error")
            if not cancel_event.is_set():
                synthDoneSpeaking.notify(synth=self)

    def _iter_chunks(
        self,
        sequence: list[Any],
        voice: str,
        rate: int,
        pitch: int,
        volume: int,
        cancel_event: threading.Event,
    ) -> Iterator[tuple[str, Any]]:
        text_parts: list[str] = []
        pending_indexes: list[int] = []
        active_voice = voice

        def flush():
            nonlocal text_parts, pending_indexes
            raw = "".join(text_parts)
            text_parts = []
            text = raw.strip()
            indexes = list(pending_indexes)
            pending_indexes = []
            if not text:
                for idx in indexes:
                    yield ("index", idx)
                return
            yield ("text", (text, active_voice, rate, pitch, volume, indexes))

        for item in sequence:
            if cancel_event.is_set():
                return
            if isinstance(item, str):
                text_parts.append(item)
            elif isinstance(item, BreakCommand):
                yield from flush()
                yield ("break", max(0, int(item.time)))
            elif isinstance(item, IndexCommand):
                pending_indexes.append(item.index)
            elif isinstance(item, LangChangeCommand):
                yield from flush()
                active_voice = self._voice_for_language(item.lang, voice)
            elif isinstance(item, RateCommand):
                yield from flush()
                rate = int(item.newValue)
            elif isinstance(item, PitchCommand):
                yield from flush()
                pitch = int(item.newValue)
            elif isinstance(item, VolumeCommand):
                yield from flush()
                volume = int(item.newValue)
        yield from flush()

    def _speak_text(
        self,
        text: str,
        voice: str,
        rate: int,
        pitch: int,
        volume: int,
        indexes: list[int],
        cancel_event: threading.Event,
    ) -> None:
        rate_percent = self._rate_to_percent(rate)
        pitch_hz = self._pitch_to_hz(pitch)
        volume_percent = self._volume_to_percent(volume)

        first_chunk = [True]

        def on_audio(pcm: bytes) -> None:
            if not cancel_event.is_set() and pcm:
                if first_chunk[0]:
                    first_chunk[0] = False
                    pcm = _trim_leading_silence(pcm)
                    if not pcm:
                        return
                self._player.feed(pcm)

        self._streamer.speak(
            text=text,
            voice=voice,
            rate=rate_percent,
            pitch=pitch_hz,
            volume=volume_percent,
            on_audio=on_audio,
            cancel_event=cancel_event,
        )

        for idx in indexes:
            if cancel_event.is_set():
                return
            self._sync_player()
            synthIndexReached.notify(synth=self, index=idx)

    def _feed_silence(self, milliseconds: int) -> None:
        if milliseconds <= 0:
            return
        frame_count = int(SAMPLE_RATE * milliseconds / 1000)
        self._player.feed(b"\x00\x00" * frame_count)

    def _sync_player(self) -> None:
        sync = getattr(self._player, "sync", None)
        if sync is not None:
            sync()
            return
        self._player.idle()

    def _rate_to_percent(self, value: int) -> int:
        percent = max(0, min(100, value)) / 100.0
        speed = 0.5 + (5.0 - 0.5) * percent
        return int((speed - 1.0) * 100)

    def _pitch_to_hz(self, value: int) -> int:
        return int(-50 + 100 * max(0, min(100, value)) / 100.0)

    def _volume_to_percent(self, value: int) -> int:
        return max(0, min(100, value)) - 100

    def _voice_for_language(self, lang: str | None, fallback: str) -> str:
        if not lang:
            return fallback
        normalized = lang.replace("_", "-").lower()
        fallback_locale = self._voice_locale(fallback).lower()
        if fallback_locale == normalized or fallback_locale.startswith(normalized.split("-")[0]):
            return fallback
        for short_name, info in self.availableVoices.items():
            voice_lang = str(info.language).replace("_", "-").lower()
            if voice_lang == normalized:
                return short_name
        root = normalized.split("-")[0]
        for short_name, info in self.availableVoices.items():
            voice_lang = str(info.language).replace("_", "-").lower()
            if voice_lang.startswith(root):
                return short_name
        return fallback

    def _voice_locale(self, voice: str) -> str:
        info = self.availableVoices.get(voice)
        if info:
            return str(info.language).replace("_", "-")
        return ""

    def _get_voice(self) -> str:
        return self.__voice

    def _set_voice(self, value: str) -> None:
        if value not in self.availableVoices:
            value = next(iter(self.availableVoices))
        self.__voice = value

    def _get_language(self) -> str:
        info = self.availableVoices.get(self.__voice)
        if info:
            return str(info.language)
        return "en_US"

    def _get_rate(self) -> int:
        return self._rate

    def _set_rate(self, value: int) -> None:
        self._rate = max(0, min(100, int(value)))

    def _get_pitch(self) -> int:
        return self._pitch

    def _set_pitch(self, value: int) -> None:
        self._pitch = max(0, min(100, int(value)))

    def _get_volume(self) -> int:
        return self._volume

    def _set_volume(self, value: int) -> None:
        self._volume = max(0, min(100, int(value)))
