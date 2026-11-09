package com.edgetts.engine

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val voices = listOf(
        Triple("en-US-AriaNeural", "en-US", "Female"),
        Triple("en-US-AndrewMultilingualNeural", "en-US", "Male"),
        Triple("en-US-JennyNeural", "en-US", "Female"),
        Triple("en-GB-SoniaNeural", "en-GB", "Female"),
        Triple("en-GB-RyanNeural", "en-GB", "Male"),
        Triple("fr-FR-DeniseNeural", "fr-FR", "Female"),
        Triple("de-DE-KatjaNeural", "de-DE", "Female"),
        Triple("af-ZA-AdriNeural", "af-ZA", "Female"),
        Triple("zu-ZA-ThandoNeural", "zu-ZA", "Female"),
        Triple("es-MX-DaliaNeural", "es-MX", "Female"),
    )

    @Before
    fun seedVoiceCache() {
        val json = voices.joinToString(",", "[", "]") { (name, locale, gender) ->
            """{"ShortName":"$name","Locale":"$locale","Gender":"$gender","FriendlyName":"$name"}"""
        }
        File(context.filesDir, "voices_cache.json").writeText(json)
        context.getSharedPreferences(EdgeTtsService.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun screenshot(activity: Activity, name: String) {
        val root = activity.window.decorView
        root.jumpDrawablesToCurrentState() // finish check-mark animations
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun settingsShowsDeviceLanguageAndSavesVoicePerLanguage() {
        val activity = Robolectric.buildActivity(EngineSettingsActivity::class.java).setup().get()
        val languageName = activity.findViewById<TextView>(R.id.languageName)
        val group = activity.findViewById<RadioGroup>(R.id.voiceGroup)
        waitUntil("voices") { group.childCount > 0 }

        assertEquals("English (United States)", languageName.text.toString())
        val labels = (0 until group.childCount).map { (group.getChildAt(it) as RadioButton).text.toString() }
        assertEquals(listOf("Andrew Multilingual, male", "Aria, female", "Jenny, female"), labels)
        val checked = activity.findViewById<RadioButton>(group.checkedRadioButtonId)
        assertEquals("Aria, female", checked.text.toString())
        screenshot(activity, "settings")

        (group.getChildAt(2) as RadioButton).performClick()
        assertEquals("en-US-JennyNeural", VoicePrefs.voiceFor(context, "en-US", parsedCatalog()))
        // Other languages keep their own default.
        assertEquals("en-GB-RyanNeural", VoicePrefs.voiceFor(context, "en-GB", parsedCatalog()))
    }

    @Test
    fun languagePickerIsAlphabeticalAndSearchable() {
        val activity = Robolectric.buildActivity(LanguagePickerActivity::class.java).setup().get()
        val list = activity.findViewById<ListView>(R.id.languageList)
        waitUntil("languages") { list.adapter.count > 0 }

        val names = (0 until list.adapter.count).map { (list.adapter.getItem(it) as Any).let(::entryName) }
        assertEquals(
            listOf(
                "Afrikaans (South Africa)", "English (United Kingdom)", "English (United States)",
                "French (France)", "German (Germany)", "Spanish (Mexico)", "Zulu (South Africa)",
            ),
            names,
        )
        screenshot(activity, "picker")

        val search = activity.findViewById<TextInputEditText>(R.id.searchField)
        search.setText("sonia")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("English (United Kingdom)"), shownNames(list))

        search.setText("FRANCAIS fr")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), shownNames(list))

        search.setText("south africa")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Afrikaans (South Africa)", "Zulu (South Africa)"), shownNames(list))
        screenshot(activity, "picker_search")
    }

    @Test
    fun engineMapsRequestsToPerLanguageChoice() {
        val catalog = parsedCatalog()
        assertEquals("en-US", VoicePrefs.localeFor(context, "eng", "USA", catalog))
        assertEquals("en-GB", VoicePrefs.localeFor(context, "eng", "GBR", catalog))
        assertEquals("en-US", VoicePrefs.localeFor(context, "eng", "", catalog))
        assertEquals("fr-FR", VoicePrefs.localeFor(context, "fra", null, catalog))
        assertEquals(null, VoicePrefs.localeFor(context, "jpn", "JPN", catalog))

        VoicePrefs.setVoiceFor(context, "en-GB", "en-GB-SoniaNeural")
        assertEquals("en-GB-SoniaNeural", VoicePrefs.voiceFor(context, "en-GB", catalog))
        assertEquals("en-US-AriaNeural", VoicePrefs.voiceFor(context, "en-US", catalog))

        VoicePrefs.rememberIssuedDefault(context, "en-US-AriaNeural")
        assertTrue(VoicePrefs.wasIssuedDefault(context, "en-US-AriaNeural"))
        assertTrue(!VoicePrefs.wasIssuedDefault(context, "en-US-JennyNeural"))
    }

    private fun parsedCatalog(): List<EdgeVoice> = EdgeVoices.cachedOnly(context)

    private fun shownNames(list: ListView): List<String> =
        (0 until list.adapter.count).map { entryName(list.adapter.getItem(it)) }

    private fun entryName(entry: Any): String =
        entry.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(entry) as String

    @Suppress("unused")
    private fun visible(view: View) = view.visibility == View.VISIBLE
}
