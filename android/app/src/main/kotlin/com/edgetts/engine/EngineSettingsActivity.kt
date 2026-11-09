package com.edgetts.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The app's main screen. Lets the user:
 *  - pick a language, then a voice within that language
 *  - edit the sample text
 *  - adjust speech rate and pitch
 *  - Test (speak the sample with the current selections)
 *  - Set as default (persist voice + rate + pitch for the system engine)
 *  - Open the system TTS settings
 *
 * On launch it checks whether Edge TTS is the device's default engine and, if
 * not, offers to jump to the system TTS settings.
 */
class EngineSettingsActivity : Activity() {

    private lateinit var languageSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var sampleText: EditText
    private lateinit var rateSeek: SeekBar
    private lateinit var pitchSeek: SeekBar
    private lateinit var rateLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var status: TextView

    private var allVoices: List<EdgeVoice> = emptyList()
    private var languages: List<String> = emptyList()          // sorted locale codes
    private var voicesForLanguage: List<EdgeVoice> = emptyList()

    private val previewClient = EdgeTtsClient()
    private val previewCancel = AtomicBoolean(false)
    @Volatile private var previewPlayer: PcmPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        languageSpinner = findViewById(R.id.languageSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        sampleText = findViewById(R.id.sampleText)
        rateSeek = findViewById(R.id.rateSeek)
        pitchSeek = findViewById(R.id.pitchSeek)
        rateLabel = findViewById(R.id.rateLabel)
        pitchLabel = findViewById(R.id.pitchLabel)
        status = findViewById(R.id.statusText)

        sampleText.setText(getString(R.string.default_sample))

        val prefs = getSharedPreferences(EdgeTtsService.PREFS, MODE_PRIVATE)
        rateSeek.progress = ratePercentToSlider(prefs.getInt(EdgeTtsService.KEY_RATE_PERCENT, 0))
        pitchSeek.progress = pitchHzToSlider(prefs.getInt(EdgeTtsService.KEY_PITCH_HZ, 0))
        updateRateLabel()
        updatePitchLabel()

        rateSeek.setOnSeekBarChangeListener(simpleSeekListener { updateRateLabel() })
        pitchSeek.setOnSeekBarChangeListener(simpleSeekListener { updatePitchLabel() })

        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                onLanguageChosen(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.testButton).setOnClickListener { onTest() }
        findViewById<Button>(R.id.setButton).setOnClickListener { onSetDefault() }
        findViewById<Button>(R.id.systemTtsButton).setOnClickListener { openSystemTtsSettings() }

        loadVoices()
        maybePromptSetDefault()
        // Warm the connection so the first Test tap doesn't pay the handshake.
        previewClient.watchNetwork(this)
        previewClient.prewarm()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        previewClient.shutdown()
    }

    // ---- Voice loading & filtering ----------------------------------------

    private fun loadVoices() {
        status.text = getString(R.string.loading_voices)
        thread {
            val result = runCatching { EdgeVoices.list(applicationContext) }
            runOnUiThread {
                result.onSuccess { list ->
                    allVoices = list
                    languages = list.map { it.locale }.distinct().sorted()
                    languageSpinner.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item,
                        languages.map { localeDisplay(it) }
                    )
                    // Preselect the saved voice's language.
                    val savedVoice = getSharedPreferences(EdgeTtsService.PREFS, MODE_PRIVATE)
                        .getString(EdgeTtsService.KEY_VOICE, EdgeTtsService.DEFAULT_VOICE)
                    val savedLocale = allVoices.firstOrNull { it.shortName == savedVoice }?.locale
                    val langIdx = languages.indexOf(savedLocale).takeIf { it >= 0 } ?: 0
                    languageSpinner.setSelection(langIdx)
                    status.text = resources.getQuantityString(
                        R.plurals.voices_loaded,
                        list.size,
                        list.size,
                    )
                }.onFailure {
                    status.text = getString(R.string.voices_error, it.message ?: "unknown")
                }
            }
        }
    }

    private fun onLanguageChosen(langPos: Int) {
        val locale = languages.getOrNull(langPos) ?: return
        voicesForLanguage = allVoices.filter { it.locale == locale }.sortedBy { it.friendlyName }
        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            voicesForLanguage.map { "${shortVoiceName(it)} (${it.gender})" }
        )
        // If the saved voice is in this language, preselect it.
        val savedVoice = getSharedPreferences(EdgeTtsService.PREFS, MODE_PRIVATE)
            .getString(EdgeTtsService.KEY_VOICE, EdgeTtsService.DEFAULT_VOICE)
        val idx = voicesForLanguage.indexOfFirst { it.shortName == savedVoice }
        if (idx >= 0) voiceSpinner.setSelection(idx)
    }

    private fun selectedVoice(): EdgeVoice? =
        voicesForLanguage.getOrNull(voiceSpinner.selectedItemPosition)

    // ---- Actions -----------------------------------------------------------

    private fun onTest() {
        val voice = selectedVoice() ?: return
        val text = sampleText.text?.toString()?.trim().orEmpty()
            .ifEmpty { getString(R.string.default_sample) }
        val rate = sliderToRatePercent(rateSeek.progress)
        val pitch = sliderToPitchHz(pitchSeek.progress)

        status.text = getString(R.string.testing)
        stopPreview()
        previewCancel.set(false)
        thread {
            val player = PcmPlayer(EdgeTtsClient.SAMPLE_RATE)
            previewPlayer = player
            val outcome = runCatching {
                player.start()
                previewClient.speak(
                    text = text,
                    voice = voice.shortName,
                    rate = rate,
                    pitch = pitch,
                    cancelled = previewCancel,
                ) { pcm -> player.write(pcm) }
            }
            player.stop()
            if (previewPlayer === player) previewPlayer = null
            runOnUiThread {
                status.text = outcome.fold(
                    onSuccess = { getString(R.string.test_done) },
                    onFailure = { getString(R.string.test_error, it.message ?: "unknown") }
                )
            }
        }
    }

    private fun onSetDefault() {
        val voice = selectedVoice() ?: return
        getSharedPreferences(EdgeTtsService.PREFS, MODE_PRIVATE).edit()
            .putString(EdgeTtsService.KEY_VOICE, voice.shortName)
            .putInt(EdgeTtsService.KEY_RATE_PERCENT, sliderToRatePercent(rateSeek.progress))
            .putInt(EdgeTtsService.KEY_PITCH_HZ, sliderToPitchHz(pitchSeek.progress))
            .apply()
        Toast.makeText(
            this, getString(R.string.saved, shortVoiceName(voice)), Toast.LENGTH_SHORT
        ).show()
    }

    private fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            // Fall back to general settings if the TTS action is unavailable.
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    // ---- Default-engine prompt --------------------------------------------

    private fun maybePromptSetDefault() {
        // Read the ACTUAL user-selected default engine from secure settings.
        // Note: TextToSpeech.getDefaultEngine() returns the highest-RANKED
        // engine, not the user's choice, so it can wrongly report this app when
        // our voices rank high — do not use it for this check.
        val current = android.provider.Settings.Secure.getString(
            contentResolver, "tts_default_synth"
        )
        if (current != null && current != packageName) {
            showSetDefaultDialog()
        }
    }

    private fun showSetDefaultDialog() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.set_default_title)
            .setMessage(R.string.set_default_message)
            .setPositiveButton(R.string.yes) { _, _ -> openSystemTtsSettings() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ---- Rate / pitch mapping ---------------------------------------------
    //
    // Rate uses the Google-TTS style multiplier from the NVDA add-on: slider
    // 0..100 maps to 0.5x..5x speed, stored as an Edge "+N%" percent offset.

    private fun sliderToMultiplier(slider: Int): Double = 0.5 + slider * 0.045
    private fun sliderToRatePercent(slider: Int): Int =
        ((sliderToMultiplier(slider) - 1.0) * 100).roundToInt()

    private fun ratePercentToSlider(percent: Int): Int {
        val multiplier = 1.0 + percent / 100.0
        return ((multiplier - 0.5) / 0.045).roundToInt().coerceIn(0, 100)
    }

    /** Slider 0..100 -> pitch -50Hz..+50Hz, 50 = neutral. */
    private fun sliderToPitchHz(slider: Int): Int = slider - 50
    private fun pitchHzToSlider(hz: Int): Int = (hz + 50).coerceIn(0, 100)

    private fun updateRateLabel() {
        val multiplier = sliderToMultiplier(rateSeek.progress)
        val text = getString(R.string.rate_label, "%.2fx".format(Locale.ROOT, multiplier))
        rateLabel.text = text
        rateSeek.contentDescription = text
    }

    private fun updatePitchLabel() {
        val hz = sliderToPitchHz(pitchSeek.progress)
        val text = getString(R.string.pitch_label, "%+dHz".format(Locale.ROOT, hz))
        pitchLabel.text = text
        pitchSeek.contentDescription = text
    }

    // ---- Helpers -----------------------------------------------------------

    private fun stopPreview() {
        previewCancel.set(true)
        previewPlayer?.stop()
        previewPlayer = null
    }

    private fun simpleSeekListener(onChange: () -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    /** Human label for a locale code, e.g. "en-US" -> "English (United States) — en-US". */
    private fun localeDisplay(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayName(Locale.ENGLISH)
        return if (name.isNotBlank() && name != code) "$name — $code" else code
    }

    /** "en-US-AriaNeural" -> "Aria" for compact display. */
    private fun shortVoiceName(v: EdgeVoice): String {
        val name = v.shortName.substringAfterLast("-").removeSuffix("Neural")
        return name.ifBlank { v.friendlyName }
    }
}
