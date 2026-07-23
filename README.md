# Edge TTS For NVDA (Online Version)

Use Microsoft Edge's free, high-quality online neural text-to-speech (TTS) voices directly inside the NVDA screen reader. 

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
1. Go to the [Releases](https://github.com/athee06/edgeTtsForNvda/releases) page on GitHub.
2. Download the latest `.nvda-addon` package file.
3. Open/Run the downloaded file to install it in NVDA, then restart NVDA.

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
