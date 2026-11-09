package com.edgetts.engine

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few synthesis failures from the system engine. Apps such as
 * e-book readers usually skip a failed utterance silently, so without this the
 * user has no way to see why text was skipped.
 */
object EngineErrorLog {
    private const val KEY = "recent_errors"
    private const val MAX_ENTRIES = 5

    fun record(context: Context, message: String) {
        val time = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        val entry = "$time  ${message.replace('\n', ' ')}"
        val entries = (listOf(entry) + read(context)).take(MAX_ENTRIES)
        prefs(context).edit().putString(KEY, entries.joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        prefs(context).getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(EdgeTtsService.PREFS, Context.MODE_PRIVATE)
}
