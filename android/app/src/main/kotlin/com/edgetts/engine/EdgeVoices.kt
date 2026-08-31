package com.edgetts.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** A single Edge voice, distilled from the voices/list JSON. */
data class EdgeVoice(
    val shortName: String,   // e.g. "en-US-AriaNeural"
    val locale: String,      // e.g. "en-US"
    val gender: String,      // "Male" / "Female"
    val friendlyName: String // human-readable
)

/** Port of `list_voices` from the add-on, with an on-disk cache. */
object EdgeVoices {

    private const val VOICES_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=${SecMsGec.TRUSTED_CLIENT_TOKEN}"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/${SecMsGec.CHROMIUM_VERSION} Safari/537.36 " +
            "Edg/${SecMsGec.CHROMIUM_VERSION}"

    private const val CACHE_FILE = "voices_cache.json"
    private const val CACHE_MAX_AGE_MS = 86_400_000L // 24h, matches _VOICE_CACHE_MAX_AGE

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Reads voices from the on-disk cache only. Never touches the network, so
     *  it is safe to call on the main thread (e.g. from CheckVoiceDataActivity). */
    fun cachedOnly(context: Context): List<EdgeVoice> {
        val cache = File(context.filesDir, CACHE_FILE)
        if (!cache.isFile) return emptyList()
        return runCatching { parse(cache.readText()) }.getOrElse { emptyList() }
    }

    fun list(context: Context, forceRefresh: Boolean = false): List<EdgeVoice> {
        val cache = File(context.filesDir, CACHE_FILE)
        if (!forceRefresh && cache.isFile &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
        ) {
            runCatching { return parse(cache.readText()) }
        }

        val secGec = SecMsGec.generate()
        val url = "$VOICES_URL&Sec-MS-GEC=$secGec&Sec-MS-GEC-Version=${SecMsGec.SEC_MS_GEC_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val body = try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("empty body")
            }
        } catch (e: Exception) {
            // Fall back to any stale cache, matching the Python behaviour.
            if (cache.isFile) runCatching { return parse(cache.readText()) }
            throw EdgeTtsException("Failed to fetch voice list: ${e.message}", e)
        }

        runCatching { cache.writeText(body) }
        return parse(body)
    }

    private fun parse(json: String): List<EdgeVoice> {
        val arr = JSONArray(json)
        val out = ArrayList<EdgeVoice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                EdgeVoice(
                    shortName = o.optString("ShortName"),
                    locale = o.optString("Locale"),
                    gender = o.optString("Gender"),
                    friendlyName = o.optString("FriendlyName")
                )
            )
        }
        return out
    }
}
