package com.edgetts.engine

import java.security.MessageDigest
import java.util.Locale

/**
 * Generates the Sec-MS-GEC authentication token required by the Edge read-aloud
 * endpoints. This is a direct port of `_generate_sec_ms_gec` from the Python
 * NVDA add-on and MUST reproduce its float arithmetic exactly, otherwise the
 * server rejects the token with HTTP 403.
 */
object SecMsGec {

    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_VERSION = "143.0.3650.75"
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_VERSION"

    /** Windows epoch offset (seconds between 1601-01-01 and 1970-01-01). */
    private const val WIN_EPOCH_OFFSET = 11644473600.0

    /**
     * @param clockSkew seconds to add to the current time to compensate for a
     *        skewed device clock (mirrors the Python `clock_skew` parameter).
     */
    fun generate(clockSkew: Double = 0.0): String {
        // time.time() in Python is seconds-as-float; match with millis/1000.0.
        var ticks = System.currentTimeMillis() / 1000.0 + clockSkew
        ticks += WIN_EPOCH_OFFSET
        // Round DOWN to the nearest 5 minutes (300 s), same as `ticks -= ticks % 300`.
        ticks -= ticks % 300.0
        // Convert to 100-nanosecond intervals.
        ticks *= 10_000_000.0

        // Python formats with "{ticks:.0f}" — no decimals, half-to-even rounding.
        val ticksStr = String.format(Locale.ROOT, "%.0f", ticks)
        val hashInput = "$ticksStr$TRUSTED_CLIENT_TOKEN"

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hashInput.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.ROOT, "%02X", it) }
    }
}
