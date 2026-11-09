package com.edgetts.engine

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Human-readable names and ordering for Edge locales and voices. */
object Languages {

    /** "en-US" -> "English (United States)" in the device's language. */
    fun displayName(code: String): String {
        val locale = Locale.getDefault()
        val name = Locale.forLanguageTag(code).getDisplayName(locale)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return code
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** Locale codes ordered alphabetically by display name. */
    fun sorted(codes: Collection<String>): List<String> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return codes.sortedWith(compareBy(collator) { displayName(it) })
    }

    /** "en-US-AndrewMultilingualNeural" -> "Andrew Multilingual". */
    fun voiceName(voice: EdgeVoice): String {
        val raw = voice.shortName.removePrefix(voice.locale + "-")
            .substringAfterLast('-')
            .removeSuffix("Neural")
        val spaced = raw.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        return spaced.ifBlank { voice.friendlyName }
    }

    /** Lower-cased, accent-free text for search matching. */
    fun searchKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
}
