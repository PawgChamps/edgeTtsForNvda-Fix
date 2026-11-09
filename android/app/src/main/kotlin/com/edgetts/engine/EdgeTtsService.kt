package com.edgetts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System-wide Edge TTS engine. Once installed and selected in
 * Settings > Accessibility > Text-to-speech, TalkBack and any other app can
 * speak through Microsoft Edge neural voices.
 *
 * Implements both the legacy language API (onIsLanguageAvailable / onLoadLanguage)
 * and the modern Voice API (onGetVoices / onIsValidVoiceName / onLoadVoice /
 * onGetDefaultVoiceNameFor). The Voice API is what TalkBack's per-voice picker
 * and TextToSpeech.getVoices() depend on — without it only a single generic
 * voice per locale would surface.
 */
class EdgeTtsService : TextToSpeechService() {

    private val client = EdgeTtsClient()
    private val cancelled = AtomicBoolean(false)

    /** Lazily-loaded catalog, indexed by Edge short name (== Android Voice.name). */
    @Volatile private var catalog: Map<String, EdgeVoice> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        // Warm the catalog off the main thread; onCreate runs on the main thread
        // and EdgeVoices.list may hit the network on a cold/stale cache.
        Thread { loadCatalog() }.start()
        // Open the WebSocket ahead of the first utterance so the TLS + WS
        // handshake cost isn't paid on the first speak.
        client.watchNetwork(this)
        client.prewarm()
    }

    override fun onDestroy() {
        client.shutdown()
        super.onDestroy()
    }

    /** Loads (cache-first) the Edge voice catalog. Safe to call off the main thread. */
    private fun loadCatalog(): Map<String, EdgeVoice> {
        val cached = catalog
        if (cached.isNotEmpty()) return cached
        val loaded = runCatching { EdgeVoices.list(applicationContext) }
            .getOrElse { emptyList() }
            .associateBy { it.shortName }
        if (loaded.isNotEmpty()) catalog = loaded
        return loaded
    }

    // ---- Legacy language API (abstract, still required) --------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val voices = loadCatalog().values
        var langMatch = false
        for (v in voices) {
            val loc = localeOf(v.locale)
            if (!loc.isO3Language.equals(lang, ignoreCase = true)) continue
            langMatch = true
            if (!country.isNullOrEmpty() && loc.isO3Country.equals(country, ignoreCase = true)) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
        }
        return if (langMatch) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        // Dead on API 21+, but abstract. Report the saved default voice's locale.
        val saved = savedVoiceName()
        val loc = catalog[saved]?.let { localeOf(it.locale) } ?: Locale.US
        return arrayOf(loc.isO3Language, loc.isO3Country, "")
    }

    // ---- Modern Voice API --------------------------------------------------

    override fun onGetVoices(): MutableList<Voice> {
        val out = ArrayList<Voice>()
        for (v in loadCatalog().values) {
            out.add(
                Voice(
                    v.shortName,                       // name == what comes back in getVoiceName()
                    localeOf(v.locale),
                    // QUALITY_NORMAL (not VERY_HIGH): advertising the maximum
                    // quality on every voice makes some OEM Settings UIs and the
                    // framework's engine-resolver auto-PREFER this engine in any
                    // ranking/fallback, which showed up as "preferred engine"
                    // wrongly displaying Edge TTS after switching away.
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_HIGH,                // cloud round-trip
                    true,                              // requiresNetworkConnection
                    emptySet()
                )
            )
        }
        return out
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName != null && loadCatalog().containsKey(voiceName)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int =
        // Nothing to download for a network voice; validity == loadable.
        onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (lang == null) return null
        val voices = loadCatalog().values
        val locale = VoicePrefs.localeFor(this, lang, country, voices) ?: return null
        return VoicePrefs.voiceFor(this, locale, voices)
            ?.also { VoicePrefs.rememberIssuedDefault(this, it) }
    }

    // ---- Synthesis ---------------------------------------------------------

    override fun onStop() {
        // Called on a different thread than onSynthesizeText; polled between chunks.
        cancelled.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        cancelled.set(false)

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            // Conclude cleanly so the client's progress listener doesn't hang.
            callback.start(EdgeTtsClient.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, EdgeTtsClient.CHANNELS)
            callback.done()
            return
        }

        val (rate, pitch) = resolveRateAndPitch(request)
        val voice = resolveVoice(request)

        // 16-bit PCM mono @ 24kHz — exactly what we receive from Edge, no decode.
        callback.start(EdgeTtsClient.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, EdgeTtsClient.CHANNELS)

        // Chunk size MUST NOT exceed maxBufferSize, or audioAvailable() throws.
        val maxChunk = callback.maxBufferSize.coerceAtLeast(1)

        try {
            client.speak(
                text = text,
                voice = voice,
                rate = rate,
                pitch = pitch,
                volume = 0,
                cancelled = cancelled,
            ) { pcm ->
                var offset = 0
                while (offset < pcm.size && !cancelled.get()) {
                    val len = minOf(maxChunk, pcm.size - offset)
                    // A non-SUCCESS return means the framework stopped us.
                    if (callback.audioAvailable(pcm, offset, len) != TextToSpeech.SUCCESS) {
                        cancelled.set(true)
                        break
                    }
                    offset += len
                }
            }
            // Always conclude, even after a stop.
            callback.done()
        } catch (e: EdgeTtsException) {
            Log.e(TAG, "Synthesis failed", e)
            EngineErrorLog.record(this, "${e.message} [${text.length} chars]")
            callback.error(TextToSpeech.ERROR_NETWORK)
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected synthesis error", e)
            EngineErrorLog.record(this, "Unexpected: $e [${text.length} chars]")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            callback.done()
        }
    }

    /**
     * Resolves the Edge rate (%) and pitch (Hz) to use.
     *
     * Design: the calling app's rate/pitch takes precedence whenever it is set
     * (i.e. not the neutral 100). Only when the caller leaves them neutral do we
     * apply the user's saved app defaults. This avoids the surprising outcome of
     * multiplying two rates together (e.g. app 2x × TalkBack 3x = 6x).
     */
    private fun resolveRateAndPitch(request: SynthesisRequest): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedRatePercent = prefs.getInt(KEY_RATE_PERCENT, 0)
        val savedPitchHz = prefs.getInt(KEY_PITCH_HZ, 0)

        val frameworkRate = request.speechRate    // 100 = normal
        val frameworkPitch = request.pitch         // 100 = normal

        // Android's speechRate is linear about 100; Edge's rate% is too:
        // 200 -> +100% (2x), 50 -> -50%.
        val ratePercent = if (frameworkRate <= 0 || frameworkRate == 100) {
            savedRatePercent
        } else {
            frameworkRate - 100
        }
        val pitchHz = if (frameworkPitch <= 0 || frameworkPitch == 100) {
            savedPitchHz
        } else {
            (frameworkPitch - 100) / 2
        }
        return ratePercent.coerceIn(-90, 400) to pitchHz.coerceIn(-50, 50)
    }

    private fun resolveVoice(request: SynthesisRequest): String {
        // setVoice() path: the exact Voice.name we handed out comes back here.
        val requested = request.voiceName
        val catalog = loadCatalog()
        val requestedVoice = requested?.let { catalog[it] }
        if (requestedVoice != null) {
            // A name we issued as a language default (not one the app chose)
            // follows the user's current choice for that language.
            if (!VoicePrefs.wasIssuedDefault(this, requestedVoice.shortName)) return requestedVoice.shortName
            return VoicePrefs.voiceFor(this, requestedVoice.locale, catalog.values) ?: requestedVoice.shortName
        }

        // setLanguage() path: getVoiceName() may be empty — map the locale.
        val lang = request.language
        if (!lang.isNullOrEmpty()) {
            onGetDefaultVoiceNameFor(lang, request.country, request.variant)?.let { return it }
        }
        return savedVoiceName()
    }

    private fun savedVoiceName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    /** Parses an Edge locale code ("en-US", "sr-Latn-RS") into a Locale. */
    private fun localeOf(code: String): Locale = Locale.forLanguageTag(code)

    companion object {
        private const val TAG = "EdgeTtsService"
        const val PREFS = "edge_tts_prefs"
        const val KEY_VOICE = "default_voice"
        const val KEY_RATE_PERCENT = "default_rate_percent"
        const val KEY_PITCH_HZ = "default_pitch_hz"
        const val DEFAULT_VOICE = "en-US-AriaNeural"
    }
}
