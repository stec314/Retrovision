# Retrovision wire protocol v1

Status: **draft 1.0**. Canonical schema: [`proto/retrovision/v1/retrovision.proto`](../proto/retrovision/v1/retrovision.proto).
Conformance vectors: [`proto/testvectors/framing.json`](../proto/testvectors/framing.json).
Executable reference: [`tools/refcodec/`](../tools/refcodec/).

## 1. Roles and design goals

- **Probe**: a dumb sensor (ESP32-S3 today; ESP32-C5, nRF52, SDR bridges later). It captures and forwards raw-ish observations and never needs to know wall-clock time or position.
- **Host**: the Android app. It owns time, GPS, the database, fingerprinting, correlation and alerts.

Design goals, in priority order:

1. **Put the intelligence on the host.** Fingerprinting and tracker-detection logic changes often. Probes forward raw IEs and AD structures so that logic can be updated without reflashing.
2. **Add new probes without touching the core.** A new probe type is a new `probe_type` string plus a host-side `ProbeDriver`. See §9.
3. **Use no dynamic allocation on the firmware.** Every variable-length field has a bound in `retrovision.options`.
4. **Be independent of the transport.** USB CDC today; BLE GATT or Wi-Fi later carry the same frames.

## 2. Transport

v1 targets **USB CDC-ACM**: ESP32-S3 native USB (VID `0x303A`) connected to the phone over USB-OTG, and read on the host with `usb-serial-for-android`. Baud rate and line settings are ignored by native USB CDC. The host should still set 115200 8N1 for compatibility with bridges such as CH340/CP210x.

The byte stream is full-duplex, and each direction carries a sequence of frames.

## 3. Framing

```
frame = COBS( envelope || CRC32_LE(envelope) ) || 0x00
```

| Item | Value |
|---|---|
| Delimiter | `0x00`. It never appears inside an encoded frame. |
| Byte stuffing | COBS (Cheshire & Baker, 1999) |
| Integrity | CRC-32/ISO-HDLC over the envelope bytes, appended little-endian. `check("123456789") = 0xCBF43926`. On Android use `java.util.zip.CRC32`; on ESP-IDF use `esp_rom_crc32_le` (verify it against the vectors). |
| `MAX_DECODED_FRAME` | 1280 bytes (envelope + CRC). The nanopb worst-case `Envelope` is 1128 bytes. |
| `MAX_ENCODED_FRAME` | 1286 bytes (excluding the delimiter) |

Receiver rules:

- Accumulate bytes until `0x00`, then decode. An empty frame (two consecutive delimiters) is legal and ignored.
- If the buffer grows past `MAX_ENCODED_FRAME` before a delimiter arrives, drop everything until the next `0x00` and count one bad frame.
- If COBS decoding, the CRC check or protobuf parsing fails, drop the frame, count it, and keep going. **Never close the link because of one bad frame.**
- Senders may emit a leading `0x00` after opening the port, to flush any partial frame on the other side.

Why CRC over COBS on a USB link that already has its own CRC: the link is not the only place data gets corrupted. Resynchronising after a host reconnect, buffer overruns in the firmware, and future UART or BLE transports all produce broken frames. Four bytes is a small price.

## 4. Envelope and sequencing

Each frame carries exactly one `Envelope { seq, oneof payload }`.

- `seq` is per sender and per boot. It starts at 1 and increments by 1 for every frame sent, wrapping at 2³²−1 → 1.
- The host uses gaps in the probe's `seq` to estimate lost frames. This is separate from `Status.obs_dropped`, which counts drops *inside* the probe.
- A `CommandAck.command_seq` refers to the `Envelope.seq` of the host's `Command`.

## 5. Handshake

```
probe                                   host
  | -- Hello (every 2 s until acked) -->  |   select ProbeDriver by probe_type
  |                                       |   check protocol_major
  | <------------ HelloAck ------------   |   accepted + initial Config
  | -- CommandAck(config) ------------->  |
  | <-- Command(TimeSyncRequest) x N ---  |   see §6
  | -- TimeSyncResponse x N ----------->  |
  | == Observation / Status / Log ====>   |   streaming
```

- Before it receives a `HelloAck` whose `boot_id` matches its own, the probe captures nothing. It sends `Hello`, `Log`, and answers to the only two commands allowed at that point: `TimeSyncRequest` and `Reboot`. These two let a flasher reboot a probe even when its protocol version is unknown. Any other command is answered with `ACK_RESULT_ERROR` ("no session").
- The probe answers the `HelloAck` itself with a `CommandAck` whose `command_seq` is the `HelloAck`'s `Envelope.seq`. That ack reports how the initial config was applied (`OK` / `PARTIAL`).
- `HelloAck.accepted = false` means the probe stays idle and keeps sending `Hello` every 10 s. The host shows `reject_reason` to the user; the usual case is a major-version mismatch, which triggers a firmware-update prompt (see §10).
- A new `boot_id` in any later `Hello` means the probe rebooted. The host then discards the time-sync state and `seq` tracking and runs the handshake again.
- A `Hello` that arrives during an active session is how the probe asks to be reinitialised. The host always answers it.

Version negotiation: the host accepts the probe if `protocol_major` is equal on both sides. When the minor versions differ, each side ignores fields and enum values it does not know.

## 6. Time sync

Probes timestamp everything with their **monotonic clock** (`esp_timer_get_time()`, µs since boot). The host converts those timestamps to wall-clock time, and that conversion is what lets each observation be joined to a GPS fix.

The algorithm is NTP-style and runs on the host:

1. The host sends `TimeSyncRequest{host_t1_us}`, where t1 is the wall-clock time when the frame is written.
2. The probe replies with `TimeSyncResponse{host_t1_us, probe_t2_us}`, where t2 is its monotonic clock when the reply is built.
3. The host stamps t3 when the reply is received. Then `rtt = t3 − t1` and `offset ≈ (t1 + t3)/2 − t2`.
4. Burst: send 8 requests at the handshake and keep the sample with the **minimum RTT**. Minimum-RTT filtering removes most of the USB and scheduler jitter.
5. Maintenance: send one burst every 30 s. Fit `wall = a·probe + b` over the last 10 minutes of minimum-RTT samples, which absorbs crystal drift (typically ±20 ppm, about 72 ms/hour).
6. Tag each observation with its mapped wall time and its uncertainty (±rtt_min/2 plus the fit residual).

Expected accuracy over USB is about 1 ms. That is more than enough, because a phone GPS fix is 1 Hz; at 130 km/h, 1 ms is 4 cm.

## 7. Observations

`Observation` = common header (`probe_ts_us`, `sensor`, `rssi_dbm`, `merged_count`) + `oneof detail`.

### 7.1 Wi-Fi (`WifiFrame`)

- Always forward `addr2` (the transmitter) and `seq_ctrl`. Sequence-number continuity across a MAC rotation is one of the few signals that survive MAC randomisation.
- `ssid` holds raw bytes and is **not guaranteed to be UTF-8**. The host must never assume it can decode them.
- `tsf_us` (protocol 1.1, additive) is the 8-byte timestamp field of beacons and probe responses: the AP's TSF timer, i.e. its uptime in µs. 0 for other frames. Reception time minus TSF is the AP's boot moment, which survives renaming and BSSID changes until the AP reboots.
- `raw_ies` carries the tagged parameters verbatim; it is enabled by `WifiConfig.forward_raw_ies`. The host derives the IE fingerprint from them (element order, supported rates, HT/VHT/HE capabilities, extended capabilities, vendor OUIs). If the IEs don't fit the budget (320 bytes), the probe cuts them at an element boundary and sets `raw_ies_truncated`.
- Which frame types are forwarded is controlled by `WifiConfig.frame_types`. The default is probe requests plus beacons. Beacons are useful to recognise mobile APs such as phone hotspots, car Wi-Fi and dashcams.

### 7.2 BLE (`BleAdvertisement`)

- `adv_data` and `scan_rsp_data` are the raw AD structures. A scan response arrives as its own Observation with `adv_type = SCAN_RSP`, and the host pairs it with the advertisement by address. All tracker classification (Apple Find My, Samsung SmartTag, Tile, Google FMDN, and others) happens on the host.
- `address` is sent MSB-first, the same order as its written form.
- Passive scanning is the default. Active scanning sends `SCAN_REQ` frames and so makes the probe itself observable.

### 7.3 Probe-side dedup

Without dedup, a busy street produces thousands of frames per second: beacons at 10 Hz per AP, BLE advertisements at 1–10 Hz per device. The probe therefore uses **emit-first** dedup:

1. The first sighting of a key is forwarded **immediately**, so there is no added latency.
2. Repeats of that key within the window are suppressed and counted.
3. The first sighting after the window has elapsed is forwarded again with `merged_count = 1 + suppressed`. The window then restarts from that sighting.

Net effect: the host sees each device at most once per window, with timestamp and RSSI taken from the forwarded sighting. The probe keeps no payload state. It uses a fixed 1024-slot hash table of about 24 KB; when the table overflows, the oldest key is evicted and its next sighting is forwarded as if it were new.

| Stream | Identity key | Default window |
|---|---|---|
| Wi-Fi probe request | (type, addr2, ssid) | **0 (off)**: each frame carries a distinct `seq_ctrl`, which fingerprinting needs |
| Wi-Fi beacon / probe response | (type, addr2, ssid) | 30 s |
| BLE | (address, adv_type, adv_data) | 1 s |

Because the BLE key includes the payload, a tracker that rotates its payload but keeps its address shows up once per payload change. That is the behaviour we want.

### 7.4 GNSS and custom data

- `GnssFix` is for probes with their own receiver. It lets a standalone probe, for example one left in a car and synced later, be correlated.
- `CustomObservation{schema_id, cbor}` is the path for **experimental probes**. Once a schema is stable it gets promoted to a typed message in this file (§9).

## 8. Commands, config, backpressure

- `Config` is **replace, not merge**. Each `SetConfig` carries the full desired state and is applied atomically. Out-of-range values are clamped and the probe answers `ACK_RESULT_PARTIAL` with a message describing the first adjustment.
- Defaults: an **absent** sub-message (`wifi`, `ble`, `schedule`) means probe defaults. Inside a **present** sub-message, zero means dedup off, no RSSI filter, and the default hop list, frame types and scan timing. `status_interval_s = 0` in a present `Config` means Status is sent only on `GetStatus`, so the host should always set it.
- `RadioSchedule` exists because the ESP32-S3 has one 2.4 GHz radio shared by Wi-Fi and BLE. `COEX` hands the sharing to ESP-IDF's coexistence scheduler; `TIME_SLICED` alternates between the two explicitly (e.g. 400 ms Wi-Fi / 200 ms BLE). Firmware 0.1 implements only `COEX`, `WIFI_ONLY` and `BLE_ONLY`; it answers `TIME_SLICED` with `PARTIAL` and falls back to `COEX`. Whether `TIME_SLICED` is worth implementing will be decided by measurement.
- Backpressure: the probe has a bounded outbound queue. When it is full the probe **drops new Observations** and increments `obs_dropped`. It never drops `Hello`, `CommandAck`, `TimeSyncResponse` or `Status`, which bypass the queue. If `obs_dropped` grows, the host should widen the dedup windows or reduce `frame_types`.
- `Status` is sent every `status_interval_s` seconds (default 5) and doubles as a heartbeat. If no frame arrives for 3 intervals, the host considers the probe stalled.
- `Reboot{into_bootloader: true}` puts the chip in ROM download mode for flashing from the app or the web flasher.

## 9. Adding a new probe type

1. Choose a `probe_type` in reverse-DNS form, e.g. `dev.retrovision.esp32c5`.
2. Reuse the existing observation types (`WifiFrame`, `BleAdvertisement`, `GnssFix`) wherever they fit, and declare the matching `Capability` values. A dual-band C5 only adds `CAPABILITY_WIFI_5GHZ` and 5 GHz channels, with no schema change.
3. New kinds of data start life as `CustomObservation` with a versioned `schema_id`.
4. Promote a schema once it is stable: add a typed message to `Observation.detail`, bump the MINOR version, add vectors, and keep accepting the custom form for one minor release.
5. On the host, implement `ProbeDriver`, which maps `Hello` to a driver instance and maps observations into the core `Observation` model. The correlation engine works on entities and does not care which probe produced a sighting.

## 10. Versioning and firmware updates

- Protocol version: [`proto/VERSION`](../proto/VERSION) (`MAJOR.MINOR`). Each firmware release declares the protocol version it speaks.
- The app ships with a table of the minimum firmware version per `probe_type`. When `Hello.firmware_version` is older, or the major version does not match, the app offers an update through the web flasher (planned, `web/`).
- Evolution rules are listed at the top of the `.proto` file. In short: never renumber, and use `reserved` for removed fields.

### Version history

| Version | Change | Compatibility |
|---|---|---|
| 1.0 | Initial protocol | — |
| 1.1 | `WifiFrame.tsf_us` (field 10): beacon / probe-response timestamp, the AP's uptime | Additive. A 1.0 probe never sends it (reads as 0 = absent); a 1.0 host ignores it |

The experimental branch `wip/wireless-links` once used "1.1" for its own additions. That branch is not part of the mainline; if any of it is ever revived, it must take the next free minor (1.2 or later), never 1.1.

## 11. Security notes

- **USB (v1)**: physical access equals trust. There is no authentication, and that is deliberate.
- **Wireless transports**: a BLE GATT link shipped in protocol 1.2. It is wrapped in an authenticated, encrypted channel: LE Secure Connections bonding for confidentiality, plus an HMAC-SHA256 challenge (`Hello.auth_nonce` / `HelloAck.auth_mac`) keyed by a pairing key set over USB (`SetLink`, USB-only). The probe streams nothing until the host answers the challenge, so a nearby attacker cannot spoof observations or reconfigure the probe. A Wi-Fi/TCP transport remains reserved (`LINK_KIND_WIFI`) but is not implemented.
- The probe is receive-only by default. Only an explicit `BleConfig.active_scan` makes it transmit anything other than its USB traffic.
- Observations contain third-party identifiers (MAC addresses, SSIDs). The host should store them encrypted at rest and apply a retention period (see the app design).

## 12. Open questions

- COEX vs TIME_SLICED: which gives better capture on the S3? To be measured.
- Is the 320-byte `raw_ies` budget enough for Wi-Fi 6/7 clients with large HE/EHT capability elements? To be checked with real captures.
- Should a batched `ObservationBatch` be added if per-frame overhead shows up in profiling? It is not needed at USB full-speed.
