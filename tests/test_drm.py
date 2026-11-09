# -*- coding: utf-8 -*-
"""Unit tests for v2 Sec-MS-GEC token generation.

Test vectors verified against rany2/edge-tts 7.3.1.
"""
import os
import re
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "synthDrivers", "edgeTts")
)
from drm import _sha256_init, _token_hash, generate_sec_ms_gec_v2


class TestSecMsGecV2(unittest.TestCase):
    """Test the v2 token generator against known vectors."""

    def setUp(self):
        _token_hash.cache_clear()
        _sha256_init.cache_clear()

    # ------------------------------------------------------------------
    # Mandatory test vectors (from the user's spec)
    # ------------------------------------------------------------------

    def test_mandatory_vector_timestamp(self):
        result = generate_sec_ms_gec_v2(unix_timestamp=1791888000)
        self.assertEqual(
            result,
            "492D7694A183A45E-"
            "56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
        )

    def test_mandatory_vector_raw_hash(self):
        cs64, sha256 = _token_hash(
            b"1343100960000000006A5AA1D4EAFF4E9FB37E23D68491D6F4"
        )
        self.assertEqual(cs64, "3575017BE6BA2CA8")
        self.assertEqual(
            sha256,
            "B3BA8420B435DDB0C48BFDAE783DEA42F2B361F94D0D625A231155D76B5CE4D2",
        )

    # ------------------------------------------------------------------
    # Additional vectors generated from upstream edge-tts 7.3.1
    # ------------------------------------------------------------------

    def test_vector_epoch(self):
        self.assertEqual(
            generate_sec_ms_gec_v2(unix_timestamp=0),
            "52BC7A17B50DFEC9-"
            "28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
        )

    def test_vector_1700000000(self):
        self.assertEqual(
            generate_sec_ms_gec_v2(unix_timestamp=1700000000),
            "2CD23A2CA0B2595A-"
            "F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
        )

    def test_vector_1700000300(self):
        self.assertEqual(
            generate_sec_ms_gec_v2(unix_timestamp=1700000300),
            "0919602B5750900A-"
            "EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397",
        )

    def test_vector_1800000000(self):
        self.assertEqual(
            generate_sec_ms_gec_v2(unix_timestamp=1800000000),
            "99E54519B490BE5C-"
            "E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
        )

    def test_vector_2000000000(self):
        self.assertEqual(
            generate_sec_ms_gec_v2(unix_timestamp=2000000000),
            "2D62557F1142BC5D-"
            "FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E",
        )

    # ------------------------------------------------------------------
    # Window behaviour
    # ------------------------------------------------------------------

    def test_same_within_five_minute_window(self):
        a = generate_sec_ms_gec_v2(unix_timestamp=1700000299)
        _token_hash.cache_clear()
        _sha256_init.cache_clear()
        b = generate_sec_ms_gec_v2(unix_timestamp=1700000300)
        self.assertEqual(a, b)
        self.assertEqual(
            a,
            "0919602B5750900A-"
            "EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397",
        )

    def test_different_across_windows(self):
        a = generate_sec_ms_gec_v2(unix_timestamp=1700000000)
        _token_hash.cache_clear()
        _sha256_init.cache_clear()
        b = generate_sec_ms_gec_v2(unix_timestamp=1700000300)
        self.assertNotEqual(a, b)

    # ------------------------------------------------------------------
    # Format
    # ------------------------------------------------------------------

    def test_token_format(self):
        token = generate_sec_ms_gec_v2(unix_timestamp=1791888000)
        self.assertRegex(token, r"^[0-9A-F]{16}-[0-9A-F]{64}$")


if __name__ == "__main__":
    unittest.main()
