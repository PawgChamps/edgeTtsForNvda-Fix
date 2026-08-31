# Edge TTS — Android engine

A system-wide Text-to-Speech engine for Android that speaks through Microsoft
Edge's online neural voices. Once installed and selected, **TalkBack and any
other app** can use Edge voices system-wide.

This is a Kotlin port of the protocol from the NVDA add-on in the parent repo
(`synthDrivers/edgeTts/edge_tts_client.py`). The free Edge read-aloud endpoint
serves compressed audio (raw PCM is rejected with WS close code 1007), so we
request `audio-24khz-48kbitrate-mono-mp3` and decode MP3 → 16-bit PCM on-device
with Android's built-in `MediaExtractor` + `MediaCodec` (no ffmpeg). The decoded
PCM is fed to Android's `SynthesisCallback`.

## Requirements

- JDK 17
- Android SDK with platform **android-36** and build-tools **36.0.0**
- An internet connection at runtime (voices are synthesised online)

## Build

```bash
cd android
./gradlew assembleDebug        # debug APK   -> app/build/outputs/apk/debug/
./gradlew assembleRelease      # signed release APK -> app/build/outputs/apk/release/
```

The release build is minified (R8) + resource-shrunk and **signed** using
credentials from `keystore.properties` (see Signing below). minSdk 24,
targetSdk/compileSdk 36. ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 (phones/
tablets) — though the app is pure Kotlin/JVM with no native libs, so this is a
single universal APK regardless.

### Signing

Release signing reads `android/keystore.properties` (git-ignored):

```properties
storeFile=edge-tts-release.jks
storePassword=<password>
keyAlias=edgetts
keyPassword=<password>
```

The keystore (`*.jks`) and `keystore.properties` are **git-ignored** — never
commit them. If they're absent, `assembleRelease` still builds but produces an
**unsigned** APK. To regenerate a keystore:

```bash
keytool -genkeypair -v -keystore edge-tts-release.jks -alias edgetts \
  -keyalg RSA -keysize 2048 -validity 10000
```

If `local.properties` is missing, create it pointing at your SDK:

```properties
sdk.dir=C\:\\Android\\Sdk
```

> The Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is committed, so
> `./gradlew` works without a system Gradle install.

## Install & enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the device:

1. Open the **Edge TTS** app. On launch, if Edge TTS is not your default
   engine, it offers to open the system TTS settings so you can select it.
2. In the app: pick a **Language**, then a **Voice** for that language, edit the
   **sample text**, adjust **speech rate** and **pitch**, and press **Test** to
   hear it.
3. Press **Set as default** to save that voice + rate + pitch as the engine's
   global defaults. The system engine (`EdgeTtsService`) reads these, so
   TalkBack and every other app speak with your chosen settings.
4. **Open system TTS settings** jumps to Android's TTS screen to select Edge TTS
   as the preferred engine (or tap the gear next to it there).

### UI overview

- **Language** dropdown → filters the **Voice** dropdown to that locale.
- **Sample text** is editable; **Test** speaks it with the current selections.
- **Speech rate** slider is a Google-TTS-style multiplier (0.5x–5x), ported from
  the NVDA add-on; **Pitch** slider spans −50Hz…+50Hz (50 = neutral).
- The calling app's rate/pitch takes precedence whenever it is set (non-neutral);
  your saved app defaults apply only when the caller leaves them neutral. This
  avoids multiplying two rates together (e.g. app 2x × TalkBack 3x = 6x).

## Architecture

| File | Role | Ported from |
|------|------|-------------|
| `SecMsGec.kt` | SHA-256 `Sec-MS-GEC` auth token | `_generate_sec_ms_gec` |
| `Ssml.kt` | SSML + voice-name expansion | `_build_ssml`, `_expand_voice_name` |
| `EdgeVoices.kt` | voices/list fetch + 24h cache | `list_voices` |
| `EdgeTtsClient.kt` | WebSocket PCM streamer | `EdgeTtsStreamer` |
| `EdgeTtsService.kt` | Android `TextToSpeechService` | NVDA `synthDriver` |
| `EngineSettingsActivity.kt` | voice picker + preview | add-on settings ring |
| `CheckVoiceDataActivity.kt` / `GetSampleTextActivity.kt` | TTS framework hooks | — |

### System-wide voice selection (the Voice API)

`EdgeTtsService` implements the **modern Voice API** —
`onGetVoices()`, `onIsValidVoiceName()`, `onLoadVoice()`,
`onGetDefaultVoiceNameFor()` — in addition to the legacy
`onIsLanguageAvailable()`/`onLoadLanguage()`. This is what
`TextToSpeech.getVoices()` and TalkBack's per-voice picker depend on; without
it Android would surface only one generic voice per locale. Each Edge voice is
advertised with `requiresNetworkConnection = true`, `QUALITY_VERY_HIGH`, and
`LATENCY_HIGH`, and its `Voice.name` is the Edge short name (e.g.
`en-US-AriaNeural`) — the exact string that comes back in
`SynthesisRequest.getVoiceName()`.

## Notes / limitations

- Voices are **online only**. No network → no speech (the framework reports an
  error, same as any cloud engine).
- Rate/pitch are mapped from Android's scale onto Edge's `+N%` / `+NHz` scale in
  `EdgeTtsService.androidRateToEdgePercent` / `androidPitchToEdgeHz` — tune to
  taste.
- `Sec-MS-GEC` token generation must match the Python float arithmetic exactly
  or the server returns 403; see the comments in `SecMsGec.kt`.
