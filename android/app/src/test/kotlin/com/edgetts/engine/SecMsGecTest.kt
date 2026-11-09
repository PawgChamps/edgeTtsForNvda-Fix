package com.edgetts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Unit tests for the v2 Sec-MS-GEC token generation, verified against
 * test vectors from rany2/edge-tts 7.3.1.
 */
class SecMsGecTest {

    @Before
    fun clearCaches() {
        // Reset the caches via reflection so each test is independent.
        val cls = SecMsGec::class.java
        for (name in listOf("cachedInitKey", "cachedInitState",
                            "cachedTokenHashKey", "cachedTokenHashResult")) {
            cls.getDeclaredField(name).apply {
                isAccessible = true
                set(SecMsGec, null)
            }
        }
    }

    private fun generateFromTimestamp(unixTimestamp: Double): String {
        val winEpoch = 11644473600.0
        var ticks = unixTimestamp + winEpoch
        ticks -= ticks % 300.0
        ticks *= 1e9 / 100.0
        val ticksStr = String.format(Locale.ROOT, "%.0f", ticks)
        val strToHash = "$ticksStr${SecMsGec.TRUSTED_CLIENT_TOKEN}"
            .toByteArray(Charsets.US_ASCII)
        return tokenHashDirect(strToHash)
    }

    private fun tokenHashDirect(input: ByteArray): String {
        val method = SecMsGec::class.java.getDeclaredMethod(
            "tokenHash", ByteArray::class.java)
        method.isAccessible = true
        val result = method.invoke(SecMsGec, input)
        val pair = result as Pair<*, *>
        return "${pair.first}-${pair.second}"
    }

    // ---- Mandatory test vectors from the spec ----

    @Test
    fun testMandatoryVector_timestamp1791888000() {
        assertEquals(
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            generateFromTimestamp(1791888000.0)
        )
    }

    @Test
    fun testMandatoryVector_rawInputString() {
        val input = "1343100960000000006A5AA1D4EAFF4E9FB37E23D68491D6F4"
            .toByteArray(Charsets.US_ASCII)
        assertEquals(
            "3575017BE6BA2CA8-B3BA8420B435DDB0C48BFDAE783DEA42F2B361F94D0D625A231155D76B5CE4D2",
            tokenHashDirect(input)
        )
    }

    // ---- Additional vectors generated from edge-tts 7.3.1 ----

    @Test
    fun testVector_epoch() {
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            generateFromTimestamp(0.0)
        )
    }

    @Test
    fun testVector_1700000000() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            generateFromTimestamp(1700000000.0)
        )
    }

    @Test
    fun testVector_1700000300() {
        assertEquals(
            "0919602B5750900A-EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397",
            generateFromTimestamp(1700000300.0)
        )
    }

    @Test
    fun testVector_1800000000() {
        assertEquals(
            "99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
            generateFromTimestamp(1800000000.0)
        )
    }

    @Test
    fun testVector_2000000000() {
        assertEquals(
            "2D62557F1142BC5D-FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E",
            generateFromTimestamp(2000000000.0)
        )
    }

    // ---- Window tests ----

    @Test
    fun testSameTokenWithinFiveMinuteWindow() {
        val a = generateFromTimestamp(1700000299.0)
        clearCaches()
        val b = generateFromTimestamp(1700000300.0)
        assertEquals(a, b)
        assertEquals(
            "0919602B5750900A-EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397",
            a
        )
    }

    @Test
    fun testDifferentTokenAcrossWindows() {
        val a = generateFromTimestamp(1700000000.0)
        clearCaches()
        val b = generateFromTimestamp(1700000300.0)
        assertNotEquals(a, b)
    }

    // ---- Format test ----

    @Test
    fun testTokenFormat() {
        val token = generateFromTimestamp(1791888000.0)
        val parts = token.split("-")
        assertEquals(2, parts.size)
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        assertTrue(parts[0].all { it in '0'..'9' || it in 'A'..'F' })
        assertTrue(parts[1].all { it in '0'..'9' || it in 'A'..'F' })
    }

    // ---- Version prefix test ----

    @Test
    fun testVersionPrefix() {
        assertTrue(SecMsGec.SEC_MS_GEC_VERSION.startsWith("2-"))
    }
}
