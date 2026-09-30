# Retrovision

Counter-surveillance for your pocket: an ESP32 probe captures Wi-Fi and BLE traffic, and an Android app correlates it with GPS to tell you whether the same device keeps showing up wherever you go.

Retrovision is a from-scratch reimplementation of the ideas behind [Chasing Your Tail NG](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG) by @matt0177 / ArgeliusLabs. It replaces the Kismet-plus-Linux-laptop setup with a thumb-sized probe and a phone.

> **Status:** early development. Wire protocol v1 and the ESP32-S3 probe firmware are in place (not yet validated on hardware); the Android app is next.

## Architecture

```
┌──────────────┐  USB-OTG (CDC)   ┌─────────────────────────────────────┐
│ ESP32-S3     │ ───────────────▶ │ Android app                         │
│ probe        │  COBS+CRC32 +    │  ProbeDriver → ingest (+GPS, time)  │
│ Wi-Fi sniff  │  protobuf        │  → entities / fingerprints          │
│ BLE scan     │ ◀─────────────── │  → persistence & multi-location     │
└──────────────┘  config, sync    │  → alerts, map, KML/report export   │
                                  └─────────────────────────────────────┘
```

- **Probe**: a dumb sensor. It sniffs 2.4 GHz Wi-Fi management frames and BLE advertisements, dedups them, and forwards raw data.
- **App**: does all the heavy lifting: time sync, GPS tagging, an encrypted database, fingerprinting to link randomised MACs, BLE tracker detection, persistence scoring, alerts and exports.
- **Expandable**: new probe types (e.g. an ESP32-C5 for 5 GHz) plug in through a `ProbeDriver` and the shared protocol. See [docs/protocol.md §9](docs/protocol.md#9-adding-a-new-probe-type).

## Repository layout

| Path | Contents |
|---|---|
| `proto/` | Wire protocol schema, the single source of truth, plus conformance vectors |
| `firmware/` | ESP-IDF probe firmware (ESP32-S3) and portable C core, see [firmware/README.md](firmware/README.md) |
| `android/` | Kotlin app *(planned)* |
| `web/` | Browser-based firmware flasher (ESP Web Tools on GitHub Pages) *(planned)* |
| `tools/refcodec/` | Python reference codec and tests for the framing |
| `docs/` | Design documents |

## Protocol quick check

```sh
pip install grpcio-tools
python3 tools/refcodec/gen_vectors.py          # regenerate proto/testvectors/framing.json
python3 -m unittest -v tools/refcodec/test_framing.py
```

## Legal

This tool is meant for personal safety and security research. Passive reception of radio traffic is regulated differently in each jurisdiction. MAC addresses and SSIDs are personal data under the GDPR: keep captures local, encrypted and short-lived, and never publish them.
