package com.edgetts.engine

/** Port of `_build_ssml` / `_xml_escape` / `_expand_voice_name` from the add-on. */
object Ssml {

    private fun xmlEscape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /**
     * Expands a short voice name (e.g. "en-US-AriaNeural") into the full
     * "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)" form.
     */
    fun expandVoiceName(shortName: String): String {
        if (shortName.startsWith("Microsoft Server Speech")) return shortName
        val parts = shortName.split("-", limit = 3)
        if (parts.size >= 3) {
            val locale = "${parts[0]}-${parts[1]}"
            val name = parts[2]
            return "Microsoft Server Speech Text to Speech Voice ($locale, $name)"
        }
        return shortName
    }

    fun build(
        text: String,
        voice: String,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%",
    ): String {
        val escaped = xmlEscape(text)
        val fullVoice = expandVoiceName(voice)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$fullVoice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
            escaped +
            "</prosody></voice></speak>"
    }
}
