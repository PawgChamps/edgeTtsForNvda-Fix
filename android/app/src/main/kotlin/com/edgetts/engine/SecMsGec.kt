package com.edgetts.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * V2 Sec-MS-GEC token generation for the Edge read-aloud WebSocket.
 *
 * Ported from rany2/edge-tts v7.3.1 (src/edge_tts/drm.py, PR #492).
 * The v1 SHA-256-only token is rejected with HTTP 403 since ~2024-10-10;
 * the v2 token combines a custom SHA-256 digest with a CS64 digest in
 * the format "<16 hex CS64>-<64 hex SHA256>".
 */
object SecMsGec {

    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_VERSION = "143.0.3650.75"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_VERSION"

    private const val WIN_EPOCH_OFFSET = 11644473600.0
    private const val S_TO_NS = 1e9
    private const val M = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )
    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val SHA256_BLOCK_CONSTANTS: LongArray = run {
        val arr = SHA256_ROUND_CONSTANTS.copyOf()
        arr[14] = SHA256_ADJUSTMENT
        arr
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L),
        longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L),
        longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L),
        longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L),
        longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL),
        longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L),
        longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
        longArrayOf(0xCB49E3C6L, 0xC5626167L),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    @Volatile
    private var cachedInitKey: ByteArray? = null

    @Volatile
    private var cachedInitState: LongArray? = null

    @Volatile
    private var cachedTokenHashKey: ByteArray? = null

    @Volatile
    private var cachedTokenHashResult: Pair<String, String>? = null

    fun generate(clockSkew: Double = 0.0): String {
        var ticks = System.currentTimeMillis() / 1000.0 + clockSkew
        ticks += WIN_EPOCH_OFFSET
        ticks -= ticks % 300.0
        ticks *= S_TO_NS / 100.0
        val ticksStr = String.format(Locale.ROOT, "%.0f", ticks)
        val strToHash = "$ticksStr$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val (cs64, sha256) = tokenHash(strToHash)
        return "$cs64-$sha256"
    }

    private fun rol16(value: Long): Long {
        val v = value and M
        return ((v shl 16) or (v ushr 16)) and M
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val padLen = (4 - data.size % 4) % 4
        val padded = if (padLen > 0) data + ByteArray(padLen) else data
        if (padded.isEmpty()) return LongArray(0)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        var count = padded.size / 4
        if (count % 2 != 0) count++
        val words = LongArray(count)
        for (i in 0 until padded.size / 4) {
            words[i] = buf.getInt().toLong() and M
        }
        return words
    }

    private fun sigma0(value: Long): Long {
        return ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor
            (value shl 14) xor (value ushr 3)) and M
    }

    private fun sigma1(value: Long): Long {
        return ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor
            (value shl 13) xor (value ushr 10)) and M
    }

    private fun sha256Rounds(
        stateIn: LongArray, words: LongArray, firstRound: Int = 0
    ): LongArray {
        var a = stateIn[0]; var b = stateIn[1]; var c = stateIn[2]; var d = stateIn[3]
        var e = stateIn[4]; var f = stateIn[5]; var g = stateIn[6]; var h = stateIn[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        val aligned = minOf(64, ((firstRound + 7) / 8) * 8)
        for (index in firstRound until aligned) {
            val rotE = e * 0x100000001L
            val rotA = a * 0x100000001L
            val t1 = (h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (g xor (e and (f xor g))) + constants[index] + words[index])
            val t2 = ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = (d + t1) and M
            d = c; c = b; b = a; a = (t1 + t2) and M
        }
        for (index in aligned until 64 step 8) {
            var rotE: Long; var rotA: Long; var t1: Long

            rotE = e * 0x100000001L; rotA = a * 0x100000001L
            t1 = (h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (g xor (e and (f xor g))) + constants[index] + words[index])
            d = (d + t1) and M
            h = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((a and b) or (c and (a or b)))) and M

            rotE = d * 0x100000001L; rotA = h * 0x100000001L
            t1 = (g + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (f xor (d and (e xor f))) + constants[index + 1] + words[index + 1])
            c = (c + t1) and M
            g = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((h and a) or (b and (h or a)))) and M

            rotE = c * 0x100000001L; rotA = g * 0x100000001L
            t1 = (f + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (e xor (c and (d xor e))) + constants[index + 2] + words[index + 2])
            b = (b + t1) and M
            f = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((g and h) or (a and (g or h)))) and M

            rotE = b * 0x100000001L; rotA = f * 0x100000001L
            t1 = (e + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (d xor (b and (c xor d))) + constants[index + 3] + words[index + 3])
            a = (a + t1) and M
            e = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((f and g) or (h and (f or g)))) and M

            rotE = a * 0x100000001L; rotA = e * 0x100000001L
            t1 = (d + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (c xor (a and (b xor c))) + constants[index + 4] + words[index + 4])
            h = (h + t1) and M
            d = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((e and f) or (g and (e or f)))) and M

            rotE = h * 0x100000001L; rotA = d * 0x100000001L
            t1 = (c + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (b xor (h and (a xor b))) + constants[index + 5] + words[index + 5])
            g = (g + t1) and M
            c = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((d and e) or (f and (d or e)))) and M

            rotE = g * 0x100000001L; rotA = c * 0x100000001L
            t1 = (b + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (a xor (g and (h xor a))) + constants[index + 6] + words[index + 6])
            f = (f + t1) and M
            b = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((c and d) or (e and (c or d)))) and M

            rotE = f * 0x100000001L; rotA = b * 0x100000001L
            t1 = (a + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25))
                + (h xor (f and (g xor h))) + constants[index + 7] + words[index + 7])
            e = (e + t1) and M
            a = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22))
                + ((b and c) or (d and (b or c)))) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSha256Schedule(words: LongArray, firstWord: Int = 16): LongArray {
        val result = LongArray(64)
        for (i in words.indices) result[i] = words[i]
        for (index in firstWord until 64) {
            val x = result[index - 2]; val y = result[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            result[index] = (s1 + result[index - 7] + s0 + result[index - 16]) and M
        }
        return result
    }

    private fun sha256Init(data: ByteArray): LongArray {
        val cached = cachedInitKey
        if (cached != null && cached.contentEquals(data)) {
            return cachedInitState!!
        }
        val words = LongArray(64)
        words[14] = ((data[0].toLong() and 0xFF) shl 16) or
            ((data[1].toLong() and 0xFF) shl 8) or
            (data[2].toLong() and 0xFF)
        words[15] = ((data[3].toLong() and 0xFF) shl 24) or
            ((data[4].toLong() and 0xFF) shl 16) or
            ((data[5].toLong() and 0xFF) shl 8) or
            (data[6].toLong() and 0xFF)
        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16)
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                else
                    sigma1(words[index - 2])
            }
            if (index - 7 >= 14) {
                value += words[index - 7]
            }
            if (index - 15 >= 14) {
                value += if (index == 29)
                    sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                else
                    sigma0(words[index - 15])
            }
            words[index] = value and M
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) +
            words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]) and M
        val expanded = expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, expanded, 14)
        val state = LongArray(8) { (SHA256_INITIAL[it] + working[it]) and M }
        cachedInitKey = data.copyOf()
        cachedInitState = state
        return state
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size)
        val padSize = (55 - length % 64 + 64) % 64
        val padded = ByteArray(tail.size + 1 + padSize + 8)
        System.arraycopy(tail, 0, padded, 0, tail.size)
        padded[tail.size] = 0x80.toByte()
        val bitLen = length.toLong() * 8
        ByteBuffer.wrap(padded, padded.size - 8, 8)
            .order(ByteOrder.BIG_ENDIAN).putLong(bitLen)
        var state = sha256Init(data.copyOfRange(0, 7))
        for (offset in padded.indices step 64) {
            val buf = ByteBuffer.wrap(padded, offset, 64).order(ByteOrder.BIG_ENDIAN)
            val block = LongArray(16) { buf.getInt().toLong() and M }
            val expanded = expandSha256Schedule(block)
            val working = sha256Rounds(state, expanded)
            state = LongArray(8) { (state[it] + working[it]) and M }
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (v in state) out.putInt(v.toInt())
        return out.array()
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        val buf = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        var keyA = buf.getInt().toLong() and M
        var keyB = buf.getInt().toLong() and M
        keyA = keyA or 1L
        keyB = keyB or 1L
        val factorA = (keyA * CS64_FACTOR) and M
        val factorB = (keyB * CS64_FACTOR) and M
        val factors = longArrayOf(factorA, factorB)

        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for ((i, pair) in CS64_COEFFICIENTS.withIndex()) {
            terms.add((pair[0] * factors[i % 2]) and M)
            offsets.add(pair[1])
        }

        val prefixed = ByteArray(1 + data.size)
        System.arraycopy(data, 0, prefixed, 1, data.size)
        val words = wordsLe4Even(prefixed)
        for ((i, word) in words.withIndex()) {
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and M)
            offsets.add(offset and M)
        }

        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var idx = 0
        while (idx < terms.size) {
            val x = terms[idx]; val y = terms[idx + 1]
            var t = rol16((offsets[idx] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M
            t = rol16((offsets[idx + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M

            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            idx += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun tokenHash(data: ByteArray): Pair<String, String> {
        val cached = cachedTokenHashKey
        if (cached != null && cached.contentEquals(data)) {
            return cachedTokenHashResult!!
        }
        val digest = sha256Digest(data)
        val cs64Words = cs64Digest(data, digest)
        val cs64Buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        cs64Buf.putInt(cs64Words[0].toInt())
        cs64Buf.putInt(cs64Words[1].toInt())
        val cs64Hex = cs64Buf.array().joinToString("") {
            String.format(Locale.ROOT, "%02X", it)
        }
        val sha256Hex = digest.joinToString("") {
            String.format(Locale.ROOT, "%02X", it)
        }
        val result = Pair(cs64Hex, sha256Hex)
        cachedTokenHashKey = data.copyOf()
        cachedTokenHashResult = result
        return result
    }
}
