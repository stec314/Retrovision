package dev.retrovision.core.analysis

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
 *
 * Deliberately NOT flagged as attacks (too many false positives): an SSID served by several
 * BSSIDs (normal for mesh/enterprise), or simply many APs around (normal in a city).
 */
object WifiThreats {
    enum class Kind { DEAUTH_FLOOD, KARMA_AP, EVIL_TWIN_OWN }

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
    )

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
    )

    /**
     * @param wifi recent Wi-Fi sightings (the caller passes a short window, e.g. the last 2–5 min).
     * @param ownSsids the user's own network names (so a clone of one is a strong signal).
     */
    fun detect(wifi: List<Sighting>, ownSsids: Set<String>, cfg: Config = Config()): List<Threat> {
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
                )
            }
        }

        // ---- Evil twin of one of your own networks ----
        if (ownSsids.isNotEmpty()) {
            val aps = wifi.filter {
                val w = it.wifi ?: return@filter false
                (w.kind == WifiKind.BEACON || w.kind == WifiKind.PROBE_RESP) && w.ssidText in ownSsids
            }
            aps.groupBy { it.wifi!!.ssidText }.forEach { (ssid, list) ->
                val bssids = list.mapNotNull { it.wifi?.bssid ?: it.address.takeIf { _ -> it.wifi?.bssid == null } }
                    .ifEmpty { list.map { it.address } }
                    .distinct()
                if (bssids.size >= 2) {
                    out += Threat(
                        Kind.EVIL_TWIN_OWN,
                        severity = 1.0,
                        count = list.sumOf { maxOf(1, it.mergedCount) },
                        ssid = ssid,
                        bssids = bssids,
                        firstMs = list.minOf { it.timeMs },
                        lastMs = list.maxOf { it.timeMs },
                    )
                }
            }
        }

        return out.sortedByDescending { it.severity }
    }
}
