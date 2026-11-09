package com.edgetts.engine

import android.media.MediaCodec
import android.media.MediaFormat
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Splits a stream of MP3 bytes (arriving incrementally over the WebSocket) into
 * complete MP3 frames. MediaCodec's MP3 decoder requires each input buffer to
 * contain whole frames — feeding it arbitrary byte splits fails with err -14 —
 * so we parse frame headers and only ever hand it aligned frames.
 *
 * Edge sends CBR MPEG-2 Layer III (24 kHz, 48 kbps, mono), but the parser is
 * generic across MPEG 1/2/2.5 Layer III.
 */
class Mp3StreamSplitter {

    private val lock = Any()
    private val frames = ArrayDeque<ByteArray>()
    private var carry = ByteArray(0)
    private var finished = false

    private val bitrateMpeg1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val bitrateMpeg2L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val sampleRates = arrayOf(
        intArrayOf(11025, 12000, 8000),   // MPEG 2.5
        intArrayOf(0, 0, 0),              // reserved
        intArrayOf(22050, 24000, 16000),  // MPEG 2
        intArrayOf(44100, 48000, 32000),  // MPEG 1
    )

    /** Appends newly-received MP3 bytes and extracts any newly-complete frames. */
    fun feed(data: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            val input = if (carry.isEmpty()) {
                data.copyOfRange(off, off + len)
            } else {
                ByteArray(carry.size + len).also {
                    System.arraycopy(carry, 0, it, 0, carry.size)
                    System.arraycopy(data, off, it, carry.size, len)
                }
            }
            var p = 0
            while (true) {
                val sync = findSync(input, p)
                if (sync < 0) { p = input.size; break }
                if (sync + 4 > input.size) { p = sync; break }      // need full header
                val frameLen = frameLength(input, sync)
                if (frameLen <= 0) { p = sync + 1; continue }       // false sync
                if (sync + frameLen > input.size) { p = sync; break } // incomplete frame
                frames.add(input.copyOfRange(sync, sync + frameLen))
                p = sync + frameLen
            }
            carry = if (p >= input.size) ByteArray(0) else input.copyOfRange(p, input.size)
        }
    }

    fun finish() = synchronized(lock) { finished = true }

    /** Removes and returns the next complete frame, or null if none ready yet. */
    fun poll(): ByteArray? = synchronized(lock) { frames.poll() }

    fun hasFrames(): Boolean = synchronized(lock) { frames.isNotEmpty() }

    /** True once the source is done and every buffered frame has been polled. */
    fun isDrained(): Boolean = synchronized(lock) { finished && frames.isEmpty() }

    /** Scans for an MP3 frame sync: 0xFF followed by 0b111xxxxx. */
    private fun findSync(buf: ByteArray, from: Int): Int {
        var i = from
        while (i < buf.size - 1) {
            if (buf[i].toInt() and 0xFF == 0xFF && (buf[i + 1].toInt() and 0xE0) == 0xE0) return i
            i++
        }
        return -1
    }

    /** Computes frame length from a header at [pos], or <=0 if the header is invalid. */
    private fun frameLength(buf: ByteArray, pos: Int): Int {
        val b1 = buf[pos + 1].toInt() and 0xFF
        val b2 = buf[pos + 2].toInt() and 0xFF
        val versionId = (b1 shr 3) and 0x03      // 0=2.5, 2=2, 3=1
        val layer = (b1 shr 1) and 0x03           // 1 == Layer III
        if (layer != 0x01) return -1
        val bitrateIdx = (b2 shr 4) and 0x0F
        val sampleIdx = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (bitrateIdx == 0 || bitrateIdx == 15 || sampleIdx == 3 || versionId == 1) return -1

        val isMpeg1 = versionId == 3
        val bitrate = (if (isMpeg1) bitrateMpeg1L3 else bitrateMpeg2L3)[bitrateIdx] * 1000
        val sampleRate = sampleRates[versionId][sampleIdx]
        if (bitrate == 0 || sampleRate == 0) return -1

        // Layer III frame length in bytes.
        return if (isMpeg1) {
            144 * bitrate / sampleRate + padding
        } else {
            72 * bitrate / sampleRate + padding
        }
    }
}

/**
 * Decodes streaming MP3 to 16-bit PCM using a PERSISTENT MediaCodec that is
 * reused (flushed) across utterances, avoiding the ~100ms create/configure/start
 * cost per utterance. Output PCM is emitted in chunks as it is produced, so
 * playback can begin while later audio is still downloading.
 */
class StreamingMp3Decoder {

    private companion object {
        const val MIME = "audio/mpeg"
        const val TIMEOUT_US = 5_000L
        /** Frames are waiting but the codec has accepted no input for this long. */
        const val INPUT_STALL_MS = 2_000L
    }

    private var codec: MediaCodec? = null
    private var format: MediaFormat? = null

    private fun ensureCodec(sampleRate: Int, channels: Int): MediaCodec {
        codec?.let { return it }
        val f = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
        val c = MediaCodec.createDecoderByType(MIME)
        c.configure(f, null, null, 0)
        c.start()
        codec = c
        format = f
        return c
    }

    /**
     * Readies the codec for the next utterance. Some vendor MP3 decoders stop
     * accepting input after flush() following end-of-stream, so a full
     * stop/configure/start cycle is used instead; on any failure the codec is
     * released and recreated lazily.
     */
    private fun resetAfterEos(c: MediaCodec) {
        val f = format
        val ok = f != null && runCatching {
            c.stop()
            c.configure(f, null, null, 0)
            c.start()
        }.isSuccess
        if (!ok) release()
    }

    /**
     * Pumps frames from [splitter] through the decoder until the splitter is
     * drained (or [cancelled] / [errored] / the deadline). Emits PCM via [onPcm].
     */
    fun decode(
        splitter: Mp3StreamSplitter,
        sampleRate: Int,
        channels: Int,
        cancelled: AtomicBoolean,
        errored: () -> Boolean,
        deadlineMs: Long,
        firstAudioDeadlineMs: Long = deadlineMs,
        onPcm: (ByteArray) -> Unit,
    ) {
        val c = try {
            ensureCodec(sampleRate, channels)
        } catch (e: Exception) {
            throw EdgeTtsException("MP3 decoder init failed: ${e.message}", e)
        }

        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var pts = 0L
        var receivedAudio = false
        var inputStallSince = 0L
        var failed = true
        // An input buffer we own but have not queued yet. It must be held until
        // audio arrives: dropping it leaks it, and after a few network waits the
        // codec has no input buffers left and stalls for good.
        var heldInput = -1

        try {
            while (!outputEos) {
                if (cancelled.get() || errored()) break
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (!receivedAudio && (pendingFrame != null || splitter.hasFrames())) receivedAudio = true
                if (nowMs > deadlineMs) {
                    throw EdgeTtsException("Synthesis timeout")
                }
                if (!receivedAudio && nowMs > firstAudioDeadlineMs) {
                    throw EdgeTtsException("No audio received from server")
                }

                // ---- feed input (whole frames only, packed per buffer) ----
                if (!inputEos) {
                    if (heldInput < 0) heldInput = c.dequeueInputBuffer(TIMEOUT_US)
                    val inIdx = heldInput
                    if (inIdx >= 0) {
                        inputStallSince = 0L
                        // Only remove audio from the queue once the codec has
                        // accepted an input buffer. A temporary dequeue timeout
                        // must not drop or reorder MP3 frames.
                        val frame = pendingFrame?.also { pendingFrame = null }
                            ?: splitter.poll()
                        if (frame != null) {
                            receivedAudio = true
                            val ib = c.getInputBuffer(inIdx)!!
                            ib.clear()
                            ib.put(frame)
                            // Pack any further ready frames that still fit.
                            var written = frame.size
                            while (true) {
                                val next = splitter.poll() ?: break
                                if (written + next.size > ib.capacity()) {
                                    // Put it back by feeding a fresh buffer next round.
                                    pendingFrame = next
                                    break
                                }
                                ib.put(next); written += next.size
                            }
                            c.queueInputBuffer(inIdx, 0, written, pts, 0)
                            heldInput = -1
                            pts += 24_000L
                        } else if (splitter.isDrained()) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            heldInput = -1
                            inputEos = true
                        } else {
                            // Awaiting more network data; keep holding the buffer.
                            Thread.sleep(2)
                        }
                    } else if (pendingFrame != null || splitter.hasFrames()) {
                        if (inputStallSince == 0L) {
                            inputStallSince = nowMs
                        } else if (nowMs - inputStallSince > INPUT_STALL_MS) {
                            throw EdgeTtsException("MP3 decoder stalled")
                        }
                    } else if (!splitter.isDrained()) {
                        // Awaiting more network data.
                        Thread.sleep(2)
                    }
                }

                // ---- drain output ----
                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0 && !cancelled.get()) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        val pcm = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(pcm, 0, info.size)
                        onPcm(pcm)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
            }
            failed = false
        } finally {
            pendingFrame = null
            when {
                failed -> release()
                outputEos -> resetAfterEos(c)
                // Cancelled/errored mid-stream: no EOS was queued, so flush is safe.
                else -> if (runCatching { c.flush() }.isFailure) release()
            }
        }
    }

    private var pendingFrame: ByteArray? = null

    fun release() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }
}
