package com.edgetts.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Minimal blocking PCM player used only for the settings-screen preview. */
class PcmPlayer(sampleRate: Int) {

    private val minBuf = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(minBuf)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    fun start() = track.play()

    fun write(pcm: ByteArray) {
        var off = 0
        while (off < pcm.size) {
            val n = track.write(pcm, off, pcm.size - off)
            if (n <= 0) break
            off += n
        }
    }

    fun stop() {
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
