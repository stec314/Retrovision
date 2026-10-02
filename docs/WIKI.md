# Retrovision Wiki

This is the full manual: what Retrovision is for, how every part works, the exact heuristics and their numbers, and where it fails. The same file ships inside the app (Settings → Guide), so the copy in your APK always matches the code it came with. Each build adds a "Changes in this build" section at the end.

If something here disagrees with what the app does, the code is right and this page is a bug. Please report it.

## What Retrovision is (and is not)

Retrovision is a personal counter-surveillance tool. A small ESP32 board (the **probe**) listens to Wi-Fi and Bluetooth Low Energy (BLE) radio traffic around you. An Android phone (the **app**) adds GPS and time, and asks one question: **does the same device keep showing up wherever I go?**

It does five jobs, which differ a lot in how certain they are:

| Job | What it answers | How certain |
|---|---|---|
| Following detection | "Has this device been with me across several places?" | Probabilistic. A score with reasons that you have to judge yourself |
| Tracker detection | "Is there an AirTag / SmartTag / Tile / Find My tag near me, possibly away from its owner?" | High. These tags announce what they are |
| Radio attack detection | "Is someone deauthing, running a fake AP, cloning my network, or spamming Wi-Fi/Bluetooth right now?" | High. Read directly from the frames |
| Drone detection | "Is a drone nearby, where is it and where is its pilot?" | High with Remote ID (the drone states it). Low without it |
| Notable devices | "Is that a pentest gadget, a plate-reading camera, a body cam, camera glasses?" | Low. A pattern match on names and IDs, shown as information only |

What it is **not**:

- Not a way to identify people. It sees radios, not names. It never tells you *who* something belongs to.
- Not a tracking tool. It looks at devices around *you*, in relation to *your* movement. It does not export per-device tracks or maps of where a device has been.
- Not proof. A high score means "worth a look", never "you are being followed". Buses, commuter trains, colleagues and neighbours all produce real, harmless persistence.
- Not a cellular detector. IMSI catchers / fake base stations are out of scope. Use a dedicated tool such as Rayhunter for those.

Retrovision is a from-scratch reimplementation of the ideas in [Chasing Your Tail NG](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG) (time windows, multi-location persistence). It replaces a Kismet laptop with a thumb-sized probe and a phone.

## How it works: the pipeline

```
 radio ──▶ probe (ESP32) ──USB──▶ app: decode ─▶ time-stamp ─▶ resolve identity ─▶ store (encrypted)
                                                                                    │
     GPS ─────────────────────────────▶ quality filter ─▶ places ──────────────────┤
                                                                                    ▼
                         every 60 s: analysis ─▶ score + reasons ─▶ alerts / radar / lists
                         every 60 s: last 3 min of Wi-Fi ─▶ attack detection ─▶ alerts
```

1. **Capture (probe).** The probe hops Wi-Fi channels and scans BLE continuously, then forwards each frame it keeps as a protobuf message over USB. It never decides anything; it is a sensor.
2. **Time (app).** The probe only knows "microseconds since boot". The app maps that to wall-clock time with an NTP-style sync (see *The probe link*).
3. **Identity (app).** Each sighting is attributed to an **entity**: the best guess of one physical device across MAC address rotations (see *Identity*).
4. **Storage.** Sightings go into an encrypted on-device database, written in batches.
5. **Location.** GPS fixes are stored separately and filtered by accuracy (see *GPS quality*).
6. **Analysis.** Every 60 s, or when you press *Analyze*, the app scores every entity heard within the analysis window and explains each score.
7. **Attack detection.** In the same cycle it scans the last 3 minutes of Wi-Fi management frames for attacks.
8. **Output.** Alerts (notifications), the Status screen (radar, threats, probe health), the Devices list and Places.

Everything runs on the phone. Nothing is uploaded unless you turn on an optional lookup service (see *Data, privacy and security*).

## Hardware and probes

| Board | Link to phone | Wi-Fi bands | Notes |
|---|---|---|---|
| ESP32-S3 (e.g. Seeed XIAO ESP32-S3) | Native USB (USB-Serial-JTAG) | 2.4 GHz | Reference board. Small, low power |
| ESP32 (classic: DevKitC, NodeMCU-32S…) | USB-UART bridge (CP210x / CH340) at 921,600 baud | 2.4 GHz | Cheap and common. The app releases DTR/RTS so the auto-reset circuit lets it run |
| ESP32-C5 | Native USB | 2.4 + 5 GHz | Dual-band hop list. Built by CI |

You connect the probe with a USB-OTG cable or adapter. The app recognises Espressif (0x303A), WCH (0x1A86) and Silicon Labs (0x10C4) USB vendor IDs.

**Flashing.** The APK bundles firmware built from the same commit as the app. *Probe → Flash* writes it over USB with a built-in ROM-bootloader flasher, so you need no computer. The in-app flasher supports the **ESP32-S3 and the classic ESP32**. The **ESP32-C5 must be flashed from a computer** with the browser flasher (ESP Web Tools) on the project's GitHub Pages, or with esptool, using the image published in each release.

**What the probe captures by default:**
- Wi-Fi management frames: probe requests, beacons, probe responses, authentication, (re)association requests, deauthentication, disassociation.
- Wi-Fi data frames: **only** if you enable *Capture data frames* (see *Associated clients*). Only the addresses are used, never the payload.
- BLE advertisements and scan responses, including extended advertising.

**Channel hopping.** On 2.4 GHz the probe visits channels 1, 6 and 11 three times as often as the others, and stays twice as long each time, because most networks live there. It hears **one channel at a time**: a device that transmits briefly on a channel the probe isn't on is missed. This is inherent to a single-radio sniffer.

**Deduplication on the probe.** Beacons are merged over 30 s per access point (they repeat ~10×/s). BLE adverts are merged over 1 s. Probe requests are **not** deduplicated, because their sequence numbers are needed to link MAC rotations. Merged frames carry a count, so totals stay correct.

**Coexistence.** Wi-Fi and BLE share one antenna and are time-multiplexed (coex mode), so each sees somewhat less than it would alone.

## The probe link

The probe and app speak the Retrovision wire protocol v1 (see [protocol.md](protocol.md)): protobuf messages, COBS-framed with a CRC-32. Corrupted frames are counted and dropped, and never misread.

**Handshake.**
1. The probe sends **Hello** every 2 s (type, firmware version, hardware ID, a random boot ID, protocol version).
2. The app answers **HelloAck** with the full configuration (frame types, dedup, LED, status interval). The configuration is *replace, not merge*: every config message is the complete desired state.
3. If the major protocol version differs, the app rejects the probe and tells you to update the firmware.

**Time sync.** The app sends 8 time-sync requests 60 ms apart (a "burst"), right after the handshake and every 30 s after that. From each round trip, offset ≈ (t1 + t3)/2 − t2, with error ≤ RTT/2. Within a burst only the fastest round trip is kept. Across bursts a line `wall = a·probe + b` is fitted, which absorbs crystal drift (±20 ppm ≈ 72 ms per hour). Observations that arrive before the clock is synced are dropped and counted ("dropped (no clock)"), never stamped with a guess.

**Status.** Every 10 s the probe reports free memory, chip temperature, the current channel and frames it had to drop (its queue was full). These numbers fill the probe card on the Status screen.

**Link watchdog (recovery without unplugging).** The probe can get stuck without the cable ever leaving:
- *Orphaned session.* The app reopened the port (service restart, a read error) while the probe still believed its old session was up. It kept streaming status but never said Hello again, so the app waited forever ("Waiting for the probe to introduce itself", 0 observations). The app now notices frames arriving without a Hello and **reboots the probe over the wire**. It returns within seconds with a fresh Hello.
- *Silence.* No frames at all for 8 s while waiting for Hello, or 30 s while streaming (status alone should arrive every 10 s). The app reboots the probe. If it stays silent for 45 s, the app closes and reopens the USB port.
- *Firmware side.* If the probe hears nothing from the app for 2 minutes (the app sends time sync every 30 s), it drops back to the handshake by itself. This needs firmware from the same build or newer.

If none of this helps, the probe is probably not running Retrovision firmware (flash it), or the USB cable or adapter is power- or data-limited.

**Temperature.** Continuous Wi-Fi and BLE make the chip warm. 60–75 °C on the internal sensor is normal for a small board in a pocket. The sensor reads the die, not the case.

## Identity: from MAC addresses to entities

The core problem: modern phones **randomise** their MAC addresses. A Wi-Fi probe request from an iPhone uses a different address every few minutes, and BLE phones rotate their address about every 15 minutes. Counting addresses would turn one phone into dozens of short-lived "devices", none of which ever looks like it follows you.

Retrovision groups sightings into **entities** with these rules, which are conservative on purpose. A wrong link merges two strangers into one fake "follower", which is worse than a missed link.

**Stable addresses: one entity per address.**
- Wi-Fi addresses that are globally administered (the vendor-assigned MAC; the "locally administered" bit is 0).
- BLE public addresses and BLE *random static* addresses (stable until the device reboots).

**Randomised Wi-Fi addresses (probe requests).** A new address is linked to an existing entity only if **all** of these hold:
1. **Same IE fingerprint.** A hash of the capability elements in the probe request (supported rates, HT/VHT capabilities, extended capabilities, vendor elements, in order), skipping per-request fields (SSID, channel) and random ones (WPS UUID). The idea comes from Vanhoef et al., *Why MAC Address Randomization is not Enough* (2016). The fingerprint is **not unique**: every phone of the same model and OS shares it.
2. **Time adjacency.** The entity's last probe was ≤ 2 minutes ago.
3. **Sequence-number continuity.** 802.11 frames carry a 12-bit counter. The new frame must continue it: 1 ≤ (seq − last) mod 4096 ≤ 64.
4. **No ambiguity.** Exactly one candidate matches. If two do, nothing is linked.

Some OSes reset the sequence counter when they rotate the address. Those rotations are **missed, never mis-linked**.

**Randomised BLE addresses.** A new address is stitched to a previous one ("carry-over") only if:
- the advertisement has a **distinctive, serial-like local name** (≥ 10 characters, or ≥ 4 with a digit, e.g. a fitness band broadcasting its serial), and
- the coarse shape (company ID, service UUIDs, appearance) matches, and
- exactly one recent trail (≤ 5 minutes) matches, at a comparable signal (|ΔRSSI| ≤ 12 dB).

**Anonymous phones are never stitched.** Their advertising shape (Apple, Google or Microsoft "continuity" messages) is shared by millions of devices, and BLE has no per-device counter like Wi-Fi's sequence number. This is the biggest honest limit of the tool (see *Limits*). Trails are forgotten after 5 minutes, so no cross-day identity is built from BLE.

**MAC trust.** Each entity shows how much its address can be trusted over time:
- **Stable**: a vendor or public address. Same device, every time.
- **Until reboot**: a BLE random static address.
- **Rotating**: a randomised address. The entity may be a fragment of a device, and the same device may appear as several entities.

## Device categories, trackers and moving access points

**Categories** are best-effort guesses from what a device broadcasts about itself: router, hotspot, vehicle, camera, Wi-Fi Direct, Wi-Fi client, phone, computer, watch, audio, TV, input device, health device, home device, tracker, beacon, other. The evidence comes from the Bluetooth company ID, service UUIDs, GAP appearance, iBeacon/Eddystone frames, Fast Pair, local names and AP SSID patterns. Vendor names come from the IEEE OUI registry and the Bluetooth SIG company list, downloaded at build time.

**Trackers** are recognised from their advertisements, following AirGuard (seemoo-lab), the reference Android tracker detector:

| Tracker | How it is recognised | Separated-from-owner flag |
|---|---|---|
| AirTag, Find My accessories, AirPods, Apple devices in Find My | Apple company 0x004C, offline-finding type 0x12. Status bits give the class | Yes. The payload length says "separated" or "near owner" |
| Samsung Galaxy SmartTag | Service 0xFD5A | No |
| Samsung Find My Mobile devices | Service 0xFD69 | No |
| Tile | Service 0xFEED | No |
| Chipolo | Service 0xFE33 | No |
| Pebblebee | Service 0xFA25 | No |
| Google Find My Device network (tags and phones) | Service data 0xFEAA, frame type 0x40/0x41 (told apart from Eddystone) | No |
| **Any DULT tag** (IETF *Detecting Unwanted Location Trackers*, adopted by Chipolo, Pebblebee, moto tag and others) | Service data 0xFCB2. Byte 1 bit 0 = mode | Yes. 0 = separated, 1 = near owner. If the tag also carries its brand UUID, the brand is shown and DULT supplies the flag |

An AirTag **separated from its owner** that moves with you is the classic planted-tracker situation. It gets the largest bonus in the score.

**Moving access points** are networks that travel: phone hotspots, car Wi-Fi, dashcams, action cameras, Wi-Fi Direct. They are recognised by default SSID patterns and vendor prefixes. A moving AP seen at several of your places is a strong signal, because it is usually a vehicle. These are **default names only**; a renamed hotspot is not recognised.

## The following score

Each entity heard within the analysis window gets a score from 0 to 1 and a list of **reasons**. The Devices screen shows both. Nothing is hidden: if a reason isn't shown, it didn't count.

### Inputs

- **Places.** Your GPS fixes are grouped into places by leader clustering with a 100 m radius: the first fix starts a place, and any later fix within 100 m of it belongs to it. Each sighting is matched to your nearest fix in time (gaps up to 60 s), and so to a place.
- **Windows** (live mode). Following Chasing Your Tail, the last 20 minutes are split into four windows: 0–5, 5–10, 10–15 and 15–20 minutes ago. The window sub-score is the share of these in which the entity was heard.
- **Span.** Time between the first and last sighting.
- **Travel.** The largest distance between any two of the places where the entity was heard.
- **Co-movement.** The longest continuous stretch (no gap over 90 s, at least 4 sightings, at least 2 minutes) during which **you moved ≥ 400 m** and the entity's signal stayed **steady**: mean RSSI ≥ −90 dBm and standard deviation ≤ 7.5 dB. A fixed neighbour cannot do this. As you leave, its signal fades and then disappears.

### Formula

```
effPlaces = unfamiliarPlaces + 0.3 × familiarPlaces

places  = clamp((effPlaces − 1) / 3)            # 1 place → 0, 4+ → 1
windows = windowsPresent / 4                    # live mode
span    = clamp(span / 30 min)
travel  = clamp(travel / 2000 m)

score = 0.40·places + 0.20·windows + 0.15·span + 0.25·travel
```

**Bonuses:**
| Condition | Bonus |
|---|---|
| Moved with you (co-movement) | +0.15 |
| Tracker tag, seen at ≥ 2 effective places, **separated from owner** | +0.20 |
| Tracker tag, seen at ≥ 2 effective places, separation unknown or near owner | +0.10 |
| Moving access point (hotspot, car, camera; not Wi-Fi Direct), ≥ 2 effective places | +0.10 |
| Drone (Remote ID or drone-radio signature), ≥ 2 effective places | +0.15 |
| **Arrived after you and left with you** at **≥ 2** stops | +0.15 |
| **Stayed with you through ≥ 3** of your turns | +0.10 |
| Away from your routine places, **asked by name for one of your networks** | +0.10 |
| Travels in a group with other devices (same places, same times), ≥ 2 effective places | +0.05 |

**Caps:**
- **Fewer than 2 effective places → score ≤ 0.30.** Being near you for a long time in one spot makes it a neighbour, not a follower.
- **Resident → score ≤ 0.25** (see *Places, routine places and residents*).

**Alert rule.** An alert fires when **score ≥ alert threshold** (default 0.70) **and effective places ≥ minimum places** (default 3). Both are in Settings.

### Reasons you can see

| Reason | Meaning |
|---|---|
| Seen at N places | Heard at N distinct places (100 m clusters) |
| Familiar discount | Some of those places are your routine places, so they count for 0.3 each |
| Present in N/4 windows | Live mode: heard in N of the four 5-minute windows |
| Seen across N periods | Retrospective mode: reappeared in N distinct hours |
| Seen for … | First-to-last time ≥ 5 minutes |
| Travelled with you … m | The places where it was heard are ≥ 200 m apart |
| Moved with you … | Co-movement with a steady signal (distance and RSSI spread shown) |
| Tracker … | A recognised tracker, with its separated-from-owner state if known |
| Moving access point … | Hotspot, car, camera or Wi-Fi Direct, with its SSID |
| Rotated N addresses | The entity was stitched from N MAC addresses |
| Drone … | Broadcasts Remote ID (serial shown), or matches a drone/controller radio signature |
| Looks like: … | Matches a notable-device signature. Information, not proof, no score bonus |
| Arrived after you and left with you at N stops | See *Route signals* |
| Stayed with you through N of your M turns | See *Route signals* |
| Asked for YOUR network | See *Network signals* |
| N rotating addresses linked: same rare networks | See *Network signals* |
| Same access point under a new name | See *Network signals* |
| Moves together with N other devices | See *Network signals* |
| Known at your routine places | Resident: damped |

### Worked examples

*A car following you across town.* Its hotspot is seen at 3 unfamiliar places, in all 4 windows, over 25 minutes, with places 1.5 km apart, and moving with you.
places = (3−1)/3 = 0.67 → 0.267 · windows = 1 → 0.200 · span = 25/30 → 0.125 · travel = 0.75 → 0.188.
Base = 0.78. Co-movement +0.15, moving AP +0.10 → **1.00 (clamped). Alert.**

*Your neighbour's router.* It is seen only at home (a confirmed routine place) for 12 hours. effPlaces = 0.3 → capped at 0.30, and after 3 days it becomes a resident (≤ 0.25). **No alert.**

*A commuter on your train.* Their phone has a stable address and is seen at 4 stations over 40 minutes, moving with you. The score is high and **this is a real false positive**: they really did travel with you. The reasons make that visible ("moved with you" during the train ride, no presence before or after). See *Limits*.

## Route signals: following vs sharing your road

Being "often near you" does not separate a follower from someone who simply takes the same road. Two signals look at **how** a device behaves around your own movements.

**Stops.** From your fixes, a stop is a stay of **≥ 5 minutes** at one place, with an arrival and a departure time. For each finished stop, the device is classed as:
- *there already, or came with you*: heard within **2 min** of your arrival;
- **arrived after you**: first heard **≥ 3 min** after you arrived;
- **left with you**: heard again within **5 min** after you left, while you were already elsewhere;
- *stayed behind*: not heard after you left, although the receivers were working.

**Arrived after you *and* left with you at 2 or more stops** is the classic follower pattern. It earns **+0.15**. A resident is there already and stays behind. Your own gear and your fellow passengers come with you. A passer-by arrives later and leaves before you.

**Turns.** Your path is thinned to points at least 20 m apart. A turn is a change of direction **≥ 60°**, measured over **60 m** before and after the point (at most one per minute). A device heard both in the **2 minutes before and after** a turn stayed with you through it. **3 or more** such turns earn **+0.10**. On a straight road everyone "follows" you; through several turns, almost nobody does by chance. This is the logic of a surveillance-detection route. The *Route check* card on the Status screen lists what stayed with you through your recent turns.

**Limits.** These signals need good GPS: stops and turns come from the same filtered fixes as everything else. They also need time: one stop or one turn proves nothing. People on the same bus or train genuinely arrive and turn with you; the reasons show it, so judge them.

## Network signals

- **Someone asked for YOUR network.** A device that, away from your routine places, asks by name for one of your own networks (Settings → My networks) has been connected to it: a household member, a past guest, or someone who got your password. **+0.10**.
  - The **+0.10 applies only after you identify your phone** (below): until then the most likely asker is your own phone, so the reason is shown without a bonus.
  - Your own phone does this too. **Settings → Identify my phone**: with the probe close, the phone runs a Wi-Fi scan and the loudest probe requests are saved as your phone's fingerprint, then ignored for this signal. Phones of the same model share the fingerprint, so theirs are ignored too.
  - Modern phones ask by name mostly for **hidden** networks, so this fires rarely. When it does, it is meaningful.
- **Rotating addresses linked by rare networks.** If two randomised addresses both ask for **≥ 2** networks that at most **3** devices in the window ask for, and their lists overlap by at least half, they are merged into one entity. Common names (eduroam, airport and chain hotspots) never link anything, and neither do names that are only your own networks (your household shares those).
- **One access point, new name.** Every beacon carries the AP's uptime (TSF timer). *Reception time − uptime* is the moment it booted, constant until it reboots. When one AP goes silent and another appears within **30 min** with the same boot moment (**± 2 s**), it is the same radio renamed or with a new address: typically a phone hotspot or car Wi-Fi. Radios serving several names *at the same time* are not merged. This needs probe firmware from this build or newer (protocol 1.1).
- **Groups.** Devices seen at **≥ 3** places, sharing **≥ 75%** of their places and **≥ 40%** of their 5-minute time slots, form a group: a phone, watch, earbuds and car moving as one person or vehicle. A group survives one member rotating its address. **+0.05** each.

## Your phone's own sensors

The phone can be a receiver too (Settings → Phone sensors):

| Sensor | What it adds | Limits |
|---|---|---|
| **Bluetooth LE** (off / only without probe / always; default always) | Full advertisements like the probe: trackers, Bluetooth Remote ID, BLE spam and notable devices **work without a probe**. Phones with LE Coded PHY also hear **Bluetooth 5 Long Range**, which the probe doesn't | Android throttles scanning and may pause it with the screen off. Uses battery |
| **Accelerometer** | GPS drift check (see below) | None worth noting: it is the cheapest sensor |
| **Bluetooth Classic** (button, ~12 s) | Discoverable Classic devices, e.g. **HC-05/HC-06 serial modules** used in card skimmers. The probe has no Classic radio | Only devices in discoverable mode answer; it occupies the phone's Bluetooth while it runs |
| **Wi-Fi connection** | Being connected to one of your networks counts as being at a routine place, even indoors without GPS | Needs location permission to read the network name |

**Phone-only mode.** Without a probe: trackers, Bluetooth drones, BLE spam and notable Bluetooth devices work. Probe requests, Wi-Fi attacks and Wi-Fi Remote ID need the probe. The Status screen says which mode you are in.

**Signal levels are per receiver.** The phone's antenna and the probe's read different dBm, so "steady signal" (co-movement) and "one transmitter" (BLE spam) are judged on one receiver at a time.

**Silent GPS drift.** The accuracy filter only catches fixes the receiver *admits* are bad; indoors it often claims ±20 m while wandering 100 m. While the accelerometer says you are **not travelling** **and** the GPS Doppler speed is under **0.8 m/s**, fixes more than **30 m** (or the anchor's accuracy) from where stillness began are rejected. "Not travelling" is the spread of acceleration over 20 s: under 0.12 m/s² = resting on a table, under 0.8 m/s² = in your hand (tremor and taps, but no steps); walking jolts 1–3 m/s² at every step. If fixes the guard rejected keep getting **further away 3 times in a row** (≥ 10 m each), that is real movement the sensors missed: the guard accepts it and starts over there. Drift jumps back and forth, so it never escapes this way. Doppler speed comes from carrier frequency shifts, not positions, so it stays near zero during drift and stays right in a smooth car where the accelerometer looks calm.

## Your own devices and your own network

**"Is this yours?"** A device that travelled with you (moved with you across ≥ 2 places) on **3** different days is proposed on the Status screen. *Yes* ignores it; *No* never asks again. Your watch, earbuds and car are the most common false alerts, and this removes them.

**Trackers and drones are never proposed.** A tracker planted on you also "travels with you every day": auto-ignoring would hide exactly the threat the app exists for.

**Your phone joining an unknown access point.** For your own networks, the app remembers the access points your phone joins (the first one is trusted, and so are others from the same vendor, such as mesh nodes and extenders). If your phone joins your network through an access point from another vendor, it alerts: that is an evil twin that got **your** phone, not just one in the air. If it's yours (a new router), trust it in Settings.

## Your verdicts and field tests

Every threshold in this page is reasoned, not measured on real data. Two tools make measuring possible:

- **Verdict buttons** in device details: *False alarm* (silences that device's alerts for 24 h), *Suspicious*, and *Ignore (mine)*. Settings shows the counts and which reasons appear most in false alarms: that is where tuning should start. Verdicts stay on the phone.
- **Field test.** Mark a device you carry on purpose (a friend's phone, a tag, the retrovision-target firmware) as a test target. Then walk a route with turns and stops, or have someone follow you with it. The Sessions screen shows its detection rate, places, score, reasons and **how long it took to first cross the alert threshold**.

## Places, routine places and residents

**Routine (familiar) places.** Where you live, work or spend a lot of time, devices are always around. Places you are at often still count, because someone following you knows where you live, but they count for only **0.3** of an unfamiliar place.

Routine places come from two sources:
- **Learned.** Every 15 minutes the app looks at your own GPS history (up to 30 days). A 100 m cluster where you spent **≥ 30 minutes a day on ≥ 3 days** is *suggested*. If ≥ 40% of the time there falls between 22:00 and 06:00, it is labelled home-like. Only fixes better than your GPS accuracy setting are used. Suggestions do nothing until you **confirm** them.
- **Manual.** *Mark where I am as a routine place*, with a radius you choose.

Only **confirmed** places count.

**Residents (baseline per place).** A device that is heard **only** at your routine places (never at an unfamiliar one) gains one "day" per calendar day it is heard. After **3 days** it becomes a **resident**: the neighbour's TV, a colleague's laptop. Residents are capped at a score of 0.25 and show the reason "Known at your routine places". If a resident is ever seen at an unfamiliar place, it simply stops gaining days and is scored normally there. The cap keeps applying while it stays a resident. Wipe the baseline in Settings to start over.

**Alerts only away from routine places** (optional). This mutes following alerts while you are inside a confirmed routine place. Attack alerts are never muted by it.

## GPS quality

GPS is the weakest link in following detection. Indoors, in a car park, in a tunnel or between tall buildings, the reported position **drifts**: it jumps tens to hundreds of metres while you are standing still. Without a filter, drift would:
- invent **places** you never visited, inflating "seen at N places";
- invent **travel**, inflating "travelled with you";
- invent **co-movement**: you "moved 400 m" with a steady signal from your own flatmate's phone;
- point the radar in a random direction.

Retrovision handles this in layers:
1. **At capture.** Fixes worse than ±150 m are never stored. Fixes are stored at most every 5 s.
2. **At analysis.** Fixes worse than **±50 m** (the *Ignore GPS fixes worse than* setting, 20–150 m) are dropped before places, travel and co-movement are computed. Fixes with unknown accuracy are kept.
3. **Learning routine places** uses the same filter.
4. **Radar** only uses samples taken with a fix that is good enough and less than 15 s old.
5. **Silent drift** while the phone is still is rejected using the accelerometer and Doppler speed (see *Your phone's own sensors*).

**Trade-off.** A stricter setting means fewer false alerts but also fewer usable fixes. In a long indoor stay you may get no places at all, so following detection effectively pauses there. That is the honest behaviour: without a reliable position, "it followed me" cannot be judged. Trackers and attack detection don't depend on GPS and keep working.

Suggested values: 30–50 m in cities (default 50), 75–100 m if you are mostly in the countryside with poor coverage.

## Live and retrospective analysis

**Live analysis** answers "is something following me *now*?". It looks back over the **analysis window** (Settings, 30–720 minutes, default 120) and runs every 60 s. The four windows only cover the last 20 minutes, so a short window reacts fast and stays focused on the current trip.

**Why not a huge window?** Persistence would build up from ordinary life (the same café twice a week), and alerts would fire on stale history. Live mode is for "now".

**Retrospective analysis** (*Analyze saved data*) answers "has anything been around me across the last days?". This catches someone who shows up at different moments rather than continuously. It reviews a span you pick (e.g. 24 h, 3 days, 7 days) with a tuned configuration:
- The windows are replaced by **recurrence**: the number of distinct **hours** in which the entity reappeared, saturating at 6.
- Span saturates at ¼ of the review (between 30 min and 6 h), and travel at 3 km.
- Very large datasets are **sampled** evenly (at most 60,000 sightings), and the raw Wi-Fi elements are dropped to keep memory low. Counts are approximate in that case, and the screen says so.

Recommended routine: keep live mode on all the time with a short window, and run a retrospective review now and then, or whenever something felt off.

## Radio attack detection

Every analysis cycle checks the **last 3 minutes** of Wi-Fi management frames and the **last minute** of BLE advertisements. These are facts read from the air, not guesses about identity.

| Attack | Detection rule | Why this rule |
|---|---|---|
| **Deauth / disassoc flood** | ≥ **40** deauth+disassoc frames aimed at **one** BSSID within 3 min. Severity grows to 400 | Normal networks send a few deauths, spread across many BSSIDs (roaming, idle timeouts). An attack hammers one target. An earlier rule ("≥ 12 in total") fired on ordinary city traffic |
| **Karma / MANA access point** | One AP answers probe responses for **≥ 5 different SSIDs** (severity saturates at 15) | A real AP has one name. A Karma AP says "yes" to every network your phone asks for, to lure it in |
| **Evil twin of your network** | One of **your own SSIDs** (Settings → My networks) advertised from **≥ 2 BSSIDs** | Your home network should have one known AP. Add every BSSID of a mesh by listing it, or expect this to fire |
| **Beacon flood** (mdk4, ESP32 Marauder / Deauther "beacon spam") | **≥ 25** networks first heard within the last minute, on **one channel**, with ≥ 12 different names, and a signal spread (std dev) **≤ 6 dB**. Needs ≥ 2 min of history first | Walking or driving past real networks also brings many new ones, but they come from many places, so their signals spread widely. Fake ones all come from one transmitter |
| **BLE spam** (Flipper Zero / ESP32 "pop-up" attacks) | **≥ 25** random addresses within 1 min, each alive **≤ 10 s**, sending pairing pop-up adverts (Apple Proximity Pairing / Nearby Action, Google Fast Pair, Microsoft Swift Pair, Samsung EasySetup), with a signal spread **≤ 6 dB** | Real earbuds keep an address for minutes, and a crowd's signals spread widely. A spammer cycles a new address every advert from one spot |

Not flagged, on purpose: an SSID served by many BSSIDs in general (normal for mesh, enterprise and hotspot chains) and simply "many APs around".

Threats appear on the Status screen. Threats with severity ≥ 0.6 send a notification, at most once per 10 minutes per attack and target. Quiet hours apply.

**Limits.** A one-channel sniffer misses frames on other channels, so a short attack on another channel can go unseen. Management Frame Protection (WPA3 / 802.11w) makes forged deauths ineffective, but they are still visible and still flagged. A beacon flood is caught when it **starts** (its networks are new). If it was already running when you arrived, the fake networks look like the neighbourhood. A spammer that keeps one address, or a single pop-up, is not a flood and is not flagged.

## Drones

Drones over 250 g, and most new consumer drones, must broadcast **Remote ID** (ASTM F3411; EU Delegated Regulation 2019/945 "direct remote identification"). It is a public, unencrypted digital licence plate: the drone states its serial number, its position, height, speed and heading, and **its pilot's position**. Retrovision decodes it.

**What the probe can hear:**

| Transport | Heard? | Notes |
|---|---|---|
| Wi-Fi Beacon (vendor IE FA:0B:BC, type 0x0D) | **Yes** | Used by DJI and many consumer drones. Phones can't see it on stock Android; the probe's monitor mode can. Beacons carrying Remote ID are deduplicated over 1 s instead of 30 s, so the track stays live (needs firmware from this build or newer) |
| Bluetooth 4 legacy (service data 0xFFFA) | **Yes** | Add-on Remote ID modules |
| Bluetooth 5 Long Range (Coded PHY) | No | The probe scans the 1M PHY only. Coded PHY would halve Wi-Fi listening time |
| Wi-Fi NAN (action frames) | No | Not captured |
| DJI OcuSync "DroneID" | No | Not Wi-Fi at all. It needs a software-defined radio |

**What you see:**
- Status screen → **Drones nearby**: serial, aircraft type, **distance and compass direction from you**, height, and **distance and direction to the pilot**. These are real positions from the broadcast, not signal-strength guesses.
- A notification the first time a drone is heard, then at most once per 30 minutes per drone (Settings → Notifications → drone alerts, on by default; quiet hours apply).
- In the Devices list (🛸 filter) a drone is an entity like any other. If it turns up at **≥ 2 of your places**, it gets **+0.15** and can raise a following alert.

**Drones without Remote ID** (old, home-built, or Remote ID switched off) can sometimes still be spotted by their radios: DJI, Autel, Parrot (ANAFI/Bebop), Skydio and HOVERAir Wi-Fi names, Bluetooth names and company IDs. These appear as "drone/controller radio, no Remote ID" **without a position**. Often it is the pilot's controller or a drone parked on the ground, not one in the air.

**Honest limits.**
- Remote ID is **not authenticated**: it can be spoofed, or switched off on modified drones.
- Military and many professional or government drones do not broadcast it. **No drone detected does not mean no drone.**
- Range depends on the drone's transmitter and on obstacles. Typically a few hundred metres for Bluetooth, more for Wi-Fi.
- The analysis of saved data (retrospective) drops Wi-Fi elements to save memory, so it sees Bluetooth Remote ID but not Wi-Fi Remote ID.

## Notable devices

Some radios matter because of **what kind of thing they are**. Retrovision tags them using a signature catalog derived from [Fieldwatch](https://github.com/OffGridPete/Fieldwatch) (MIT, © Off Grid Pete LLC; see NOTICE):

| Kind | Examples |
|---|---|
| Pentest tools | Flipper Zero, ESP32 Marauder / Deauther, GhostESP, Hak5 WiFi Pineapple, Pwnagotchi, Porkchop, Bruce, cheap BLE serial modules (sometimes used in card skimmers) |
| Surveillance | Licence-plate readers (Flock, Motorola Vigilant, Genetec AutoVu, Rekor…), traffic sensors, IP-camera brands (Verkada, Hikvision, Axis…), gunshot detectors |
| Police gear | Body cams and in-car systems (Axon, WatchGuard, Digital Ally, Wolfcom…), vehicle LTE routers |
| Camera glasses | Ray-Ban / Oakley Meta, Snap Spectacles, Brilliant Frame, Even G1, Vuzix |
| Recording pendants | Plaud, Limitless, Bee, Friend, Omi, Fieldy |
| Drones | See *Drones* |

Matching uses default names (exact patterns, case-insensitive), vendor prefixes (only on non-random addresses), BLE company IDs, 16- and 128-bit service UUIDs, service-data payloads and Wi-Fi vendor elements.

**This is the weakest signal in the app, so it never alerts on its own:**
- A match is **not proof**. The same chips and names show up in harmless gear. The Axon pattern, for example, also matches some ZTE phones, and the vehicle LTE routers sit in buses and shops too.
- A miss is **not a clean bill**. A renamed Flipper, or one with Bluetooth off, is invisible. Most current plate-reader poles are quiet on Wi-Fi and BLE.
- What makes it matter: **behaviour** (the same moment as BLE spam or a deauth flood) or **persistence** (the normal following score: a notable device seen at several of your places).

Each match shows the catalog's own note in the device details. The 👁 filter in Devices lists them.

## Associated clients (data frames)

When *Capture data frames* is on, the probe also forwards Wi-Fi data-frame headers, **addresses only**: nothing of the content is captured, and most traffic is encrypted anyway. The app lists which client addresses are actually connected to which access points.

Use: see what is really talking on a network near you, for example devices connected to an unknown AP that showed up next to you. Cost: much more traffic over USB and in the database, and more probe drops in busy places. Leave it off unless you need it.

## Radar and Find it

**Radar** (Status screen) shows what you are hearing right now:
- **Distance from centre = signal strength**, smoothed (EWMA). Closer to the centre means louder, which *usually* means nearer. Walls, bodies, antennas and transmit power all distort this.
- **Direction** is shown only when it can be estimated, and is otherwise drawn as a ring with no direction. With one omnidirectional antenna there is no true angle of arrival. The only cue is that **walking toward a transmitter raises its signal**. The app fits a plane `rssi ≈ a + b·east + c·north` over the last 90 s of samples (needing ≥ 8 samples and ≥ 15 m of your own movement). The slope points toward the device, and the fit's R² is the confidence. A direction is drawn only at R² ≥ 0.4. If you walked in a straight line, it can only tell ahead from behind.
- Something moving **with** you keeps a constant signal, so it gets no direction. That is correct, not a bug.
- Alerting devices are highlighted. Tap a blip for details.

**Find it** turns one device into a warmer/colder meter: −100 dBm reads as cold and −35 dBm as on top of it, with beeps that speed up as the signal grows. Walk slowly, turn around (your body blocks signal), and search where it peaks. It cannot point; it only tells you hotter or colder.

## Alerts and notifications

All of these are in Settings → Notifications:

| Setting | Effect |
|---|---|
| Alerts on/off | Master switch for following and attack notifications |
| Cooldown (minutes) | Minimum time between two alerts for the same device |
| Once per device | Never re-alert the same device (until the app restarts) |
| Only if score rises | Re-alert only if the score is at least as high as last time |
| **Escalation** | A rise of +0.15 over the last alerted score always breaks through the cooldown |
| Silent | Alerts go to a channel with no sound or vibration |
| Quiet hours | No alerts in a time range |
| Only away from routine places | Mute following alerts at confirmed routine places |
| Drone alerts | Notify when a drone is heard (first time, then at most every 30 min per drone) |
| Probe disconnected | Notify if a streaming probe drops out for more than 15 s |

## Maps (offline)

Places and your own track can be drawn on a dark, interactive map. Maps are **offline vector tiles** (PMTiles, OpenStreetMap data via Protomaps). You download the area you need once, by a bounding-box extract that fetches only the needed tiles, and the app renders it locally. No map server sees where you are browsing.

The map shows **your** places and **your** movement. By design it does not draw where any other device has been.

## Data, privacy and security

- **Everything is local.** Sightings, fixes, places and settings stay on the phone.
- **Encrypted at rest.** The database uses SQLCipher. Its key is wrapped by a key held in the Android Keystore, which cannot be exported.
- **Retention.** Sightings are deleted after the number of days you set (1–30). GPS fixes are kept for up to 30 days, because learning routine places needs weeks.
- **Optional lookups.** WiGLE (BSSID/SSID → known location) and BeaconDB are **off** unless you configure them. When you use them, the queried address or SSID is sent to that service. Results are cached.
- **Session recordings** (Sessions screen, when you start one) are local files you control; they can be replayed. They are compressed but **not encrypted** (they live in the app's private storage). Delete them when you no longer need them.
- **Settings** (your SSIDs, trusted access points, lookup tokens) are stored in the app's private settings file, **not encrypted**.
- **Delete all data** removes sightings, fixes, places, baseline, lookups, "is this yours?" suggestions, verdicts, session recordings, trusted access points, your phone's fingerprint and field-test targets.
- **Retention.** "Is this yours?" suggestions not touched for 30 days are dropped (confirmed ones are kept). Verdicts are kept for 180 days.
- **Legal.** Passive radio reception is regulated differently by country. MAC addresses and SSIDs are personal data under GDPR. Keep data local and short-lived, and never publish captures.

## Settings reference

| Setting | Default | What it changes |
|---|---|---|
| Alert when score ≥ | 70% | Alert threshold |
| …and seen at ≥ N places | 3 | Minimum effective places for an alert |
| Analysis window | 120 min | Live look-back. Longer means more memory but staler |
| Ignore GPS fixes worse than | ±50 m | GPS quality filter for analysis, learning and radar |
| Keep data for | 7 days | Sighting retention (1–30 days) |
| My networks (SSIDs) | empty | Your APs are ignored in following, and protected by evil-twin detection |
| Capture data frames | off | Associated-clients list. More traffic |
| Probe LED | on | LED off for discretion. Applied immediately |
| Notifications section | see above | Alert behaviour |
| Your network's access points | learned | Trusted APs for your own networks; forget or trust here |
| Identify my phone | not set | Your phone's probe fingerprint, ignored by "asked for your network" |
| Phone Bluetooth | always | Phone as a BLE receiver: off / only without probe / always |
| Reject GPS drift while still | on | Accelerometer + Doppler drift guard |
| WiGLE / BeaconDB | off | Optional online lookups |

## Limits

This is the honest list. Read it before trusting a result.

1. **Randomised phones are mostly invisible across time.** A modern phone that isn't connected to a network rotates its Wi-Fi and BLE addresses. Wi-Fi rotations are linked only when the sequence counter continues, and anonymous BLE phones are never linked. A person carrying only a well-randomised phone may appear as many short entities that never score high. **This is the biggest gap, and it is fundamental, not a bug.**
2. **What *does* stay identifiable**: devices with stable addresses (many laptops, cars, IoT devices, older phones), access points (hotspots, cars, cameras), trackers, and BLE devices broadcasting serial-like names (bands, earbuds). These are where the tool is strongest.
3. **Shared routes cause real persistence.** Commuters, bus passengers and people walking the same way genuinely travel with you. The reasons show it, but you have to judge.
4. **One antenna, one channel at a time.** Coverage is a sample, not a complete picture. Busy places produce probe drops (shown on the probe card).
5. **Signal strength is not distance.** It tracks distance only loosely. Radar direction is an estimate from your own movement.
6. **GPS.** Following detection pauses where GPS is poor (by design, see *GPS quality*).
7. **Range.** BLE is typically 10–50 m, Wi-Fi more. A follower staying far enough away is not heard.
8. **Default-name heuristics** (moving APs, categories) fail when devices are renamed.
9. **No cellular** coverage (IMSI catchers). Use a dedicated tool.
10. **Attack detection is per-channel.** A brief attack on a channel the probe isn't listening to can be missed.
11. **Drones** are seen only if they broadcast Remote ID over Wi-Fi beacons or Bluetooth 4, or use a recognisable radio. No detection does not mean no drone.
12. **Notable-device tags** are name/ID patterns: easy to evade, and prone to false matches.
13. **Thresholds are not yet validated on real data.** Use the verdict buttons and field tests; expect values to change.

## Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| "Waiting for the probe to introduce itself" for more than ~15 s | Orphaned session, no firmware, or the wrong firmware | The app auto-reboots the probe within ~3–12 s. If it persists, flash the firmware from *Probe* |
| Probe "rejected" | Firmware and app protocol versions differ | Flash the firmware bundled with this app |
| Wi-Fi/BLE counters stuck at 0 but status updates | Clock not synced yet, or an orphaned session | Wait a few seconds. The watchdog recovers it |
| Many "dropped" frames | Busy area, data frames on | Turn off data frames |
| No places / no alerts indoors | GPS filtered out (poor accuracy) | Expected. Go outside, or relax the GPS setting a little |
| Deauth alert at home | A misbehaving AP or a real attack | Look at the target BSSID: is it yours? Repeated alerts on one BSSID are worth investigating |
| Evil-twin alert for your own mesh | Your network has several APs | Expected. List all your SSIDs and accept that a mesh will trigger it, or remove the SSID |
| Retrospective is slow on a week of data | Large dataset | It samples automatically. Pick a shorter span for detail |
| Classic ESP32 not detected | Charge-only cable, or a missing CP210x/CH340 bridge | Use a data cable. Check that the board shows up as a USB serial device |

## Glossary

- **Entity**: the app's best guess of one physical device, possibly stitched from several MAC addresses.
- **Sighting**: one received frame or advertisement (or a merged group of identical ones).
- **Probe request**: a Wi-Fi frame in which a client asks "is network X here?". It can reveal networks the device knows.
- **Beacon**: an access point announcing itself.
- **BSSID**: the MAC address of an access point radio.
- **IE (information element)**: a capability field inside Wi-Fi management frames.
- **RSSI**: received signal strength in dBm. −40 is strong, −90 is weak.
- **Effective places**: unfamiliar places plus 0.3 × routine places.
- **Resident**: a device learned to belong to your routine places.
- **CYT**: Chasing Your Tail, the project whose method this extends.

## Further reading

- [Wire protocol](protocol.md)
- Vanhoef et al., *Why MAC Address Randomization is not Enough*, AsiaCCS 2016
- [AirGuard](https://github.com/seemoo-lab/AirGuard): tracker detection on Android
- [Chasing Your Tail NG](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG)
