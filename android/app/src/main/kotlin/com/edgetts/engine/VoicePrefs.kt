package com.edgetts.engine

import android.content.Context
import java.util.Locale

/**
 * Per-language voice choice, as in Google TTS: each Edge locale ("en-US",
 * "en-GB", ...) remembers its own voice, and the engine answers the system's
 * "which voice for this language?" with that choice.
 */
object VoicePrefs {
    private const val KEY_VOICE_PREFIX = "voice_for_"
    private const val KEY_ISSUED_DEFAULTS = "issued_default_voices"
    private const val KEY_SELECTED_LOCALE = "selected_locale"

    private fun prefs(context: Context) =
        context.getSharedPreferences(EdgeTtsService.PREFS, Context.MODE_PRIVATE)

    /** The voice for [locale]: the user's choice, else a sensible default. */
    fun voiceFor(context: Context, locale: String, catalog: Collection<EdgeVoice>): String? {
        val names = catalog.filter { it.locale == locale }.map { it.shortName }.toSet()
        if (names.isEmpty()) return null
        val prefs = prefs(context)
        prefs.getString(KEY_VOICE_PREFIX + locale, null)?.takeIf { it in names }?.let { return it }
        // Before per-language choices existed there was one global voice.
        prefs.getString(EdgeTtsService.KEY_VOICE, null)?.takeIf { it in names }?.let { return it }
        if (EdgeTtsService.DEFAULT_VOICE in names) return EdgeTtsService.DEFAULT_VOICE
        return names.minOrNull()
    }

    fun setVoiceFor(context: Context, locale: String, voice: String) {
        prefs(context).edit()
            .putString(KEY_VOICE_PREFIX + locale, voice)
            // Fallback for requests that carry no language at all.
            .putString(EdgeTtsService.KEY_VOICE, voice)
            .apply()
    }

    private fun hasChoice(context: Context, locale: String): Boolean =
        prefs(context).contains(KEY_VOICE_PREFIX + locale)

    /**
     * Maps a framework request (ISO 639-2 language, ISO 3166 alpha-3 country)
     * to an Edge locale. With no country match it prefers the device's region,
     * then a locale the user has configured.
     */
    fun localeFor(
        context: Context,
        iso3Language: String,
        iso3Country: String?,
        catalog: Collection<EdgeVoice>,
    ): String? {
        val sameLanguage = catalog.map { it.locale }.distinct().sorted()
            .filter { iso3Language(it).equals(iso3Language, ignoreCase = true) }
        if (sameLanguage.isEmpty()) return null
        if (!iso3Country.isNullOrEmpty()) {
            sameLanguage.firstOrNull { iso3Country(it).equals(iso3Country, ignoreCase = true) }
                ?.let { return it }
        }
        val deviceCountry = Locale.getDefault().country
        sameLanguage.firstOrNull { Locale.forLanguageTag(it).country.equals(deviceCountry, true) }
            ?.let { return it }
        return sameLanguage.firstOrNull { hasChoice(context, it) } ?: sameLanguage.first()
    }

    /**
     * Apps that never pick a voice get the default name once, when they
     * connect, and keep sending it. Remembering what was handed out lets a
     * later per-language choice apply without the app reconnecting.
     */
    fun rememberIssuedDefault(context: Context, voice: String) {
        val issued = prefs(context).getStringSet(KEY_ISSUED_DEFAULTS, emptySet()).orEmpty()
        if (voice !in issued) {
            prefs(context).edit().putStringSet(KEY_ISSUED_DEFAULTS, issued + voice).apply()
        }
    }

    fun wasIssuedDefault(context: Context, voice: String): Boolean =
        voice in prefs(context).getStringSet(KEY_ISSUED_DEFAULTS, emptySet()).orEmpty()

    /** The language last shown on the settings screen. */
    fun selectedLocale(context: Context): String? = prefs(context).getString(KEY_SELECTED_LOCALE, null)

    fun setSelectedLocale(context: Context, locale: String) {
        prefs(context).edit().putString(KEY_SELECTED_LOCALE, locale).apply()
    }

    private fun iso3Language(code: String): String =
        runCatching { Locale.forLanguageTag(code).isO3Language }.getOrDefault("")

    private fun iso3Country(code: String): String =
        runCatching { Locale.forLanguageTag(code).isO3Country }.getOrDefault("")
}
