# Retrovision

Counter-surveillance for your pocket: an ESP32 probe (and/or your phone's own Bluetooth) listens to Wi-Fi and Bluetooth Low Energy around you, and an Android app correlates it with your movement to tell you whether the same device keeps showing up wherever you go. It also flags trackers separated from their owner, active Wi-Fi/Bluetooth attacks, and drones broadcasting Remote ID.

Retrovision is a from-scratch reimplementation of the ideas behind [Chasing Your Tail NG](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG) by @matt0177 / ArgeliusLabs. It replaces the Kismet-plus-laptop setup with a thumb-sized probe and a phone.

> **Status:** active development. Dev builds (APK + firmware for every board) are published on the [`dev-latest`](https://github.com/stec314/Retrovision/releases/tag/dev-latest) release from the `release/v0.2` branch. Thresholds are reasoned, not yet validated on real-world data.
>
> 📖 **[Read the Wiki](docs/WIKI.md)**: purpose, how every part works, the exact heuristics and their numbers, limits, troubleshooting. The same guide ships inside the app (Settings → Guide), and every build appends its changelog.

## What it does

| Job | How certain |
|---|---|
| **Following detection**: places, time windows, travel, co-movement, "arrived after you and left with you", "stayed with you through turns", device groups, rare-network linking of rotating addresses. Explainable score with reasons | Probabilistic: you judge the reasons |
| **Trackers**: AirTag / Find My, SmartTag, Tile, Chipolo, Pebblebee, Google Find My Device network, any IETF DULT tag, with "separated from owner" where the format says so | High |
| **Radio attacks**: deauth/disassoc flood, Karma/MANA AP, evil twin of your network (in the air, and on your own phone's connection), beacon flood, BLE pop-up spam | High |
| **Drones**: Remote ID (ASTM F3411) over Wi-Fi beacons and Bluetooth 4, plus drone-radio signatures | High with Remote ID, low without |
| **Notable devices**: pentest gadgets, plate-reading cameras, body cams, camera glasses, recording pendants. Information only | Low (name/ID patterns) |

What it is **not**: it does not identify people, does not export per-device tracks or maps, and is not a cellular (IMSI-catcher) detector.

## Architecture

```
┌──────────────────────┐  USB-OTG          ┌──────────────────────────────────────────┐
│ ESP32 probe          │ ────────────────▶ │ Android app                              │
│  S3 / classic / C5   │ COBS+CRC32 +      │  time sync · GPS (+ drift guard)         │
│  Wi-Fi monitor mode  │ protobuf v1.1     │  identity (MAC rotation, fingerprints)   │
│  BLE scan            │ ◀──────────────── │  following score + route signals         │
└──────────────────────┘ config, time sync │  trackers · attacks · drones · notable   │
                                           │  alerts · radar · encrypted storage      │
      phone Bluetooth LE, accelerometer ─▶ │  (phone-only mode without a probe)       │
                                           └──────────────────────────────────────────┘
```

- **Probe**: a dumb sensor. It hops Wi-Fi channels in monitor mode (2.4 GHz; 2.4 + 5 GHz on the C5), scans BLE, dedups, and forwards raw frames. It never transmits on the air.
- **Phone**: its Bluetooth LE radio is a second receiver (Long Range where supported); without a probe the app still detects trackers, Bluetooth drones, BLE spam and notable Bluetooth devices. The accelerometer and GPS Doppler reject silent GPS drift.
- **App**: everything else. All analysis runs on the phone; nothing leaves it unless you use an optional lookup.

## Supported probes

| Board | Link | Flash from the app | Flash from a browser |
|---|---|---|---|
| ESP32-S3 (e.g. Seeed XIAO ESP32-S3) | native USB | ✅ | ✅ |
| ESP32 classic (DevKitC, NodeMCU-32S…) | CP210x / CH340 at 921,600 baud | ✅ | ✅ |
| ESP32-C5 (dual band) | native USB | ❌ | ✅ |

Browser flasher: ESP Web Tools on the project's GitHub Pages (`web/`). Images for every board are also attached to each release.

## Privacy and security

- Database encrypted with SQLCipher (key wrapped by the Android Keystore); session recordings encrypted with AES-256-GCM; sensitive settings (your network names, trusted access points, tokens) sealed with a Keystore key.
- Retention you choose (sightings 1–30 days); "Delete all data" removes everything, recordings included.
- Optional online lookups (WiGLE, BeaconDB) are off unless you configure them.

## Repository layout

| Path | Contents |
|---|---|
| `proto/` | Wire protocol schema (single source of truth, version in `proto/VERSION`) and conformance vectors |
| `firmware/` | ESP-IDF projects for ESP32-S3, ESP32, ESP32-C5, shared `rv_probe` component and portable C core; see [firmware/README.md](firmware/README.md) |
| `android/` | Kotlin app (Compose) and the pure-Kotlin `core` module (analysis, identity, crypto, maps), unit-tested on the JVM |
| `web/` | Browser firmware flasher (ESP Web Tools), deployed to GitHub Pages by CI |
| `tools/` | Reference codec and tests, nanopb generator wrapper, vendor-table builder, notable-signature importer |
| `docs/` | [Wiki](docs/WIKI.md) (also bundled in the app) and the [wire protocol](docs/protocol.md) |

## Building

CI builds everything on every push to `release/**`: firmware for the three boards, the APK with the firmware bundled, and the GitHub Pages flasher.

```sh
# protocol: regenerate nanopb sources and test vectors, run the codec tests
pip install grpcio-tools "nanopb==$(cat firmware/components/nanopb/VERSION)"
./tools/gen_proto.sh && python3 tools/refcodec/gen_vectors.py
python3 -m unittest -v tools/refcodec/test_framing.py firmware/components/rv_core/test/test_rv_core.py

# firmware (ESP-IDF v5.5): one project per board
cd firmware/esp32s3 && idf.py build

# app
cd android && ./gradlew :core:test :app:assembleRelease
```

## Credits

Notable-device signatures are derived from [Fieldwatch](https://github.com/OffGridPete/Fieldwatch) (MIT, © Off Grid Pete LLC). Tracker identifiers follow [AirGuard](https://github.com/seemoo-lab/AirGuard). Method from [Chasing Your Tail NG](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG). See [NOTICE](NOTICE).

## Legal

This tool is meant for personal safety and security research. Passive reception of radio traffic is regulated differently in each jurisdiction. MAC addresses and SSIDs are personal data under the GDPR: keep captures local, encrypted and short-lived, and never publish them.

**License:** not chosen yet. Until a `LICENSE` file is added, the code is published but not licensed for reuse.
