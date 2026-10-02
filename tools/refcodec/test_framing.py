# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
"""Conformance tests for the reference codec.  Run: python3 -m unittest -v tools/refcodec/test_framing.py"""

from __future__ import annotations

import json
import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import framing  # noqa: E402
import pb  # noqa: E402

VECTORS = json.loads((pb.REPO / "proto" / "testvectors" / "framing.json").read_text())


class TestCrc(unittest.TestCase):
    def test_check_value(self):
        self.assertEqual(framing.crc32(b"123456789"), 0xCBF43926)


class TestCobs(unittest.TestCase):
    def test_roundtrip_random(self):
        rng = random.Random(42)
        for _ in range(2000):
            n = rng.randint(0, 1200)
            data = bytes(rng.choice([0, rng.randint(1, 255)]) for _ in range(n))
            enc = framing.cobs_encode(data)
            self.assertNotIn(0, enc)
            self.assertEqual(framing.cobs_decode(enc), data)

    def test_overhead_bound(self):
        data = b"\x01" * framing.MAX_DECODED_FRAME
        self.assertLessEqual(len(framing.cobs_encode(data)), framing.MAX_ENCODED_FRAME)


class TestVectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = pb.load()

    def test_valid_vectors_encode_exactly(self):
        for v in VECTORS["valid"]:
            with self.subTest(v["name"]):
                body = bytes.fromhex(v["envelope_hex"])
                self.assertEqual(framing.encode_frame(body).hex(), v["frame_hex"])
                self.assertEqual(f"{framing.crc32(body):08x}", v["crc32"])

    def test_valid_vectors_decode(self):
        for v in VECTORS["valid"]:
            with self.subTest(v["name"]):
                frame = bytes.fromhex(v["frame_hex"])
                self.assertEqual(frame[-1], 0)
                body = framing.decode_frame(frame[:-1])
                self.assertEqual(body.hex(), v["envelope_hex"])
                if v["kind"] == "envelope":
                    env = self.p.Envelope()
                    env.ParseFromString(body)
                    self.assertEqual(str(env).strip(), v["envelope_text"])

    def test_invalid_vectors_rejected(self):
        for v in VECTORS["invalid"]:
            with self.subTest(v["name"]):
                frame = bytes.fromhex(v["frame_hex"])
                with self.assertRaisesRegex(framing.FrameError, v["error"]):
                    framing.decode_frame(frame[:-1])

    def test_envelopes_fit_budget(self):
        for v in VECTORS["valid"]:
            self.assertLessEqual(len(bytes.fromhex(v["envelope_hex"])) + framing.CRC_LEN,
                                 framing.MAX_DECODED_FRAME)


class TestStream(unittest.TestCase):
    def test_resync_after_garbage_and_split_chunks(self):
        valid = [bytes.fromhex(v["frame_hex"]) for v in VECTORS["valid"]]
        invalid = [bytes.fromhex(v["frame_hex"]) for v in VECTORS["invalid"]]
        rng = random.Random(7)
        stream = bytearray(b"\x13\x37garbage-before-first-delimiter\x00\x00")
        expected = []
        for i, f in enumerate(valid):
            stream += f
            expected.append(framing.decode_frame(f[:-1]))
            if i % 3 == 0:
                stream += invalid[i % len(invalid)]
        dec = framing.StreamDecoder()
        got = []
        i = 0
        while i < len(stream):
            n = rng.randint(1, 64)
            got += dec.feed(bytes(stream[i:i + n]))
            i += n
        self.assertEqual(got, expected)
        self.assertGreaterEqual(dec.bad_frames, 1 + len(range(0, len(valid), 3)))


if __name__ == "__main__":
    unittest.main()
