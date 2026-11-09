package com.edgetts.engine

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The app's main screen. The user picks a language (searchable list) and a
 * voice for it; like Google TTS, each language keeps its own voice, which the
 * system engine then uses whenever an app reads that language. Rate and pitch
 * are saved as they change, and a sample can be played with the current
 * settings.
 */
class EngineSettingsActivity : AppCompatActivity() {

    private lateinit var languageCard: View
    private lateinit var languageName: TextView
    private lateinit var languageCode: TextView
    private lateinit var voiceHeading: TextView
    private lateinit var voiceGroup: RadioGroup
    private lateinit var rateSlider: Slider
    private lateinit var pitchSlider: Slider
    private lateinit var rateLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var sampleText: TextInputEditText
    private lateinit var playButton: Button
    private lateinit var status: TextView
    private lateinit var defaultEngineCard: View
    private lateinit var errorsCard: View
    private lateinit var errorsText: TextView

    private var catalog: List<EdgeVoice> = emptyList()
    private var currentLocale: String? = null

    private val previewClient = EdgeTtsClient()
    private val previewCancel = AtomicBoolean(false)
    @Volatile private var previewPlayer: PcmPlayer? = null
    private var playing = false

    private val pickLanguage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra(LanguagePickerActivity.EXTRA_LOCALE)?.let { showLanguage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        languageCard = findViewById(R.id.languageCard)
        languageName = findViewById(R.id.languageName)
        languageCode = findViewById(R.id.languageCode)
        voiceHeading = findViewById(R.id.voiceHeading)
        voiceGroup = findViewById(R.id.voiceGroup)
        rateSlider = findViewById(R.id.rateSlider)
        pitchSlider = findViewById(R.id.pitchSlider)
        rateLabel = findViewById(R.id.rateLabel)
        pitchLabel = findViewById(R.id.pitchLabel)
        sampleText = findViewById(R.id.sampleText)
        playButton = findViewById(R.id.playButton)
        status = findViewById(R.id.statusText)
        defaultEngineCard = findViewById(R.id.defaultEngineCard)
        errorsCard = findViewById(R.id.errorsCard)
        errorsText = findViewById(R.id.errorsText)

        sampleText.setText(getString(R.string.default_sample))
        setUpSliders()

        languageCard.isEnabled = false
        languageCard.setOnClickListener {
            pickLanguage.launch(
                Intent(this, LanguagePickerActivity::class.java)
                    .putExtra(LanguagePickerActivity.EXTRA_LOCALE, currentLocale)
            )
        }
        playButton.setOnClickListener { if (playing) stopPreview() else onPlay() }
        findViewById<Button>(R.id.systemTtsButton).setOnClickListener { openSystemTtsSettings() }
        findViewById<Button>(R.id.makeDefaultButton).setOnClickListener { openSystemTtsSettings() }
        findViewById<Button>(R.id.clearErrorsButton).setOnClickListener {
            EngineErrorLog.clear(this)
            refreshErrors()
        }

        loadVoices()
        // Warm the connection so the first sample doesn't pay the handshake.
        previewClient.watchNetwork(this)
        previewClient.prewarm()
    }

    override fun onResume() {
        super.onResume()
        refreshDefaultEngineBanner()
        refreshErrors()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        previewClient.shutdown()
    }

    // ---- Language & voice --------------------------------------------------

    private fun loadVoices() {
        thread {
            val result = runCatching { EdgeVoices.list(applicationContext) }
            runOnUiThread {
                result.onSuccess { list ->
                    catalog = list
                    languageCard.isEnabled = true
                    showLanguage(initialLocale(list))
                }.onFailure {
                    languageName.text = getString(R.string.voices_error, it.message ?: "unknown")
                }
            }
        }
    }

    private fun initialLocale(list: List<EdgeVoice>): String {
        val locales = list.map { it.locale }.toSet()
        VoicePrefs.selectedLocale(this)?.takeIf { it in locales }?.let { return it }
        val device = Locale.getDefault()
        runCatching { VoicePrefs.localeFor(this, device.isO3Language, device.isO3Country, list) }
            .getOrNull()?.let { return it }
        return if ("en-US" in locales) "en-US" else Languages.sorted(locales).first()
    }

    private fun showLanguage(code: String) {
        currentLocale = code
        VoicePrefs.setSelectedLocale(this, code)
        val name = Languages.displayName(code)
        languageName.text = name
        languageCode.text = code
        languageCard.contentDescription = getString(R.string.language_description, name, code)
        voiceHeading.text = getString(R.string.voice_for, name)

        val voices = catalog.filter { it.locale == code }.sortedBy { Languages.voiceName(it) }
        val chosen = VoicePrefs.voiceFor(this, code, voices)

        voiceGroup.setOnCheckedChangeListener(null)
        voiceGroup.removeAllViews()
        val rowHeight = (48 * resources.displayMetrics.density).roundToInt()
        for (voice in voices) {
            val button = MaterialRadioButton(this).apply {
                id = View.generateViewId()
                tag = voice.shortName
                text = getString(R.string.voice_entry, Languages.voiceName(voice), genderLabel(voice.gender))
                minHeight = rowHeight
            }
            voiceGroup.addView(button)
            if (voice.shortName == chosen) voiceGroup.check(button.id)
        }
        voiceGroup.setOnCheckedChangeListener { group, checkedId ->
            val voice = group.findViewById<View>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            VoicePrefs.setVoiceFor(this, code, voice)
        }
    }

    private fun selectedVoice(): String? =
        voiceGroup.findViewById<View>(voiceGroup.checkedRadioButtonId)?.tag as? String

    private fun genderLabel(gender: String): String = when (gender.lowercase(Locale.ROOT)) {
        "female" -> getString(R.string.gender_female)
        "male" -> getString(R.string.gender_male)
        else -> gender
    }

    // ---- Rate & pitch ------------------------------------------------------
    //
    // Rate uses the Google-TTS style multiplier from the NVDA add-on: slider
    // 0..100 maps to 0.5x..5x speed, stored as an Edge "+N%" percent offset.

    private fun setUpSliders() {
        val prefs = getSharedPreferences(EdgeTtsService.PREFS, MODE_PRIVATE)
        rateSlider.value = ratePercentToSlider(prefs.getInt(EdgeTtsService.KEY_RATE_PERCENT, 0)).toFloat()
        pitchSlider.value = pitchHzToSlider(prefs.getInt(EdgeTtsService.KEY_PITCH_HZ, 0)).toFloat()
        rateSlider.setLabelFormatter { rateText(it.roundToInt()) }
        pitchSlider.setLabelFormatter { pitchText(it.roundToInt()) }
        updateRateLabel()
        updatePitchLabel()

        rateSlider.addOnChangeListener { _, value, fromUser ->
            updateRateLabel()
            if (fromUser) prefs.edit()
                .putInt(EdgeTtsService.KEY_RATE_PERCENT, sliderToRatePercent(value.roundToInt())).apply()
        }
        pitchSlider.addOnChangeListener { _, value, fromUser ->
            updatePitchLabel()
            if (fromUser) prefs.edit()
                .putInt(EdgeTtsService.KEY_PITCH_HZ, sliderToPitchHz(value.roundToInt())).apply()
        }
    }

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

    private fun rateText(slider: Int) = "%.2fx".format(Locale.ROOT, sliderToMultiplier(slider))
    private fun pitchText(slider: Int) = "%+dHz".format(Locale.ROOT, sliderToPitchHz(slider))

    private fun updateRateLabel() {
        rateLabel.text = getString(R.string.rate_label, rateText(rateSlider.value.roundToInt()))
    }

    private fun updatePitchLabel() {
        pitchLabel.text = getString(R.string.pitch_label, pitchText(pitchSlider.value.roundToInt()))
    }

    // ---- Sample playback ---------------------------------------------------

    private fun onPlay() {
        val voice = selectedVoice() ?: return
        val text = sampleText.text?.toString()?.trim().orEmpty()
            .ifEmpty { getString(R.string.default_sample) }
        val rate = sliderToRatePercent(rateSlider.value.roundToInt())
        val pitch = sliderToPitchHz(pitchSlider.value.roundToInt())

        stopPreview()
        previewCancel.set(false)
        setPlaying(true)
        setStatus(getString(R.string.testing))
        thread {
            val player = PcmPlayer(EdgeTtsClient.SAMPLE_RATE)
            previewPlayer = player
            val outcome = runCatching {
                player.start()
                previewClient.speak(
                    text = text,
                    voice = voice,
                    rate = rate,
                    pitch = pitch,
                    cancelled = previewCancel,
                ) { pcm -> player.write(pcm) }
            }
            player.stop()
            if (previewPlayer === player) previewPlayer = null
            runOnUiThread {
                setPlaying(false)
                setStatus(outcome.fold(
                    onSuccess = { if (previewCancel.get()) "" else getString(R.string.test_done) },
                    onFailure = { getString(R.string.test_error, it.message ?: "unknown") }
                ))
            }
        }
    }

    private fun stopPreview() {
        previewCancel.set(true)
        previewPlayer?.stop()
        previewPlayer = null
    }

    private fun setStatus(text: String) {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        playButton.setText(if (value) R.string.stop else R.string.play_sample)
    }

    // ---- System engine & diagnostics ---------------------------------------

    private fun refreshDefaultEngineBanner() {
        // Read the ACTUAL user-selected default engine from secure settings.
        // TextToSpeech.getDefaultEngine() returns the highest-RANKED engine,
        // not the user's choice, so it can wrongly report this app.
        val current = android.provider.Settings.Secure.getString(contentResolver, "tts_default_synth")
        defaultEngineCard.visibility =
            if (current != null && current != packageName) View.VISIBLE else View.GONE
    }

    private fun refreshErrors() {
        val errors = EngineErrorLog.read(this)
        errorsCard.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        errorsText.text = errors.joinToString("\n")
    }

    private fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            // Fall back to general settings if the TTS action is unavailable.
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }
}
