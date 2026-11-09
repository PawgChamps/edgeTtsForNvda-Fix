package com.edgetts.engine

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlin.concurrent.thread

/** Searchable, alphabetical list of languages. Returns the chosen locale code. */
class LanguagePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOCALE = "locale"
    }

    private class Entry(
        val code: String,
        val name: String,
        val subtitle: String,
        val searchKey: String,
    )

    private lateinit var list: ListView
    private lateinit var resultCount: TextView
    private var entries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var current: String? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_language, parent, false)
            val entry = shown[position]
            val selected = entry.code == current
            view.findViewById<TextView>(R.id.title).text = entry.name
            view.findViewById<TextView>(R.id.subtitle).text = entry.subtitle
            view.findViewById<View>(R.id.selectedMark).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
            view.contentDescription = buildString {
                append(entry.name).append(", ").append(entry.subtitle)
                if (selected) append(", ").append(getString(R.string.selected))
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_picker)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        current = intent.getStringExtra(EXTRA_LOCALE)
        list = findViewById(R.id.languageList)
        resultCount = findViewById(R.id.resultCount)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LOCALE, shown[position].code))
            finish()
        }

        val search = findViewById<TextInputEditText>(R.id.searchField)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        thread {
            val catalog = runCatching { EdgeVoices.list(applicationContext) }.getOrElse { emptyList() }
            val built = buildEntries(catalog)
            runOnUiThread {
                entries = built
                filter(search.text?.toString().orEmpty())
                shown.indexOfFirst { it.code == current }
                    .takeIf { it >= 0 }
                    ?.let { list.setSelection(maxOf(0, it - 2)) }
            }
        }
    }

    private fun buildEntries(catalog: List<EdgeVoice>): List<Entry> {
        val byLocale = catalog.groupBy { it.locale }
        return Languages.sorted(byLocale.keys).map { code ->
            val voices = byLocale.getValue(code)
            val name = Languages.displayName(code)
            val chosen = VoicePrefs.voiceFor(this, code, voices)
                ?.let { short -> voices.firstOrNull { it.shortName == short } }
                ?.let { Languages.voiceName(it) }
            val count = resources.getQuantityString(R.plurals.voice_count, voices.size, voices.size)
            val subtitle = listOfNotNull(code, count, chosen).joinToString(" · ")
            val english = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
            val key = (listOf(name, english, code) + voices.map { Languages.voiceName(it) })
                .joinToString(" ")
            Entry(code, name, subtitle, Languages.searchKey(key))
        }
    }

    private fun filter(query: String) {
        val terms = Languages.searchKey(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        shown = if (terms.isEmpty()) entries else entries.filter { e -> terms.all { it in e.searchKey } }
        adapter.notifyDataSetChanged()
        resultCount.text = if (entries.isEmpty()) getString(R.string.loading_voices) else
            resources.getQuantityString(R.plurals.language_count, shown.size, shown.size)
    }
}
