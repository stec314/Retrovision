# Retrovision probe firmware

| Path | What |
|---|---|
| `esp32s3/` | ESP-IDF app for the **Seeed Studio XIAO ESP32-S3** (native USB link) |
| `esp32/` | ESP-IDF app for **classic ESP32** boards: NodeMCU-32S, ESP32-DevKitC, WROOM-32 (UART link at 921600 baud via CP210x/CH340) |
| `esp32c5/` | ESP-IDF app for the **Waveshare ESP32-C5** (dual-band Wi-Fi 6, RISC-V, native USB). Adds 5 GHz sniffing. Needs ESP-IDF v5.4+ |
| `relay-esp32s3/` | **BLE relay** for a second XIAO ESP32-S3 wired to a probe's UART: carries the link so the probe keeps scanning Bluetooth. Not a probe (see below) |
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

CI (`.github/workflows/firmware.yml`) builds all boards on every push and publishes `retrovision-esp32s3-merged.bin` (flash at `0x0`) and `retrovision-esp32-merged.bin` (flash at `0x1000`). The app and the web flasher pick the right one from the detected chip.

### Classic ESP32 differences
- Link is UART0 at **921600 baud** through the board's USB-UART bridge; the IDF console is off so the line carries only protocol frames. The ROM boot banner at 115200 is skipped by the host decoder.
- Bluetooth 4.2 radio: legacy BLE advertising only (no extended advertising, no Coded PHY).
- No on-die temperature sensor in ESP-IDF 5 for this chip: `chip_temp_c` stays 0.
- Download mode only via the GPIO0 strap: the app uses the DTR/RTS auto-reset circuit; if that fails, hold BOOT, press EN/RST, release BOOT.
- LED on GPIO2 (NodeMCU-32S).

## BLE relay (relay-esp32s3)

In BLE link mode a probe's only Bluetooth radio carries the link and cannot scan. The relay moves the link to a second board, so the probe (typically the dual-band C5) scans 2.4 + 5 GHz Wi-Fi **and** BLE while on a power bank.

```
 ESP32-C5 probe                    XIAO ESP32-S3 relay               phone
 GPIO23 (TX) ───────────────────> D7 / GPIO44 (RX)
 GPIO24 (RX) <─────────────────── D6 / GPIO43 (TX)      ))) BLE NUS (((   app
 GND ──────────────────────────── GND
 5V (from power bank) ─────────── 5V
```

- 921600 baud 8N1, 3.3 V logic on both sides. Pins are Kconfig options (`RV_RELAY_UART_*` on the probe, `RV_RELAY_*` on the relay); classic ESP32 defaults to UART2 on GPIO17/16, ESP32-S3 probes to GPIO5/6.
- Pair the probe on the cable with *Pair for a relay board* (stores the key and `LINK_KIND_RELAY`), or *Switch to a relay board* on an already paired probe (keeps name and key). The probe reboots, starts the second UART and keeps BLE scanning on.
- The relay learns the probe's name from its `Hello`, stores it, and advertises `RV-<name>` with the same NUS service as a probe: the app finds it unchanged. It forwards whole frames and drops whole frames when BLE cannot keep up (the app counts them as lost). Every 60 s it adds a `Log` (tag `relay`) with phone RSSI, MTU, forwarded/dropped frames and seconds since the probe last spoke.
- On BLE connect/disconnect the relay sends a UART break; the probe ends its session at once and restarts the handshake (no 2-minute silence timeout).
- The relay never holds the pairing key: the HMAC challenge is end to end between probe and phone.
- Console and logs of the relay go to its USB port; its UART0 pads carry the link. Its ROM prints a boot banner on GPIO43 at power-on: the probe discards it as a bad frame, and the relay's break right after resets the probe's link state.
- LED (GPIO21): fast blink = no probe name yet (check the wires), slow blink = advertising, solid = phone connected.
- Flash it from the web flasher's *Install relay firmware* button, or `cd firmware/relay-esp32s3 && idf.py set-target esp32s3 build flash`. Do not flash it with the app: the app picks the probe image by chip type.

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

### ESP32-C5 (dual-band, 5 GHz) — to verify on hardware
The C5 adds 5 GHz. Prepared but not yet tested on silicon:
- **Flashing:** use the web flasher (auto-detects the chip) or `idf.py -p <port> flash` / `esptool`. In-app flashing is not enabled yet: the app needs the C5's MP chip-detect magic, which is read from the board on first connect.
- **5 GHz channels:** the hop list interleaves 2.4 GHz (1,6,11) with 5 GHz (36–48, 100–116, 149–161). If the IDF build rejects a 5 GHz channel in promiscuous mode, `hop_task` skips it (degrades to 2.4) — this needs confirming on hardware, possibly via a band/country call.
- **Promiscuous frame type:** a known IDF report notes the C5 promiscuous callback can mislabel the 802.11 frame type; re-check classification once capturing.
- **Status LED:** the DevKitC-1/Waveshare LED is an addressable RGB (WS2812, GPIO27); the shared code leaves it undriven for now (a WS2812 driver is a TODO). The LED on/off setting still applies as a no-op.
