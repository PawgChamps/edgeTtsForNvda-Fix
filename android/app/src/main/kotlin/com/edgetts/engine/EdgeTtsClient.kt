package com.edgetts.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streams audio from the Edge read-aloud WebSocket endpoint.
 *
 * The endpoint serves compressed MP3 (raw PCM is rejected with close code 1007),
 * so we request MP3 and decode to 16-bit PCM on-device via [StreamingMp3Decoder],
 * streaming PCM out as frames arrive rather than buffering the whole utterance.
 *
 * Latency design (mirrors the NVDA add-on, which feels instant):
 *  - A background maintainer keeps a WARM POOL of two connections alive at all
 *    times — one `active` (in use / next to use) and one `spare`. Opening a
 *    fresh TLS+WS connection costs ~0.5–1s, so a request should almost never
 *    pay that: it grabs an already-open socket.
 *  - On cancel (rapid navigation / "follow me"), the active connection is
 *    retired in the background and the warm spare is promoted instantly, so the
 *    next utterance starts immediately with no reconnect stall. Retiring the old
 *    socket also guarantees no stale audio leaks into the new request.
 *  - The maintainer RECYCLES connections before they get stale, so leaving the
 *    device idle and then tapping still finds a live, young connection — no
 *    slow recovery.
 */
class EdgeTtsClient {

    companion object {
        const val SAMPLE_RATE = 24_000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=${SecMsGec.TRUSTED_CLIENT_TOKEN}"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/${SecMsGec.CHROMIUM_VERSION} Safari/537.36 " +
                "Edg/${SecMsGec.CHROMIUM_VERSION}"

        /** Edge streams the first audio within ~1s; silence past this means a dead socket. */
        private const val FIRST_AUDIO_TIMEOUT_MS = 10_000L
        private const val MAX_ATTEMPTS = 3
        /** How often the maintainer checks/refills the pool. */
        private const val MAINTAIN_INTERVAL_MS = 8_000L
        /** Recycle a connection older than this so the pool stays young/live. */
        private const val MAX_CONN_AGE_MS = 22_000L
        /**
         * Keep the pool warm only for this long after the last use. Past this,
         * the pool goes dormant (connections closed, no refilling) so we don't
         * hold network connections open when the engine isn't in use — e.g.
         * after the user switches to a different TTS engine.
         */
        private const val IDLE_KEEPALIVE_MS = 60_000L
    }

    private val http = OkHttpClient.Builder()
        .dns(FallbackDns)
        // Per-address: a short timeout lets OkHttp move on to the next IP fast.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Auto WS pings keep idle connections warm and surface a dead peer fast.
        .pingInterval(8, TimeUnit.SECONDS)
        .build()

    /** Called with each decoded PCM chunk as it is produced. */
    fun interface AudioSink {
        fun onAudio(pcm: ByteArray)
    }

    /** Per-utterance state a connection's listener routes incoming frames into. */
    private class Utterance {
        val splitter = Mp3StreamSplitter()
        val done = Object()
        @Volatile var finished = false
        @Volatile var error: Throwable? = null
        @Volatile var audioBytes = 0L

        fun signalFinished() = synchronized(done) {
            finished = true; splitter.finish(); done.notifyAll()
        }
        fun signalError(t: Throwable) = synchronized(done) {
            // After turn.end all audio is buffered locally, while playback may
            // still run for minutes; a later connection drop is harmless.
            if (finished) return@synchronized
            error = t; splitter.finish(); done.notifyAll()
        }
    }

    /** One pooled WebSocket connection with its own listener + current utterance. */
    private inner class Conn {
        @Volatile lateinit var ws: WebSocket
        @Volatile var current: Utterance? = null
        @Volatile var dead = false
        @Volatile var opened = false
        val createdAt = now()
        @Volatile var lastUsedAt = createdAt
    }

    @Volatile private var clockSkewSec = 0.0

    private val poolLock = Any()
    private var active: Conn? = null
    private var spare: Conn? = null

    private val maintainWake = Object()
    @Volatile private var shutdownFlag = false
    /** Last time speak()/prewarm() was called; drives the idle-keepalive window. */
    @Volatile private var lastActivityAt = 0L
    /** A long paragraph can play for minutes; never go idle in the middle of one. */
    @Volatile private var speaking = false

    /** Serialises speak() so the shared decoder is never used concurrently. */
    private val speakLock = Any()

    /** Reused across utterances to avoid per-call MediaCodec setup cost. */
    private val decoder = StreamingMp3Decoder()

    private val maintainer = Thread({ maintainLoop() }, "edgeTts.pool").apply {
        isDaemon = true
        start()
    }

    // ---- Connection lifecycle ---------------------------------------------

    private fun openConn(): Conn {
        val conn = Conn()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                conn.opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) conn.current?.signalFinished()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val u = conn.current ?: return
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val audioStart = 2 + headerLen
                if (audioStart >= data.size) return
                val header = String(data, 2, headerLen, Charsets.UTF_8)
                if (header.contains("Path:audio")) {
                    u.audioBytes += data.size - audioStart
                    u.splitter.feed(data, audioStart, data.size - audioStart)
                    synchronized(u.done) { u.done.notifyAll() }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) android.util.Log.w("EdgeTtsClient", "WS closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conn.dead = true
                conn.current?.let { if (!it.finished) it.signalError(EdgeTtsException("WS closed: $code $reason")) }
                onConnLost(conn)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conn.dead = true
                if (response?.code == 403) {
                    // Token is time-based: correct for device clock skew using the
                    // server's Date header, as edge-tts does, so the retry succeeds.
                    response.headers.getDate("Date")?.let {
                        clockSkewSec = (it.time - System.currentTimeMillis()) / 1000.0
                    }
                }
                val err = if (response != null) EdgeTtsException("HTTP ${response.code} from server", t) else t
                conn.current?.signalError(err)
                onConnLost(conn)
            }
        }
        conn.ws = http.newWebSocket(buildRequest(), listener)
        // speech.config must precede any SSML; OkHttp preserves send order and
        // queues until the handshake completes.
        conn.ws.send(buildConfigMessage())
        return conn
    }

    private fun onConnLost(conn: Conn) {
        synchronized(poolLock) {
            if (active === conn) active = null
            if (spare === conn) spare = null
        }
        wakeMaintainer()
    }

    /** Opens a new connection and makes it the active one, bypassing the pool. */
    private fun acquireFresh(): Conn {
        val c = openConn()
        synchronized(poolLock) { active = c }
        wakeMaintainer()
        return c
    }

    /** Returns a live connection as the active one (cold-opens only if pool empty). */
    private fun acquire(): Conn {
        synchronized(poolLock) {
            active?.takeUnless { it.dead }?.let { it.lastUsedAt = now(); return it }
            spare?.takeUnless { it.dead }?.let {
                active = it; spare = null; it.lastUsedAt = now(); return it
            }
        }
        wakeMaintainer() // refill the pool in the background
        // Cold path: nothing warm available, open synchronously.
        val c = openConn()
        synchronized(poolLock) { if (active == null) active = c else spare = spare ?: c }
        wakeMaintainer()
        return c
    }

    /** Retires a connection (background close) and clears it from the pool. */
    private fun retire(conn: Conn) {
        synchronized(poolLock) {
            if (active === conn) active = null
            if (spare === conn) spare = null
        }
        conn.dead = true
        backgroundClose(conn.ws)
        wakeMaintainer()
    }

    private fun backgroundClose(ws: WebSocket) {
        Thread { runCatching { ws.close(1000, null) }.onFailure { runCatching { ws.cancel() } } }
            .apply { isDaemon = true }.start()
    }

    // ---- Pool maintainer ---------------------------------------------------

    private fun maintainLoop() {
        while (!shutdownFlag) {
            runCatching { maintainOnce() }
            synchronized(maintainWake) {
                if (shutdownFlag) return
                // While active, poll frequently to keep the pool warm. While
                // dormant, sleep indefinitely until prewarm()/speak() wakes us.
                if (isActive()) maintainWake.wait(MAINTAIN_INTERVAL_MS)
                else maintainWake.wait()
            }
        }
    }

    /** True while within the idle-keepalive window after the last use. */
    private fun isActive(): Boolean =
        speaking || (lastActivityAt != 0L && now() - lastActivityAt < IDLE_KEEPALIVE_MS)

    private fun maintainOnce() {
        // Gone idle: drop the whole pool and stop holding connections open.
        if (!isActive()) {
            close()
            return
        }

        // Recycle stale connections so the pool stays young and live.
        val toRecycle = ArrayList<Conn>()
        synchronized(poolLock) {
            active?.let { if (it.dead || isStale(it)) { toRecycle.add(it); active = null } }
            spare?.let { if (it.dead || isStale(it)) { toRecycle.add(it); spare = null } }
        }
        toRecycle.forEach { if (!it.dead) backgroundClose(it.ws) }

        // Ensure an active connection exists (warm before the first request).
        if (needConn { active == null }) {
            val c = runCatching { openConn() }.getOrNull()
            if (c != null) synchronized(poolLock) {
                if (active == null && !shutdownFlag) active = c else backgroundClose(c.ws)
            }
        }
        // Ensure a spare exists (so cancel/idle recovery is instant).
        if (needConn { spare == null }) {
            val c = runCatching { openConn() }.getOrNull()
            if (c != null) synchronized(poolLock) {
                if (spare == null && !shutdownFlag) spare = c else backgroundClose(c.ws)
            }
        }
    }

    private inline fun needConn(check: () -> Boolean): Boolean =
        synchronized(poolLock) { !shutdownFlag && check() }

    private fun isStale(c: Conn): Boolean =
        now() - c.createdAt > MAX_CONN_AGE_MS && c.current == null

    private fun wakeMaintainer() = synchronized(maintainWake) { maintainWake.notifyAll() }

    /** Wakes the maintainer to fill the pool ahead of the first request. */
    fun prewarm() {
        lastActivityAt = now()
        wakeMaintainer()
    }

    // ---- Network changes ---------------------------------------------------

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNetwork: Network? = null

    /**
     * Drops pooled connections whenever the default network changes (VPN
     * toggled, Wi-Fi <-> mobile). Sockets opened on the old network are
     * aborted by the OS ("Software caused connection abort") or silently
     * black-holed, so reusing them only produces errors and timeouts.
     */
    fun watchNetwork(context: Context) {
        if (networkCallback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                if (previous != null && previous != network) onNetworkChanged()
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    onNetworkChanged()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess {
            connectivity = cm
            networkCallback = cb
        }
    }

    /**
     * Drops idle pooled connections only. A connection that is mid-utterance is
     * left alone: if the old network really went away it fails and is retried,
     * and if the switch was a brief Wi-Fi/mobile handover it keeps working.
     */
    private fun onNetworkChanged() {
        val idle = synchronized(poolLock) {
            val list = listOfNotNull(active, spare).filter { it.current == null }
            if (active in list) active = null
            if (spare in list) spare = null
            list
        }
        idle.forEach { it.dead = true; backgroundClose(it.ws) }
        wakeMaintainer()
    }

    /** Drops all pooled connections (keeps the maintainer + decoder alive). */
    fun close() {
        val conns = synchronized(poolLock) {
            val list = listOfNotNull(active, spare); active = null; spare = null; list
        }
        conns.forEach { backgroundClose(it.ws) }
    }

    /** Final teardown: stops the maintainer, closes connections, frees the codec. */
    fun shutdown() {
        shutdownFlag = true
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        wakeMaintainer()
        close()
        decoder.release()
    }

    // ---- Synthesis ---------------------------------------------------------

    /**
     * Synthesises [text] with [voice], streaming PCM to [sink] as it decodes.
     * Blocks until the utterance completes or [cancelled] is set. Uses a warm
     * pooled connection. On failure it re-requests the utterance on a fresh
     * connection and skips the audio already played, so a dropped connection
     * mid-paragraph costs a short pause instead of the rest of the paragraph.
     */
    fun speak(
        text: String,
        voice: String,
        rate: Int = 0,
        pitch: Int = 0,
        volume: Int = 0,
        cancelled: AtomicBoolean,
        sink: AudioSink,
    ) = synchronized(speakLock) {
        if (text.isBlank()) return
        lastActivityAt = now()
        speaking = true
        wakeMaintainer()
        try {
            speakWithRetries(buildSsmlMessage(text, voice, rate, pitch, volume), cancelled, sink)
        } finally {
            speaking = false
            // Measure the keepalive window from the END of use so rapid
            // navigation keeps the pool warm.
            lastActivityAt = now()
        }
    }

    private fun speakWithRetries(ssmlMsg: String, cancelled: AtomicBoolean, sink: AudioSink) {
        var attempt = 0
        var played = 0L // PCM bytes handed to the sink, across attempts
        while (true) {
            if (cancelled.get()) return
            val u = Utterance()
            val conn = if (attempt == 0) acquire() else acquireFresh()
            conn.current = u
            conn.ws.send(ssmlMsg)

            val start = now()
            var decoded = 0L // PCM bytes decoded in this attempt
            val decodeError: Throwable? = try {
                decoder.decode(
                    splitter = u.splitter,
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                    cancelled = cancelled,
                    errored = { u.error != null },
                    firstAudioDeadlineMs = start + FIRST_AUDIO_TIMEOUT_MS,
                ) { pcm ->
                    // A resumed attempt re-synthesises from the start; drop
                    // what was already spoken (Edge output is deterministic).
                    val skip = (played - decoded).coerceIn(0L, pcm.size.toLong()).toInt()
                    decoded += pcm.size
                    if (!cancelled.get() && skip < pcm.size) {
                        played += pcm.size - skip
                        sink.onAudio(if (skip == 0) pcm else pcm.copyOfRange(skip, pcm.size))
                    }
                }
                null
            } catch (e: Throwable) {
                e
            } finally {
                conn.current = null
            }

            if (cancelled.get()) {
                // Retire so no stale audio leaks; the warm spare becomes active
                // for the next (rapid-navigation) utterance — no reconnect stall.
                retire(conn)
                return
            }

            // turn.end with no audio (e.g. text that is only punctuation) is a
            // legitimate empty result, not a failure.
            val failed = u.error != null || decodeError != null || (decoded == 0L && !u.finished)
            if (!failed) {
                conn.lastUsedAt = now()
                return
            }

            retire(conn)
            if (attempt < MAX_ATTEMPTS - 1) {
                // The spare may be just as stale (e.g. after a network
                // change), so drop the pool and retry on a fresh socket.
                close()
                attempt++
                continue
            }
            val cause = u.error ?: decodeError
            val stage = "connected=${if (conn.opened) "yes" else "no"}, audio bytes=${u.audioBytes}"
            throw EdgeTtsException("Synthesis failed: ${cause?.message ?: "no audio"} ($stage)", cause)
        }
    }

    // ---- Message building --------------------------------------------------

    private fun buildRequest(): Request {
        val connectionId = randomHex(32)
        val secGec = SecMsGec.generate(clockSkewSec)
        val url = "$WSS_URL&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$secGec&Sec-MS-GEC-Version=${SecMsGec.SEC_MS_GEC_VERSION}"
        return Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
    }

    private fun buildConfigMessage(): String =
        "X-Timestamp:${jsTimestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"

    private fun buildSsmlMessage(
        text: String, voice: String, rate: Int, pitch: Int, volume: Int,
    ): String {
        val ssml = Ssml.build(
            text, voice,
            rate = "%+d%%".format(rate),
            pitch = "%+dHz".format(pitch),
            volume = "%+d%%".format(volume),
        )
        return "X-RequestId:${randomHex(32)}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${jsTimestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun randomHex(length: Int): String {
        val uuid = java.util.UUID.randomUUID().toString().replace("-", "")
        return uuid.substring(0, minOf(length, uuid.length))
    }

    private fun jsTimestamp(): String {
        val sdf = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            java.util.Locale.US
        )
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
