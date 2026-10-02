# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
"""Host tests for rv_core (C) against the shared conformance vectors.

Run from the repo root:
    python3 -m unittest -v firmware/components/rv_core/test/test_rv_core.py
Needs a C compiler (cc/gcc/clang). Builds a shared library in a temp dir.
"""

from __future__ import annotations

import ctypes as C
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORE = HERE.parent
FW = CORE.parents[1]
REPO = FW.parent
VECTORS = json.loads((REPO / "proto" / "testvectors" / "framing.json").read_text())

MAX_DECODED = 1280
MAX_ENCODED = MAX_DECODED + MAX_DECODED // 254 + 1


def build_lib() -> C.CDLL:
    cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("gcc")
    out = Path(tempfile.mkdtemp(prefix="rv_core_")) / "librv_core.so"
    # config.c lives in the firmware app but is portable: compile it here too
    # so -Werror catches mistakes without an ESP-IDF toolchain.
    srcs = [*CORE.glob("*.c"), HERE / "pb_roundtrip.c", FW / "components/rv_probe/config.c",
            FW / "components/rv_proto/retrovision.pb.c",
            *(FW / "components/nanopb").glob("pb_*.c")]
    cmd = [cc, "-std=c99", "-O1", "-g", "-shared", "-fPIC",
           "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
           f"-I{CORE / 'include'}", f"-I{FW / 'components/rv_proto'}",
           f"-I{FW / 'components/nanopb'}", "-o", str(out), *map(str, srcs)]
    subprocess.run(cmd, check=True)
    return C.CDLL(str(out))


LIB = build_lib()
u8p = C.POINTER(C.c_uint8)

LIB.rv_crc32.restype = C.c_uint32
LIB.rv_crc32.argtypes = [C.c_char_p, C.c_size_t]
LIB.rv_frame_encode.restype = C.c_size_t
LIB.rv_frame_encode.argtypes = [C.c_char_p, C.c_size_t, C.c_char_p, C.c_size_t]
LIB.rv_test_pb_roundtrip.restype = C.c_long
LIB.rv_test_pb_roundtrip.argtypes = [C.c_char_p, C.c_size_t, C.c_char_p, C.c_size_t]


class FrameDecoder(C.Structure):
    _fields_ = [("buf", C.c_uint8 * MAX_ENCODED), ("decoded", C.c_uint8 * MAX_DECODED),
                ("len", C.c_size_t), ("overflow", C.c_bool), ("bad_frames", C.c_uint32)]


LIB.rv_frame_decoder_feed.restype = C.c_int
LIB.rv_frame_decoder_feed.argtypes = [C.POINTER(FrameDecoder), C.c_uint8,
                                      C.POINTER(u8p), C.POINTER(C.c_size_t)]


class WifiFrame(C.Structure):
    _fields_ = [("type", C.c_int), ("addr1", C.c_uint8 * 6), ("addr2", C.c_uint8 * 6),
                ("addr3", C.c_uint8 * 6), ("seq", C.c_uint16), ("ssid", C.c_uint8 * 32),
                ("ssid_len", C.c_uint8), ("has_ssid", C.c_bool), ("tsf", C.c_uint64), ("ies", u8p),
                ("ies_len", C.c_uint16), ("ies_truncated", C.c_bool)]


LIB.rv_wifi_parse.restype = C.c_bool
LIB.rv_wifi_parse.argtypes = [C.c_char_p, C.c_size_t, C.POINTER(WifiFrame)]

LIB.rv_test_cfg.restype = C.c_int
LIB.rv_test_cfg.argtypes = [C.c_char_p, C.c_size_t, C.c_char_p, C.c_size_t]

LIB.rv_ble_classify_addr.restype = C.c_int
LIB.rv_ble_classify_addr.argtypes = [C.c_int, C.c_char_p]
LIB.rv_ble_tx_power.restype = C.c_int8
LIB.rv_ble_tx_power.argtypes = [C.c_char_p, C.c_size_t]


class DedupSlot(C.Structure):
    _fields_ = [("key", C.c_uint64), ("window_start_us", C.c_uint64), ("suppressed", C.c_uint32)]


class Dedup(C.Structure):
    _fields_ = [("slots", DedupSlot * 1024), ("evictions", C.c_uint32)]


LIB.rv_dedup_check.restype = C.c_bool
LIB.rv_dedup_check.argtypes = [C.POINTER(Dedup), C.c_uint64, C.c_uint64, C.c_uint64,
                               C.POINTER(C.c_uint32)]


def feed(dec: FrameDecoder, data: bytes):
    """Returns (envelopes, bad_count) produced by feeding data byte by byte."""
    envs, bad = [], 0
    p, n = u8p(), C.c_size_t()
    for b in data:
        r = LIB.rv_frame_decoder_feed(C.byref(dec), b, C.byref(p), C.byref(n))
        if r == 1:
            envs.append(C.string_at(p, n.value))
        elif r == 2:
            bad += 1
    return envs, bad


class TestFraming(unittest.TestCase):
    def test_crc_check_value(self):
        self.assertEqual(LIB.rv_crc32(b"123456789", 9), 0xCBF43926)

    def test_encode_matches_vectors(self):
        out = C.create_string_buffer(MAX_ENCODED + 1)
        for v in VECTORS["valid"]:
            with self.subTest(v["name"]):
                body = bytes.fromhex(v["envelope_hex"])
                n = LIB.rv_frame_encode(body, len(body), out, len(out))
                self.assertEqual(out.raw[:n].hex(), v["frame_hex"])

    def test_encode_rejects_oversize(self):
        out = C.create_string_buffer(4096)
        body = b"\x01" * (MAX_DECODED - 4 + 1)
        self.assertEqual(LIB.rv_frame_encode(body, len(body), out, len(out)), 0)

    def test_decode_valid_stream(self):
        dec = FrameDecoder()
        stream = b"".join(bytes.fromhex(v["frame_hex"]) for v in VECTORS["valid"])
        envs, bad = feed(dec, b"\x00" + stream)
        self.assertEqual(bad, 0)
        self.assertEqual([e.hex() for e in envs], [v["envelope_hex"] for v in VECTORS["valid"]])

    def test_decode_rejects_invalid_and_resyncs(self):
        good = bytes.fromhex(VECTORS["valid"][0]["frame_hex"])
        for v in VECTORS["invalid"]:
            with self.subTest(v["name"]):
                dec = FrameDecoder()
                envs, bad = feed(dec, bytes.fromhex(v["frame_hex"]) + good)
                self.assertEqual(bad, 1)
                self.assertEqual(len(envs), 1)


class TestNanopb(unittest.TestCase):
    def test_roundtrip_all_envelope_vectors(self):
        out = C.create_string_buffer(2048)
        for v in VECTORS["valid"]:
            if v["kind"] != "envelope":
                continue
            with self.subTest(v["name"]):
                body = bytes.fromhex(v["envelope_hex"])
                n = LIB.rv_test_pb_roundtrip(body, len(body), out, len(out))
                self.assertGreater(n, 0, "nanopb failed (size bound too small?)")
                self.assertEqual(out.raw[:n].hex(), v["envelope_hex"])


def mgmt(subtype: int, body: bytes, a1=b"\xff" * 6, a2=bytes.fromhex("da0102030405"),
         a3=b"\xff" * 6, seq=0x123) -> bytes:
    fc = bytes([(subtype << 4) | 0x00, 0x00])
    return fc + b"\x00\x00" + a1 + a2 + a3 + (seq << 4).to_bytes(2, "little") + body


def ie(eid: int, data: bytes) -> bytes:
    return bytes([eid, len(data)]) + data


class TestWifi(unittest.TestCase):
    def parse(self, frame: bytes):
        wf = WifiFrame()
        # wf.ies points into this buffer: keep it alive for the whole test.
        self._buf = C.create_string_buffer(frame, len(frame))
        ok = LIB.rv_wifi_parse(self._buf, len(frame), C.byref(wf))
        return ok, wf

    def ies(self, wf):
        return C.string_at(wf.ies, wf.ies_len) if wf.ies_len else b""

    def test_probe_request(self):
        body = ie(0, b"HomeNet") + ie(1, bytes([0x82, 0x84, 0x8b, 0x96])) + ie(0xDD, b"\x00\x50\xf2\x08\x00")
        ok, wf = self.parse(mgmt(4, body))
        self.assertTrue(ok)
        self.assertEqual(wf.type, 1)
        self.assertEqual(bytes(wf.addr2).hex(), "da0102030405")
        self.assertEqual(wf.seq, 0x123)
        self.assertTrue(wf.has_ssid)
        self.assertEqual(bytes(wf.ssid[:wf.ssid_len]), b"HomeNet")
        self.assertEqual(self.ies(wf), body)
        self.assertFalse(wf.ies_truncated)

    def test_wildcard_probe(self):
        ok, wf = self.parse(mgmt(4, ie(0, b"") + ie(1, b"\x02")))
        self.assertTrue(ok and wf.has_ssid)
        self.assertEqual(wf.ssid_len, 0)

    def test_beacon_skips_fixed_fields(self):
        fixed = b"\x11" * 8 + b"\x64\x00" + b"\x31\x04"
        body = ie(0, b"CarHotspot") + ie(3, b"\x06")
        ok, wf = self.parse(mgmt(8, fixed + body))
        self.assertTrue(ok)
        self.assertEqual(wf.type, 3)
        self.assertEqual(bytes(wf.ssid[:wf.ssid_len]), b"CarHotspot")
        self.assertEqual(self.ies(wf), body)
        self.assertEqual(wf.tsf, 0x1111111111111111)

    def test_beacon_tsf_little_endian(self):
        fixed = (123_456_789_012).to_bytes(8, "little") + b"\x64\x00" + b"\x31\x04"
        ok, wf = self.parse(mgmt(8, fixed + ie(0, b"x")))
        self.assertTrue(ok)
        self.assertEqual(wf.tsf, 123_456_789_012)
        ok, wf = self.parse(mgmt(4, ie(0, b"x")))
        self.assertEqual(wf.tsf, 0)

    def test_malformed_trailing_ie(self):
        body = ie(0, b"x") + bytes([0xDD, 50, 1, 2, 3])
        ok, wf = self.parse(mgmt(4, body))
        self.assertTrue(ok)
        self.assertTrue(wf.ies_truncated)
        self.assertEqual(self.ies(wf), ie(0, b"x"))

    def test_raw_ie_budget_cut_at_boundary(self):
        body = ie(0, b"s") + b"".join(ie(0xDD, bytes([i]) * 100) for i in range(5))
        ok, wf = self.parse(mgmt(4, body))
        self.assertTrue(ok and wf.ies_truncated)
        self.assertLessEqual(wf.ies_len, 320)
        self.assertEqual(wf.ies_len, 3 + 3 * 102)  # SSID + three whole vendor IEs

    def test_oversized_ssid_ignored(self):
        ok, wf = self.parse(mgmt(4, ie(0, b"A" * 40)))
        self.assertTrue(ok)
        self.assertFalse(wf.has_ssid)

    def test_data_frame_header_only(self):
        frame = bytearray(mgmt(0, b"payload"))
        frame[0] = 0x08  # type 2 (data), subtype 0
        ok, wf = self.parse(bytes(frame))
        self.assertTrue(ok)
        self.assertEqual(wf.type, 9)
        self.assertEqual(wf.ies_len, 0)

    def test_rejects_short_and_control(self):
        self.assertFalse(self.parse(b"\x40\x00" + b"\x00" * 10)[0])
        ctrl = bytearray(mgmt(4, b""))
        ctrl[0] = 0xB4  # RTS
        self.assertFalse(self.parse(bytes(ctrl))[0])


class TestBle(unittest.TestCase):
    def test_classify(self):
        cases = [(0, "c10000000001", 1), (1, "c10000000001", 2), (1, "410000000001", 3),
                 (1, "010000000001", 4), (1, "810000000001", 0)]
        for is_random, addr, want in cases:
            self.assertEqual(LIB.rv_ble_classify_addr(is_random, bytes.fromhex(addr)), want, addr)

    def test_tx_power(self):
        ad = bytes.fromhex("020106" "020af4" "03ff4c00")
        self.assertEqual(LIB.rv_ble_tx_power(ad, len(ad)), -12)
        self.assertEqual(LIB.rv_ble_tx_power(bytes.fromhex("020106"), 3), 0)
        self.assertEqual(LIB.rv_ble_tx_power(bytes.fromhex("0a0a"), 2), 0)  # overrun


class TestDedup(unittest.TestCase):
    def check(self, d, key, t, window):
        mc = C.c_uint32()
        fwd = LIB.rv_dedup_check(C.byref(d), key, t, window, C.byref(mc))
        return fwd, mc.value

    def test_emit_first_then_suppress(self):
        d = Dedup()
        self.assertEqual(self.check(d, 42, 0, 1000), (True, 1))
        self.assertEqual(self.check(d, 42, 100, 1000)[0], False)
        self.assertEqual(self.check(d, 42, 999, 1000)[0], False)
        self.assertEqual(self.check(d, 42, 1000, 1000), (True, 3))  # 1 + 2 suppressed
        self.assertEqual(self.check(d, 42, 1500, 1000)[0], False)

    def test_disabled(self):
        d = Dedup()
        for t in range(5):
            self.assertEqual(self.check(d, 7, t, 0), (True, 1))

    def test_distinct_keys_independent(self):
        d = Dedup()
        for k in range(1, 300):
            self.assertEqual(self.check(d, k, 0, 1000), (True, 1))
        for k in range(1, 300):
            self.assertFalse(self.check(d, k, 10, 1000)[0])

    def test_eviction_when_chain_full(self):
        d = Dedup()
        # keys mapping to the same bucket: low 10 bits of key ^ key>>32 equal
        keys = [(i << 10) | 5 for i in range(1, 12)]
        for i, k in enumerate(keys):
            self.assertTrue(self.check(d, k, i, 10**9)[0])
        self.assertGreater(d.evictions, 0)
        self.assertTrue(self.check(d, keys[0], 100, 10**9)[0])  # evicted -> forwarded again


class TestConfig(unittest.TestCase):
    """Firmware Config validation (firmware/components/rv_probe/config.c)."""

    @classmethod
    def setUpClass(cls):
        import sys
        sys.path.insert(0, str(REPO / "tools" / "refcodec"))
        import pb
        cls.p = pb.load()

    def run_cfg(self, cfg):
        body = cfg.SerializeToString()
        out = C.create_string_buffer(512)
        r = LIB.rv_test_cfg(body, len(body), out, len(out))
        self.assertGreaterEqual(r, 0)
        return r, dict(kv.split("=", 1) for kv in out.value.decode().split(" msg=")[0].split()), \
            out.value.decode().split(" msg=")[1]

    def test_empty_config_is_defaults(self):
        r, c, msg = self.run_cfg(self.p.Config())
        self.assertEqual(r, self.p.ACK_RESULT_OK)
        self.assertEqual((c["wifi"], c["ble"], c["first"], c["bdd"], c["pdd"]), ("1", "1", "1:200", "30000", "0"))
        self.assertEqual((c["itvl"], c["win"]), ("256", "128"))  # 160 ms / 80 ms in 0.625 ms units
        self.assertEqual(c["mask"], hex((1 << 1) | (1 << 3)))
        self.assertEqual(c["status"], "0")  # present Config: 0 = status only on demand

    def test_explicit_values_and_clamping(self):
        p = self.p
        cfg = p.Config(
            wifi=p.WifiConfig(enabled=True, hop=[p.ChannelDwell(channel=14, dwell_ms=100),
                                                 p.ChannelDwell(channel=6, dwell_ms=10)],
                              frame_types=[p.WIFI_FRAME_TYPE_PROBE_REQ], min_rssi_dbm=-90),
            ble=p.BleConfig(enabled=False, scan_interval_ms=50, scan_window_ms=100),
            schedule=p.RadioSchedule(mode=p.RADIO_MODE_TIME_SLICED), status_interval_s=2)
        r, c, msg = self.run_cfg(cfg)
        self.assertEqual(r, p.ACK_RESULT_PARTIAL)
        self.assertIn("channel 14", msg)  # first adjustment is reported
        self.assertEqual((c["hops"], c["first"]), ("1", "6:50"))
        self.assertEqual(c["mask"], hex(1 << 1))
        self.assertEqual(c["wrssi"], "-90")
        self.assertEqual((c["itvl"], c["win"]), ("80", "80"))  # window clamped to interval
        self.assertEqual((c["ble"], c["mode"], c["status"]), ("0", str(p.RADIO_MODE_COEX), "2"))

    def test_wifi_only_mode_disables_ble(self):
        p = self.p
        r, c, _ = self.run_cfg(p.Config(schedule=p.RadioSchedule(mode=p.RADIO_MODE_WIFI_ONLY)))
        self.assertEqual(r, p.ACK_RESULT_OK)
        self.assertEqual((c["wifi"], c["ble"]), ("1", "0"))


class TestFuzz(unittest.TestCase):
    def test_sanitized_fuzz_smoke(self):
        cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("gcc")
        exe = Path(tempfile.mkdtemp(prefix="rv_fuzz_")) / "fuzz"
        subprocess.run([cc, "-std=c99", "-O1", "-g", "-fsanitize=address,undefined",
                        "-fno-sanitize-recover=all", f"-I{CORE / 'include'}",
                        *map(str, CORE.glob("*.c")), str(HERE / "fuzz_main.c"), "-o", str(exe)],
                       check=True)
        r = subprocess.run([str(exe), "100000"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr[-2000:])


if __name__ == "__main__":
    unittest.main()
