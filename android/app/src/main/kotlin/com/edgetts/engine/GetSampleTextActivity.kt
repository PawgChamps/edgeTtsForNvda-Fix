package com.edgetts.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Returns the sample text spoken from the TTS settings "Listen" button. */
class GetSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, "This is Microsoft Edge text to speech.")
        }
        setResult(TextToSpeech.LANG_AVAILABLE, result)
        finish()
    }
}
