# Retrovision probe firmware

| Path | What |
|---|---|
| `esp32s3/` | ESP-IDF app for the **Seeed Studio XIAO ESP32-S3** (native USB link) |
| `esp32/` | ESP-IDF app for **classic ESP32** boards: NodeMCU-32S, ESP32-DevKitC, WROOM-32 (UART link at 921600 baud via CP210x/CH340) |
| `components/rv_probe/` | The probe firmware itself, shared by both boards |
| `components/rv_core/` | Portable C core: framing, 802.11 parser, BLE helpers, dedup. Host-tested |
| `components/rv_proto/` | nanopb code generated from `proto/`. **Do not edit**; run `tools/gen_proto.sh` |
| `components/nanopb/` | Vendored nanopb runtime (zlib license), version in `VERSION` |

## Build and flash

Requires ESP-IDF **v5.5.x**.

```sh
cd firmware/esp32s3
idf.py set-target esp32s3      # first time only
idf.py build
idf.py -p /dev/ttyACM0 flash   # XIAO native USB; hold BOOT while plugging in if the port does not show up
```

CI (`.github/workflows/firmware.yml`) builds both boards on every push and publishes `retrovision-esp32s3-merged.bin` (flash at `0x0`) and `retrovision-esp32-merged.bin` (flash at `0x1000`). The app and the web flasher pick the right one from the detected chip.

### Classic ESP32 differences
- Link is UART0 at **921600 baud** through the board's USB-UART bridge; the IDF console is off so the line carries only protocol frames. The ROM boot banner at 115200 is skipped by the host decoder.
- Bluetooth 4.2 radio: legacy BLE advertising only (no extended advertising, no Coded PHY).
- No on-die temperature sensor in ESP-IDF 5 for this chip: `chip_temp_c` stays 0.
- Download mode only via the GPIO0 strap: the app uses the DTR/RTS auto-reset circuit; if that fails, hold BOOT, press EN/RST, release BOOT.
- LED on GPIO2 (NodeMCU-32S).

## How it works

```
Wi-Fi promiscuous cb ─┐
                      ├─> raw queue (48) ─> pipeline: parse → dedup → nanopb → COBS/CRC ─┐
NimBLE GAP events ────┘                                                                   ├─> USB-Serial-JTAG
session task: Hello, Status, LED;  rx task: HelloAck, commands, TimeSync ─────────────────┘
```

- **USB**: the native USB-Serial-JTAG port (VID `0x303A`) carries **only** the binary protocol. The ESP-IDF console is on UART0 (GPIO43/44). Application logs are also forwarded to the host as `Log` frames.
- **Wi-Fi**: `WIFI_MODE_NULL` + promiscuous mode, so the probe **never transmits** on Wi-Fi. It hops channels 1–13, weighted towards 1/6/11.
- **BLE**: passive NimBLE observer, legacy and extended advertising on the 1M PHY. The controller's duplicate filter is off, because dedup happens in `rv_core`.
- **Coexistence**: Wi-Fi and BLE share one radio through ESP-IDF software coexistence (`RADIO_MODE_COEX`).
- **Nothing is captured until the host completes the handshake.** When the USB host goes away for more than 1 s, capture stops and the probe goes back to sending `Hello`.
- **LED** (GPIO21): blinks at 1 Hz while waiting for the host, slowly when rejected by the host, and stays solid while capturing.

## Tests without hardware

```sh
pip install grpcio-tools nanopb==$(cat firmware/components/nanopb/VERSION)
python3 -m unittest -v firmware/components/rv_core/test/test_rv_core.py
```

These tests build `rv_core`, the generated protobuf code and `main/config.c` with the host compiler (`-Werror`), then check them against `proto/testvectors/framing.json`. They also run a fuzz smoke test under ASan/UBSan.

## Not yet verified on hardware

The code has so far been built in CI only. These points need a real board:

- USB-Serial-JTAG throughput under load, and the `obs_dropped` rate in a busy area.
- Capture quality under `COEX`, and whether `TIME_SLICED` is worth implementing.
- `Reboot{into_bootloader}` → ROM download mode.
- Whether chained extended-advertising reports arrive fragmented from NimBLE. They are reassembled, but this has not been observed yet.
