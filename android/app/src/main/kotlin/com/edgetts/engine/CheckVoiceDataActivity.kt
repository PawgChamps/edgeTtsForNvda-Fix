package com.edgetts.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The TTS framework launches this (CHECK_TTS_DATA) before treating the engine
 * as installed, to discover which locales have voice data. Because Edge
 * synthesises over the network there is nothing to download, so we always PASS.
 *
 * We report the locales of the cached voice catalog when available; otherwise a
 * broad fallback so the engine is usable before the first catalog fetch. This
 * activity runs on the main thread, so it must NOT hit the network — it reads
 * the cache only.
 */
class CheckVoiceDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fromCache = EdgeVoices.cachedOnly(applicationContext)
            .map { threeLetter(it.locale) }
            .filter { it.isNotEmpty() }
            .distinct()

        val available = ArrayList(fromCache.ifEmpty { FALLBACK_LOCALES })

        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }

    /** "en-US" -> "eng-USA" (ISO3 lang-country), the format CHECK_TTS_DATA expects. */
    private fun threeLetter(code: String): String {
        val loc = Locale.forLanguageTag(code)
        return runCatching {
            val lang = loc.isO3Language
            val country = loc.isO3Country
            if (lang.isEmpty()) "" else if (country.isEmpty()) lang else "$lang-$country"
        }.getOrDefault("")
    }

    private companion object {
        val FALLBACK_LOCALES = listOf(
            "eng-USA", "eng-GBR", "eng-AUS", "eng-IND",
            "spa-ESP", "spa-MEX", "fra-FRA", "deu-DEU",
            "ita-ITA", "por-BRA", "hin-IND", "jpn-JPN",
            "kor-KOR", "zho-CHN", "ara-SAU", "rus-RUS",
            "nld-NLD", "pol-POL", "tur-TUR", "tam-IND",
        )
    }
}
