# Edge TTS for NVDA and Android

Use Microsoft Edge's online neural text-to-speech (TTS) voices with the NVDA screen reader on Windows or as a system TTS engine on Android.

**Downloads and setup:** <https://athee06.github.io/edgeTtsForNvda/>

This add-on brings more than 300 natural-sounding, context-aware voices in dozens of languages to NVDA without requiring any expensive api keys.

---

## Features
*   **300+ Natural Voices**: Access Microsoft's extensive collection of online neural voices (e.g. Jenny, Aria, Pallavi, etc.) directly.
*   **Low Latency Streaming**: Streams speech audio directly as PCM chunks for fast keyboard responsiveness.
*   **Connection Pre-warming**: Automatically pre-opens WebSocket connections to Microsoft servers at startup to eliminate audio starting delay.
*   **Refreshes Voice List**: Update the list of voices on-demand from Microsoft servers via **NVDA Menu > Tools > Edge TTS Voice Refresh**.
*   **Auto-Updater**: Integrates automatic background update checks to notify you of new version releases.

---

## Installation

### Windows / NVDA

1. Go to the [Releases](https://github.com/athee06/edgeTtsForNvda/releases) page on GitHub.
2. Download the latest `.nvda-addon` package file.
3. Open/Run the downloaded file to install it in NVDA, then restart NVDA.

### Android

1. Download the signed APK from the [Android v1.0 release](https://github.com/athee06/edgeTtsForNvda/releases/tag/android-v1.0).
2. Install and open **Edge TTS**.
3. Choose **Edge TTS** in Android's text-to-speech settings.
4. Select a language and voice in the app. See the [Android documentation](android/README.md) for details.

---

## Usage & Configuration
1. Press `NVDA + Control + S` (or go to **NVDA Menu > Preferences > Settings > Speech** -> **Change Synthesizer**).
2. Choose **Edge TTS For NVDA** from the list.
3. Select your desired voice, speed, pitch, and volume, then click **OK**.

---

## Updating the Voice List
If Microsoft adds new voices, you can update the menu by:
1. Opening the **NVDA Menu**.
2. Navigating to **Tools** -> select **Edge TTS Voice Refresh**.
3. The add-on will query Microsoft servers and update your settings dialog voice list.

---

## License
This add-on is distributed under the terms of the GNU General Public License (GPL) version 2.0 or later, as compatible with the NVDA screen reader license.

## Independence and privacy notice

This is an independent open-source accessibility project. It is not affiliated with, sponsored by, or endorsed by Microsoft. Speech requires an internet connection, and text is sent to Microsoft's online speech service for synthesis. The consumer endpoint used by this project is undocumented and may change or stop working without notice.
