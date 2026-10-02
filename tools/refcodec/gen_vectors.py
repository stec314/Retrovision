# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
"""Regenerate proto/testvectors/framing.json.

Run:  python3 tools/refcodec/gen_vectors.py
All addresses and payloads are synthetic.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import framing  # noqa: E402
import pb  # noqa: E402

OUT = pb.REPO / "proto" / "testvectors" / "framing.json"


def envelopes(p):
    E = p.Envelope
    yield "hello", E(seq=1, hello=p.Hello(
        protocol_major=1, protocol_minor=0,
        probe_type="dev.retrovision.esp32s3",
        firmware_version="0.1.0",
        hardware_id=bytes.fromhex("02a1b2c3d4e5"),
        boot_id=0xDEADBEEF,
        capabilities=[p.CAPABILITY_WIFI_PROBE_REQ, p.CAPABILITY_WIFI_BEACON,
                      p.CAPABILITY_WIFI_RAW_IES, p.CAPABILITY_BLE_ADV,
                      p.CAPABILITY_BLE_EXT_ADV],
        max_rx_frame=framing.MAX_DECODED_FRAME,
        supported_wifi_channels=list(range(1, 14)),
    ))
    yield "hello_ack_with_config", E(seq=1, hello_ack=p.HelloAck(
        protocol_major=1, protocol_minor=0, boot_id=0xDEADBEEF, accepted=True,
        config=p.Config(
            wifi=p.WifiConfig(
                enabled=True,
                hop=[p.ChannelDwell(channel=c, dwell_ms=d) for c, d in
                     [(1, 300), (6, 300), (11, 300), (2, 150), (7, 150)]],
                frame_types=[p.WIFI_FRAME_TYPE_PROBE_REQ, p.WIFI_FRAME_TYPE_BEACON],
                forward_raw_ies=True, beacon_dedup_ms=30000,
            ),
            ble=p.BleConfig(enabled=True, scan_interval_ms=100, scan_window_ms=50,
                            extended=True, dedup_ms=1000),
            schedule=p.RadioSchedule(mode=p.RADIO_MODE_COEX),
            status_interval_s=5,
        ),
    ))
    yield "time_sync_request", E(seq=2, command=p.Command(
        time_sync=p.TimeSyncRequest(host_t1_us=1_790_000_000_000_000)))
    yield "time_sync_response", E(seq=2, time_sync_response=p.TimeSyncResponse(
        host_t1_us=1_790_000_000_000_000, probe_t2_us=12_345_678))
    yield "wifi_probe_req_wildcard", E(seq=3, observation=p.Observation(
        probe_ts_us=12_400_000, sensor=p.SENSOR_WIFI, rssi_dbm=-67,
        wifi=p.WifiFrame(
            frame_type=p.WIFI_FRAME_TYPE_PROBE_REQ, channel=6,
            addr1=b"\xff" * 6, addr2=bytes.fromhex("da0000112233"), addr3=b"\xff" * 6,
            seq_ctrl=1234, ssid=b"",
            raw_ies=bytes.fromhex("0000" "010802040b160c121824" "32043048606c" "2d1a2d0117ff00000000000000000000000000000000000000000000"),
        )))
    yield "wifi_probe_req_directed_nonutf8", E(seq=4, observation=p.Observation(
        probe_ts_us=12_400_500, sensor=p.SENSOR_WIFI, rssi_dbm=-71,
        wifi=p.WifiFrame(frame_type=p.WIFI_FRAME_TYPE_PROBE_REQ, channel=6,
                         addr2=bytes.fromhex("3c0000aabbcc"), seq_ctrl=4095,
                         ssid=b"Caf\xe9\x00Wifi")))
    yield "ble_adv_manufacturer", E(seq=5, observation=p.Observation(
        probe_ts_us=12_500_000, sensor=p.SENSOR_BLE, rssi_dbm=-80, merged_count=7,
        ble=p.BleAdvertisement(
            address=bytes.fromhex("c10000000001"),
            address_type=p.BLE_ADDRESS_TYPE_RANDOM_STATIC,
            adv_type=p.BLE_ADV_TYPE_ADV_NONCONN_IND,
            adv_data=bytes.fromhex("020106" "0bff4c00" + "00" * 8),
            primary_phy=1,
        )))
    yield "custom_observation", E(seq=6, observation=p.Observation(
        probe_ts_us=13_000_000, sensor=p.SENSOR_CUSTOM,
        custom=p.CustomObservation(schema_id="org.example.test.v1",
                                   payload=bytes.fromhex("a1616101"))))  # CBOR {"a":1}
    yield "command_ack", E(seq=7, command_ack=p.CommandAck(
        command_seq=1, result=p.ACK_RESULT_PARTIAL, message="dwell_ms clamped to 50"))
    yield "status", E(seq=8, status=p.Status(
        probe_ts_us=15_000_000, free_heap_bytes=180_000, min_free_heap_bytes=150_000,
        chip_temp_c=41.5, current_wifi_channel=11, wifi_frames_seen=10_000,
        wifi_obs_sent=900, ble_adv_seen=5_000, ble_obs_sent=300, obs_dropped=0))
    yield "log", E(seq=9, log=p.Log(probe_ts_us=15_000_100, level=p.LOG_LEVEL_WARN,
                                    tag="wifi", text="queue 80% full"))


def raw_vectors():
    """Framing edge cases independent of protobuf content."""
    yield "raw_single_zero", b"\x00"
    yield "raw_all_zeros_10", b"\x00" * 10
    yield "raw_254_nonzero", bytes((i % 255) + 1 for i in range(254))
    yield "raw_255_nonzero", bytes((i % 255) + 1 for i in range(255))
    yield "raw_max_size", bytes(i & 0xFF for i in range(framing.MAX_DECODED_FRAME - framing.CRC_LEN))


def main():
    p = pb.load()
    valid = []
    for name, env in envelopes(p):
        body = env.SerializeToString(deterministic=True)
        valid.append({
            "name": name,
            "kind": "envelope",
            "envelope_text": str(env).strip(),
            "envelope_hex": body.hex(),
            "crc32": f"{framing.crc32(body):08x}",
            "frame_hex": framing.encode_frame(body).hex(),
        })
    for name, body in raw_vectors():
        valid.append({
            "name": name,
            "kind": "raw",
            "envelope_hex": body.hex(),
            "crc32": f"{framing.crc32(body):08x}",
            "frame_hex": framing.encode_frame(body).hex(),
        })

    good = bytes.fromhex(valid[0]["frame_hex"])[:-1]  # strip delimiter
    bad_crc = bytearray(framing.cobs_decode(good))
    bad_crc[-1] ^= 0x01
    invalid = [
        {"name": "bad_crc", "frame_hex": (framing.cobs_encode(bytes(bad_crc)) + b"\x00").hex(),
         "error": "CRC mismatch"},
        {"name": "cobs_overrun", "frame_hex": "0a0102" "00", "error": "COBS block overruns frame"},
        {"name": "too_short", "frame_hex": framing.cobs_encode(b"\x01\x02\x03").hex() + "00",
         "error": "frame too short"},
        {"name": "oversize", "frame_hex": ("ff" + "01" * 254) * 6 + "00",
         "error": "encoded frame too large"},
    ]

    doc = {
        "description": "Retrovision framing conformance vectors. Regenerate with tools/refcodec/gen_vectors.py.",
        "framing": "COBS(envelope || CRC32_LE(envelope)) || 0x00",
        "crc": "CRC-32/ISO-HDLC, check('123456789') = cbf43926",
        "max_decoded_frame": framing.MAX_DECODED_FRAME,
        "note": "Protobuf encoding is not canonical: decoders must compare parsed messages, "
                "not re-encoded bytes. Framing bytes (COBS, CRC) must match exactly.",
        "valid": valid,
        "invalid": invalid,
    }
    OUT.write_text(json.dumps(doc, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(pb.REPO)}: {len(valid)} valid, {len(invalid)} invalid")


if __name__ == "__main__":
    main()
