package com.edgetts.engine

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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

    /** Screen plus the dropdown popup (a separate window) painted under its field. */
    private fun dropdownScreenshot(activity: Activity, field: MaterialAutoCompleteTextView, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        val popup = android.widget.AutoCompleteTextView::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(field) as android.widget.ListPopupWindow
        val list = popup.listView ?: return
        val panel = list.rootView
        val exactly = android.view.View.MeasureSpec.EXACTLY
        val unspecified = android.view.View.MeasureSpec.UNSPECIFIED
        panel.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(panel.width, exactly),
            android.view.View.MeasureSpec.makeMeasureSpec(0, unspecified),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val location = IntArray(2).also { (field.parent.parent as android.view.View).getLocationInWindow(it) }
        val anchor = field.parent.parent as android.view.View
        canvas.save()
        canvas.translate(location[0].toFloat(), (location[1] + anchor.height).toFloat())
        panel.draw(canvas)
        canvas.restore()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun popupWindow(field: MaterialAutoCompleteTextView): android.widget.PopupWindow {
        val listPopup = android.widget.AutoCompleteTextView::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(field) as android.widget.ListPopupWindow
        return android.widget.ListPopupWindow::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(listPopup) as android.widget.PopupWindow
    }

    /** Asserts the open list is exactly [rows] entries tall (plus the popup padding). */
    private fun assertVisibleRows(field: MaterialAutoCompleteTextView, rows: Int) {
        val listPopup = android.widget.AutoCompleteTextView::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(field) as android.widget.ListPopupWindow
        val rowHeight = listPopup.listView!!.getChildAt(0).height
        val padding = android.graphics.Rect().also { field.dropDownBackground.getPadding(it) }
        assertEquals("visible rows", rows * rowHeight + padding.top + padding.bottom, popupWindow(field).height)
    }

    private fun labels(field: MaterialAutoCompleteTextView): List<String> =
        (0 until field.adapter.count).map { field.adapter.getItem(it).toString() }

    private fun pick(field: MaterialAutoCompleteTextView, label: String) {
        val position = labels(field).indexOf(label)
        check(position >= 0) { "$label not in ${labels(field)}" }
        field.onItemClickListener.onItemClick(null, null, position, position.toLong())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun dropdownsShowDeviceLanguageAndSaveVoicePerLanguage() {
        val activity = Robolectric.buildActivity(EngineSettingsActivity::class.java).setup().get()
        val language = activity.findViewById<MaterialAutoCompleteTextView>(R.id.languageField)
        val voice = activity.findViewById<MaterialAutoCompleteTextView>(R.id.voiceField)
        waitUntil("voices") { language.adapter != null && voice.adapter != null }

        assertEquals("English (United States)", language.text.toString())
        assertEquals("Aria, female", voice.text.toString())
        assertEquals(
            listOf(
                "Afrikaans (South Africa)", "English (United Kingdom)", "English (United States)",
                "French (France)", "German (Germany)", "Spanish (Mexico)", "Zulu (South Africa)",
            ),
            labels(language),
        )
        assertEquals(listOf("Andrew Multilingual, male", "Aria, female", "Jenny, female"), labels(voice))
        screenshot(activity, "settings")

        pick(voice, "Jenny, female")
        assertEquals("Jenny, female", voice.text.toString())
        assertEquals("en-US-JennyNeural", VoicePrefs.voiceFor(context, "en-US", parsedCatalog()))
        // Other languages keep their own default.
        assertEquals("en-GB-RyanNeural", VoicePrefs.voiceFor(context, "en-GB", parsedCatalog()))
    }

    @Test
    fun languageDropdownFiltersByNameAndVoiceAndRevertsUnpickedText() {
        val activity = Robolectric.buildActivity(EngineSettingsActivity::class.java).setup().get()
        val language = activity.findViewById<MaterialAutoCompleteTextView>(R.id.languageField)
        val voice = activity.findViewById<MaterialAutoCompleteTextView>(R.id.voiceField)
        waitUntil("voices") { language.adapter != null }

        language.requestFocus()
        waitUntil("dropdown") { language.isPopupShowing }
        assertVisibleRows(language, 3) // 7 languages: three rows, then scroll

        language.setText("sonia")
        waitUntil("filter by voice name") { labels(language) == listOf("English (United Kingdom)") }
        waitUntil("one row") { runCatching { assertVisibleRows(language, 1) }.isSuccess }
        pick(language, "English (United Kingdom)")
        assertEquals("English (United Kingdom)", language.text.toString())
        assertEquals(listOf("Ryan, male", "Sonia, female"), labels(voice))
        assertEquals("Ryan, male", voice.text.toString())

        language.requestFocus()
        language.setText("south africa")
        waitUntil("filter by country") {
            labels(language) == listOf("Afrikaans (South Africa)", "Zulu (South Africa)")
        }
        waitUntil("two rows") { runCatching { assertVisibleRows(language, 2) }.isSuccess }
        language.showDropDown()
        shadowOf(Looper.getMainLooper()).idle()
        dropdownScreenshot(activity, language, "dropdown_search")

        language.setText("xyz")
        waitUntil("no matches") { language.adapter.count == 0 }
        voice.requestFocus() // leave without picking
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("English (United Kingdom)", language.text.toString())
        assertEquals(7, language.adapter.count)
    }

    @Test
    @Config(qualifiers = "h520dp")
    fun dropdownOpensBelowItsFieldEvenLowOnAShortScreen() {
        val activity = Robolectric.buildActivity(EngineSettingsActivity::class.java).setup().get()
        val voice = activity.findViewById<MaterialAutoCompleteTextView>(R.id.voiceField)
        val language = activity.findViewById<MaterialAutoCompleteTextView>(R.id.languageField)
        val content = activity.findViewById<android.view.View>(R.id.content)
        val bottomPadding = content.paddingBottom
        waitUntil("voices") { voice.adapter != null }
        assertTrue("a dropdown has focus at launch", !language.hasFocus() && !voice.hasFocus())

        voice.requestFocus()
        waitUntil("dropdown") { voice.isPopupShowing }
        assertEquals("blank space added below the page", bottomPadding, content.paddingBottom)
        val listPopup = android.widget.AutoCompleteTextView::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(voice) as android.widget.ListPopupWindow
        val window = android.widget.ListPopupWindow::class.java.getDeclaredField("mPopup")
            .apply { isAccessible = true }.get(listPopup) as android.widget.PopupWindow
        assertTrue("dropdown opened above the field", !window.isAboveAnchor)
        assertVisibleRows(voice, 3)
        assertTrue(voice.dropDownHeight > 0)
        dropdownScreenshot(activity, voice, "dropdown_short_screen")

        pick(voice, "Jenny, female")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("focus jumped into a dropdown", !language.hasFocus() && !voice.hasFocus())
        assertTrue("a dropdown reopened", !language.isPopupShowing && !voice.isPopupShowing)
        assertEquals(bottomPadding, content.paddingBottom)
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

}
