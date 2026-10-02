# Retrovision Wiki

This is the full manual: what Retrovision is for, how every part works, the exact heuristics and their numbers, and where it fails. The same file ships inside the app (Settings → Guide), so the copy in your APK always matches the code it came with. Each build adds a "Changes in this build" section at the end.

If something here disagrees with what the app does, the code is right and this page is a bug. Please report it.

## What Retrovision is (and is not)

Retrovision is a personal counter-surveillance tool. A small ESP32 board (the **probe**) listens to Wi-Fi and Bluetooth Low Energy (BLE) radio traffic around you. An Android phone (the **app**) adds GPS and time, and asks one question: **does the same device keep showing up wherever I go?**

It does three jobs, which differ a lot in how certain they are:

| Job | What it answers | How certain |
|---|---|---|
| Following detection | "Has this device been with me across several places?" | Probabilistic. A score with reasons that you have to judge yourself |
| Tracker detection | "Is there an AirTag / SmartTag / Tile / Find My tag near me, possibly away from its owner?" | High. These tags announce what they are |
| Wi-Fi attack detection | "Is someone deauthing, running a fake AP, or cloning my network right now?" | High. Read directly from the frames |

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

**Flashing.** The APK bundles firmware built from the same commit as the app. *Probe → Flash* writes it over USB with a built-in ROM-bootloader flasher, so you need no computer. There is also a browser flasher (ESP Web Tools) on the project's GitHub Pages.

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
| Known at your routine places | Resident: damped |

### Worked examples

*A car following you across town.* Its hotspot is seen at 3 unfamiliar places, in all 4 windows, over 25 minutes, with places 1.5 km apart, and moving with you.
places = (3−1)/3 = 0.67 → 0.267 · windows = 1 → 0.200 · span = 25/30 → 0.125 · travel = 0.75 → 0.188.
Base = 0.78. Co-movement +0.15, moving AP +0.10 → **1.00 (clamped). Alert.**

*Your neighbour's router.* It is seen only at home (a confirmed routine place) for 12 hours. effPlaces = 0.3 → capped at 0.30, and after 3 days it becomes a resident (≤ 0.25). **No alert.**

*A commuter on your train.* Their phone has a stable address and is seen at 4 stations over 40 minutes, moving with you. The score is high and **this is a real false positive**: they really did travel with you. The reasons make that visible ("moved with you" during the train ride, no presence before or after). See *Limits*.

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

## Wi-Fi attack detection

Every analysis cycle checks the **last 3 minutes** of Wi-Fi management frames. These are facts read from the air, not guesses about identity.

| Attack | Detection rule | Why this rule |
|---|---|---|
| **Deauth / disassoc flood** | ≥ **40** deauth+disassoc frames aimed at **one** BSSID within 3 min. Severity grows to 400 | Normal networks send a few deauths, spread across many BSSIDs (roaming, idle timeouts). An attack hammers one target. An earlier rule ("≥ 12 in total") fired on ordinary city traffic |
| **Karma / MANA access point** | One AP answers probe responses for **≥ 5 different SSIDs** (severity saturates at 15) | A real AP has one name. A Karma AP says "yes" to every network your phone asks for, to lure it in |
| **Evil twin of your network** | One of **your own SSIDs** (Settings → My networks) advertised from **≥ 2 BSSIDs** | Your home network should have one known AP. Add every BSSID of a mesh by listing it, or expect this to fire |

Not flagged, on purpose: an SSID served by many BSSIDs in general (normal for mesh, enterprise and hotspot chains) and simply "many APs around".

Threats appear on the Status screen. Threats with severity ≥ 0.6 send a notification, at most once per 10 minutes per attack and target. Quiet hours apply.

**Limits.** A one-channel sniffer misses frames on other channels, so a short attack on another channel can go unseen. Management Frame Protection (WPA3 / 802.11w) makes forged deauths ineffective, but they are still visible and still flagged.

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
| Probe disconnected | Notify if a streaming probe drops out for more than 15 s |

## Maps (offline)

Places and your own track can be drawn on a dark, interactive map. Maps are **offline vector tiles** (PMTiles, OpenStreetMap data via Protomaps). You download the area you need once, by a bounding-box extract that fetches only the needed tiles, and the app renders it locally. No map server sees where you are browsing.

The map shows **your** places and **your** movement. By design it does not draw where any other device has been.

## Data, privacy and security

- **Everything is local.** Sightings, fixes, places and settings stay on the phone.
- **Encrypted at rest.** The database uses SQLCipher. Its key is wrapped by a key held in the Android Keystore, which cannot be exported.
- **Retention.** Sightings are deleted after the number of days you set (1–30). GPS fixes are kept for up to 30 days, because learning routine places needs weeks.
- **Optional lookups.** WiGLE (BSSID/SSID → known location) and BeaconDB are **off** unless you configure them. When you use them, the queried address or SSID is sent to that service. Results are cached.
- **Session recordings** (Sessions screen, when you start one) are local files you control; they can be replayed.
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
