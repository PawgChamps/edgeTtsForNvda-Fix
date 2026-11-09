package com.edgetts.engine

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The app's main screen. The user picks a language and a voice for it from
 * searchable dropdowns; like Google TTS, each language keeps its own voice, which the
 * system engine then uses whenever an app reads that language. Rate and pitch
 * are saved as they change, and a sample can be played with the current
 * settings.
 */
class EngineSettingsActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var content: View
    private lateinit var languageField: MaterialAutoCompleteTextView
    private lateinit var voiceField: MaterialAutoCompleteTextView
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
    private var currentVoice: String? = null
    private var languageAdapter: SearchableAdapter? = null
    private var voiceAdapter: SearchableAdapter? = null
    /** The dropdown field being edited; its list is kept sized to open below it. */
    private var activeDropdown: MaterialAutoCompleteTextView? = null

    private val previewClient = EdgeTtsClient()
    private val previewCancel = AtomicBoolean(false)
    @Volatile private var previewPlayer: PcmPlayer? = null
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        scroll = findViewById(R.id.scroll)
        content = findViewById(R.id.content)
        languageField = findViewById(R.id.languageField)
        voiceField = findViewById(R.id.voiceField)
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
        applyWindowInsets()
        setUpSliders()

        setUpDropdown(languageField, { languageAdapter }, ::selectedLanguageLabel) { showLanguage(it.value) }
        setUpDropdown(voiceField, { voiceAdapter }, ::selectedVoiceLabel) { selectVoice(it.value) }
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
        languageField.setText(getString(R.string.loading_voices), false)
        thread {
            val result = runCatching { EdgeVoices.list(applicationContext) }
            runOnUiThread {
                result.onSuccess { list ->
                    catalog = list
                    val adapter = buildLanguageAdapter(list)
                    languageAdapter = adapter
                    languageField.setAdapter(adapter)
                    languageField.isEnabled = true
                    voiceField.isEnabled = true
                    showLanguage(initialLocale(list))
                }.onFailure {
                    languageField.setText(getString(R.string.voices_error, it.message ?: "unknown"), false)
                }
            }
        }
    }

    private fun buildLanguageAdapter(list: List<EdgeVoice>): SearchableAdapter {
        val byLocale = list.groupBy { it.locale }
        val options = Languages.sorted(byLocale.keys).map { code ->
            val voices = byLocale.getValue(code)
            val count = resources.getQuantityString(R.plurals.voice_count, voices.size, voices.size)
            val english = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
            val voiceNames = voices.joinToString(" ") { Languages.voiceName(it) }
            SearchableAdapter.Option(
                value = code,
                label = Languages.displayName(code),
                detail = "$code · $count",
                searchText = "$english $code $voiceNames",
            )
        }
        return SearchableAdapter(this, options)
    }

    private fun initialLocale(list: List<EdgeVoice>): String {
        val locales = list.map { it.locale }.toSet()
        VoicePrefs.selectedLocale(this)?.takeIf { it in locales }?.let { return it }
        val device = Locale.getDefault()
        runCatching { VoicePrefs.localeFor(this, device.isO3Language, device.isO3Country, list) }
            .getOrNull()?.let { return it }
        return if ("en-US" in locales) "en-US" else Languages.sorted(locales).first()
    }

    /**
     * Wires an editable exposed-dropdown: the arrow (or tapping the field)
     * lists every option, typing filters it, and picking one commits it. If
     * the user types without picking, the field reverts to the current choice.
     */
    private fun setUpDropdown(
        field: MaterialAutoCompleteTextView,
        adapter: () -> SearchableAdapter?,
        currentLabel: () -> String?,
        onPick: (SearchableAdapter.Option) -> Unit,
    ) {
        field.setOnItemClickListener { _, _, position, _ ->
            val option = adapter()?.getItem(position) ?: return@setOnItemClickListener
            onPick(option)
            finishEditing(field)
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            val a = adapter()
            if (a != null && a.count > 0 && field.text.toString() != currentLabel()) onPick(a.getItem(0))
            finishEditing(field)
            true
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeDropdown = field
                // Open the full, scrollable list straight away; typing then filters it.
                field.post {
                    if (!field.hasFocus()) return@post
                    adapter()?.showAll()
                    keepDropdownBelow(field) { if (field.hasFocus()) field.showDropDown() }
                }
            } else {
                if (activeDropdown === field) activeDropdown = null
                field.setText(currentLabel().orEmpty(), false)
                adapter()?.showAll()
            }
        }
    }

    /**
     * Pads the window for the system bars and the keyboard ourselves (edge-to-
     * edge apps are not resized for the IME), so the scroll view is exactly
     * the space above the keyboard. Each time that space changes, the open
     * dropdown is re-anchored below its field.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val field = activeDropdown ?: return@addOnLayoutChangeListener
            if (bottom - top == oldBottom - oldTop || !field.hasFocus()) return@addOnLayoutChangeListener
            field.post {
                keepDropdownBelow(field) { if (field.isPopupShowing) field.showDropDown() }
            }
        }
    }

    /**
     * Android opens a dropdown above its field whenever there is more room
     * above. Size the list to the room between the field and the bottom of
     * the visible area (above the keyboard), so it always opens underneath and
     * scrolls within that space. Only if the field is so low that fewer than
     * a few rows would fit is the page scrolled, just far enough.
     */
    private fun keepDropdownBelow(field: MaterialAutoCompleteTextView, then: () -> Unit) {
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val minimum = (160 * density).toInt()
        val fieldRect = Rect().also { field.getDrawingRect(it) }
        scroll.offsetDescendantRectToMyCoords(field, fieldRect)

        fun spaceBelow() = scroll.height - (fieldRect.bottom - scroll.scrollY) - gap
        if (spaceBelow() < minimum) {
            val maxScroll = (content.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, (scroll.scrollY + minimum - spaceBelow()).coerceAtMost(maxScroll))
        }
        field.dropDownHeight = spaceBelow().coerceAtLeast((56 * density).toInt())
        then()
    }

    private fun finishEditing(field: MaterialAutoCompleteTextView) {
        field.dismissDropDown()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        // clearFocus() alone hands focus to the first editable field, which
        // would reopen a dropdown; park it on the page container instead.
        content.requestFocus()
    }

    private fun selectedLanguageLabel(): String? = currentLocale?.let { Languages.displayName(it) }

    private fun selectedVoiceLabel(): String? =
        catalog.firstOrNull { it.shortName == currentVoice }?.let { voiceLabel(it) }

    private fun voiceLabel(voice: EdgeVoice): String =
        getString(R.string.voice_entry, Languages.voiceName(voice), genderLabel(voice.gender))

    private fun showLanguage(code: String) {
        currentLocale = code
        VoicePrefs.setSelectedLocale(this, code)
        languageAdapter?.let { it.selectedLabel = selectedLanguageLabel(); it.showAll() }
        languageField.setText(selectedLanguageLabel(), false)

        val voices = catalog.filter { it.locale == code }.sortedBy { Languages.voiceName(it) }
        currentVoice = VoicePrefs.voiceFor(this, code, voices)
        val adapter = SearchableAdapter(this, voices.map {
            SearchableAdapter.Option(it.shortName, voiceLabel(it), null, it.shortName)
        }).also { it.selectedLabel = selectedVoiceLabel() }
        voiceAdapter = adapter
        voiceField.setAdapter(adapter)
        voiceField.setText(selectedVoiceLabel(), false)
    }

    private fun selectVoice(shortName: String) {
        val locale = currentLocale ?: return
        currentVoice = shortName
        VoicePrefs.setVoiceFor(this, locale, shortName)
        voiceAdapter?.let { it.selectedLabel = selectedVoiceLabel(); it.showAll() }
        voiceField.setText(selectedVoiceLabel(), false)
    }

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
        val voice = currentVoice ?: return
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
