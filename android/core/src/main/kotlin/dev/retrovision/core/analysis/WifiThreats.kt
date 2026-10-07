// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.identity.AdvertisementInfo
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * Detects active Wi-Fi attacks in the air around the phone — a different, higher-certainty job
 * than "is someone following me". These are things happening now, verifiable from the frames
 * themselves, not probabilistic inferences about randomised identities.
 *
 * Covered:
 *  - DEAUTH / DISASSOC floods (forcing devices off a network; often a prelude to an evil twin).
 *  - KARMA / MANA access points that answer probe requests for many different SSIDs ("yes, I'm
 *    whatever network you're looking for").
 *  - Evil-twin of one of YOUR OWN networks: your SSID advertised from a BSSID that isn't your AP.
 *  - Beacon flood (mdk4, ESP32 Marauder/Deauther "beacon spam"): dozens of fake networks
 *    appearing at once, all from one transmitter (same channel, same signal strength).
 *  - BLE spam (Flipper Zero / ESP32 "popup" attacks): a burst of short-lived random addresses
 *    sending pairing-popup adverts (Apple proximity pairing / Nearby Action, Google Fast Pair,
 *    Microsoft Swift Pair, Samsung EasySetup), all at the same signal strength.
 *
 * Deliberately NOT flagged as attacks (too many false positives): an SSID served by several
 * BSSIDs (normal for mesh/enterprise), or simply many APs around (normal in a city).
 */
object WifiThreats {
    enum class Kind { DEAUTH_FLOOD, KARMA_AP, EVIL_TWIN_OWN, BEACON_FLOOD, BLE_SPAM }

    class Threat(
        val kind: Kind,
        /** 0..1 */
        val severity: Double,
        val count: Int,
        val ssid: String? = null,
        val bssid: MacAddress? = null,
        /** For KARMA: how many distinct SSIDs the AP answered for. */
        val distinctSsids: Int = 0,
        /** For EVIL_TWIN_OWN: the BSSIDs seen advertising your SSID. */
        val bssids: List<MacAddress> = emptyList(),
        val firstMs: Long = 0,
        val lastMs: Long = 0,
        /** Example network names involved (beacon flood, KARMA). */
        val ssids: List<String> = emptyList(),
        val channel: Int = 0,
        /** Median signal of the frames involved, dBm (0 = unknown). */
        val rssi: Int = 0,
        /** Beacon flood evidence: share of BSSIDs with the same beacon template, and distinct radios. */
        val templateShare: Double = 0.0,
        val radios: Int = 0,
    ) {
        /** Stable identity for snoozes, verdicts and notification cooldowns. */
        val key: String get() = "threat:$kind:${bssid ?: ""}:${ssid ?: ""}"
    }

    class Config(
        /**
         * A flood is judged by CONCENTRATION on one network, not the raw total: normal deauth/
         * disassoc traffic is sparse and spread across many BSSIDs, while an attack hammers one.
         * Minimum deauth+disassoc frames targeting a SINGLE BSSID in the window to call it an attack.
         */
        val deauthMinPerBssid: Int = 40,
        val deauthSaturation: Int = 400,
        /** AP answering probe requests for at least this many distinct SSIDs = KARMA. */
        val karmaMinSsids: Int = 5,
        val karmaSaturation: Int = 15,
        /** Beacon flood: networks first heard in the last [floodWindowMs], on one channel. */
        val floodWindowMs: Long = 60_000,
        val floodMinNew: Int = 25,
        val floodSaturation: Int = 120,
        /** Signal spread (std dev, dB) below which many sources look like ONE transmitter. */
        val oneTransmitterStdDb: Double = 6.0,
        /**
         * Beacon flood: the fake networks come from a transmitter near you. Real networks you walk
         * into range of appear at the edge of reception, all weak (and so all "the same" signal).
         */
        val floodMinMedianRssi: Int = -80,
        /** Share of the new networks that must share one beacon template (one tool, one radio). */
        val floodMinTemplateShare: Double = 0.6,
        /**
         * Distinct radios (BSSIDs grouped by their middle bytes) per new network. A multi-SSID
         * router derives its BSSIDs from one base address; a flood tool makes up a random one each time.
         */
        val floodMinRadioShare: Double = 0.6,
        /** ...or this many fake networks counted up from ONE base address (real routers serve ≤ 16). */
        val floodMinOneBase: Int = 20,
        /** BLE spam: distinct short-lived popup addresses in [spamWindowMs]. */
        val spamWindowMs: Long = 60_000,
        val spamMinAddresses: Int = 25,
        val spamSaturation: Int = 150,
        /** An address heard over a longer stretch than this is a real device, not spam. */
        val spamMaxLifeMs: Long = 10_000,
    )

    /**
     * @param wifi recent Wi-Fi sightings (the caller passes a short window, e.g. the last 2–5 min).
     * @param ownSsids the user's own network names (so a clone of one is a strong signal).
     * @param trustedBssids access points you confirmed for your networks: never counted as twins.
     */
    fun detect(wifi: List<Sighting>, ownSsids: Set<String>, cfg: Config = Config(), trustedBssids: Set<MacAddress> = emptySet()): List<Threat> {
        val out = ArrayList<Threat>()

        // ---- DEAUTH / DISASSOC flood: judged by concentration on one targeted network ----
        val deauths = wifi.filter { it.wifi?.kind == WifiKind.DEAUTH || it.wifi?.kind == WifiKind.DISASSOC }
        if (deauths.isNotEmpty()) {
            val byBssid = deauths.groupBy { it.wifi?.bssid }
            val top = byBssid.maxByOrNull { it.value.sumOf { s -> maxOf(1, s.mergedCount) } }!!
            val n = top.value.sumOf { maxOf(1, it.mergedCount) }
            if (n >= cfg.deauthMinPerBssid) {
                out += Threat(
                    Kind.DEAUTH_FLOOD,
                    severity = ((n - cfg.deauthMinPerBssid).toDouble() / (cfg.deauthSaturation - cfg.deauthMinPerBssid)).coerceIn(0.0, 1.0).coerceAtLeast(0.35),
                    count = n,
                    bssid = top.key,
                    rssi = median(top.value.map { it.rssi }),
                    channel = top.value.first().wifi?.channel ?: 0,
                    firstMs = top.value.minOf { it.timeMs },
                    lastMs = top.value.maxOf { it.timeMs },
                )
            }
        }

        // ---- KARMA / MANA: a BSSID answering probe requests for many SSIDs ----
        val probeResp = wifi.filter { it.wifi?.kind == WifiKind.PROBE_RESP }
        probeResp.groupBy { it.address }.forEach { (apMac, list) ->
            val ssids = list.mapNotNull { it.wifi?.ssidText?.takeIf { s -> s.isNotEmpty() } }.toSet()
            if (ssids.size >= cfg.karmaMinSsids) {
                out += Threat(
                    Kind.KARMA_AP,
                    severity = ((ssids.size - cfg.karmaMinSsids).toDouble() / (cfg.karmaSaturation - cfg.karmaMinSsids)).coerceIn(0.0, 1.0).coerceAtLeast(0.5),
                    count = list.sumOf { maxOf(1, it.mergedCount) },
                    bssid = apMac,
                    distinctSsids = ssids.size,
                    firstMs = list.minOf { it.timeMs },
                    lastMs = list.maxOf { it.timeMs },
                    ssids = ssids.take(20),
                    rssi = median(list.map { it.rssi }),
                )
            }
        }

        // ---- Evil twin of one of your own networks ----
        // Matched on the normalised name too: beacon-spam tools clone a network with trailing
        // spaces or invisible characters, which phones display as the same name (field data: 25
        // padded copies of the user's SSID from 144 random BSSIDs in one minute).
        if (ownSsids.isNotEmpty()) {
            val byNorm = ownSsids.associateBy { normaliseSsid(it) }
            val aps = wifi.mapNotNull { s ->
                val w = s.wifi ?: return@mapNotNull null
                if (w.kind != WifiKind.BEACON && w.kind != WifiKind.PROBE_RESP) return@mapNotNull null
                val own = if (w.ssidText in ownSsids) w.ssidText else byNorm[normaliseSsid(w.ssidText)] ?: return@mapNotNull null
                own to s
            }
            aps.groupBy({ it.first }, { it.second }).forEach { (ssid, list) ->
                val bssids = list.map { it.wifi?.bssid ?: it.address }.distinct()
                // Your own access points for this name (confirmed in Settings) are never the twin.
                val untrusted = bssids.filter { it !in trustedBssids }
                val lookalikes = list.mapNotNull { it.wifi?.ssidText }.filter { it != ssid }.distinct()
                val trustedKnown = bssids.any { it in trustedBssids }
                val fire = when {
                    lookalikes.isNotEmpty() -> true
                    trustedBssids.isEmpty() -> bssids.size >= 2 // nothing confirmed yet: the old rule
                    else -> untrusted.isNotEmpty() && (trustedKnown || untrusted.size >= 2)
                }
                if (fire) {
                    out += Threat(
                        Kind.EVIL_TWIN_OWN,
                        severity = 1.0,
                        count = list.sumOf { maxOf(1, it.mergedCount) },
                        ssid = ssid,
                        bssids = untrusted.ifEmpty { bssids },
                        firstMs = list.minOf { it.timeMs },
                        lastMs = list.maxOf { it.timeMs },
                        ssids = lookalikes.take(5).map { "“$it”" },
                        rssi = median(list.map { it.rssi }),
                        channel = list.mapNotNull { it.wifi?.channel }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0,
                    )
                }
            }
        }

        beaconFlood(wifi, cfg)?.let { out += it }
        return out.sortedByDescending { it.severity }
    }

    /** How a phone shows a network name: surrounding spaces, invisible characters and case don't count. */
    fun normaliseSsid(s: String): String =
        s.filterNot { it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' || it == '\u00A0' || it.isISOControl() }
            .trim().lowercase()

    private fun median(v: List<Int>): Int {
        val x = v.filter { it != 0 }.sorted()
        return if (x.isEmpty()) 0 else x[x.size / 2]
    }

    /**
     * Beacon template: the information elements in order with their sizes, without the fields that
     * differ per network (SSID, channel, TIM). One flood tool sends one template for every fake.
     */
    internal fun template(ies: ByteArray): String? {
        if (ies.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i + 2 <= ies.size) {
            val id = ies[i].toInt() and 0xFF
            val len = ies[i + 1].toInt() and 0xFF
            if (i + 2 + len > ies.size) break
            when (id) {
                0, 3, 5 -> Unit
                221 -> if (len >= 4) sb.append("v").append("%02x%02x%02x%02x".format(ies[i + 2], ies[i + 3], ies[i + 4], ies[i + 5])).append(':').append(len).append(',')
                else -> sb.append(id).append(':').append(len).append(',')
            }
            i += 2 + len
        }
        return sb.toString()
    }

    private fun std(v: List<Int>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    /** Many networks appearing at once on one channel at one signal level = one fake transmitter. */
    private fun beaconFlood(wifi: List<Sighting>, cfg: Config): Threat? {
        val beacons = wifi.filter { it.wifi?.kind == WifiKind.BEACON }
        if (beacons.isEmpty()) return null
        val end = beacons.maxOf { it.timeMs }
        // Need a "before" to call networks new: right after start-up everything is new.
        if (end - beacons.minOf { it.timeMs } < 2 * cfg.floodWindowMs) return null
        val firstSeen = HashMap<MacAddress, Sighting>()
        for (b in beacons.sortedBy { it.timeMs }) firstSeen.putIfAbsent(b.wifi?.bssid ?: b.address, b)
        val fresh = firstSeen.values.filter { end - it.timeMs <= cfg.floodWindowMs }
        val best = fresh.groupBy { it.wifi!!.channel }.maxByOrNull { it.value.size }?.value ?: return null
        if (best.size < cfg.floodMinNew) return null
        val ssids = best.mapNotNull { it.wifi?.ssidText?.takeIf { s -> s.isNotEmpty() } }.toSet()
        if (ssids.size < cfg.floodMinNew / 2) return null
        if (std(best.map { it.rssi }.filter { it != 0 }) > cfg.oneTransmitterStdDb) return null
        // Walking into range of real networks also makes many appear at once on one channel, all
        // equally weak. Three checks tell a flood tool apart (field report: city centre at walking pace).
        val med = median(best.map { it.rssi })
        if (med == 0 || med < cfg.floodMinMedianRssi) return null
        val templates = best.mapNotNull { it.wifi?.ies?.let { ies -> template(ies) } }
        if (templates.size < best.size / 2) return null
        val topShare = templates.groupingBy { it }.eachCount().maxOf { it.value }.toDouble() / best.size
        if (topShare < cfg.floodMinTemplateShare) return null
        val bssids = best.map { it.wifi?.bssid ?: it.address }
        val families = bssids.groupingBy { (it.bits ushr 8) and 0xFFFFFFFFL }.eachCount()
        val radios = families.size
        // Random fake BSSIDs: many radios. Some tools instead count up from one base address
        // (more names than any real multi-SSID router serves). Real routers: neither.
        if (radios < cfg.floodMinRadioShare * best.size && families.values.max() < cfg.floodMinOneBase) return null
        return Threat(
            Kind.BEACON_FLOOD,
            severity = ((best.size - cfg.floodMinNew).toDouble() / (cfg.floodSaturation - cfg.floodMinNew)).coerceIn(0.0, 1.0).coerceAtLeast(0.6),
            count = best.size,
            distinctSsids = ssids.size,
            bssids = bssids.take(30),
            firstMs = best.minOf { it.timeMs },
            lastMs = best.maxOf { it.timeMs },
            ssids = ssids.take(20).toList(),
            channel = best.first().wifi!!.channel,
            rssi = med,
            templateShare = topShare,
            radios = radios,
        )
    }

    /** Pairing-popup advertisement: the payloads BLE spam tools cycle through. */
    fun isPopupAdvert(info: AdvertisementInfo): Boolean {
        val md = info.manufacturerData
        val t = md?.firstOrNull()?.toInt()?.and(0xFF)
        return when (info.manufacturerId) {
            0x004C -> t == 0x07 || t == 0x0F // Apple Proximity Pairing / Nearby Action
            0x0006 -> t == 0x03 // Microsoft Swift Pair beacon
            0x0075 -> md != null && md.size >= 2 && t == 0x42 && (md[1].toInt() and 0xFF) == 0x09 // Samsung EasySetup
            else -> false
        } || 0xFE2C in info.serviceData16.keys // Google Fast Pair
    }

    /**
     * BLE spam: many random addresses, each alive only a few seconds, all sending popup adverts
     * at nearly the same signal. A crowd of real AirPods has stable addresses and spread signals.
     */
    fun detectBle(ble: List<Sighting>, cfg: Config = Config()): List<Threat> =
        // Each receiver separately: signal levels from different antennas are not comparable.
        ble.groupBy { it.probeId }.values.flatMap { detectBleOne(it, cfg) }.sortedByDescending { it.count }.take(1)

    private fun detectBleOne(ble: List<Sighting>, cfg: Config): List<Threat> {
        if (ble.isEmpty()) return emptyList()
        val end = ble.maxOf { it.timeMs }
        val byAddr = ble.filter { end - it.timeMs <= cfg.spamWindowMs && it.ble != null }
            .groupBy { it.address }
        val shortPopups = byAddr.filter { (_, l) ->
            l.maxOf { it.timeMs } - l.minOf { it.timeMs } <= cfg.spamMaxLifeMs &&
                l.any { isPopupAdvert(AdvertisementInfo.of(it.ble!!.advData)) }
        }
        if (shortPopups.size < cfg.spamMinAddresses) return emptyList()
        val rssi = shortPopups.values.map { l -> l.maxOf { it.rssi } }.filter { it != 0 }
        if (std(rssi) > cfg.oneTransmitterStdDb) return emptyList()
        val all = shortPopups.values.flatten()
        return listOf(
            Threat(
                Kind.BLE_SPAM,
                severity = ((shortPopups.size - cfg.spamMinAddresses).toDouble() / (cfg.spamSaturation - cfg.spamMinAddresses)).coerceIn(0.0, 1.0).coerceAtLeast(0.6),
                count = shortPopups.size,
                firstMs = all.minOf { it.timeMs },
                lastMs = all.maxOf { it.timeMs },
            ),
        )
    }
}
