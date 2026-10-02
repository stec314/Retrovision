package dev.retrovision.core.analysis

import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.WifiKind
import dev.retrovision.core.model.Sighting

/**
 * From captured DATA frames, maps each access point to the client devices talking to it.
 * This finds devices that never send probe requests (already connected, silent) — the most
 * invasive capture, which is why it is off by default.
 *
 * A client→AP data frame carries addr2 = client (stored as [Sighting.address]) and
 * addr3 = BSSID (stored as [dev.retrovision.core.model.WifiDetail.bssid]).
 */
object AssociatedClients {
    class Ap(
        val bssid: MacAddress,
        val ssid: String?,
        val clients: Set<MacAddress>,
        val frames: Int,
    )

    fun of(wifi: List<Sighting>): List<Ap> {
        val clientsByAp = HashMap<MacAddress, MutableSet<MacAddress>>()
        val framesByAp = HashMap<MacAddress, Int>()
        for (s in wifi) {
            val w = s.wifi ?: continue
            if (w.kind != WifiKind.DATA) continue
            val ap = w.bssid ?: continue
            val client = s.address
            if (client == ap || client.isMulticast || client.isBroadcast) continue
            clientsByAp.getOrPut(ap) { HashSet() }.add(client)
            framesByAp[ap] = (framesByAp[ap] ?: 0) + maxOf(1, s.mergedCount)
        }
        // SSID for each AP, from any beacon/probe-resp seen for that BSSID.
        val ssidByAp = HashMap<MacAddress, String>()
        for (s in wifi) {
            val w = s.wifi ?: continue
            if (w.kind == WifiKind.BEACON || w.kind == WifiKind.PROBE_RESP) {
                val b = w.bssid ?: s.address
                val t = w.ssidText
                if (t.isNotEmpty()) ssidByAp.putIfAbsent(b, t)
            }
        }
        return clientsByAp.map { (ap, clients) ->
            Ap(ap, ssidByAp[ap], clients, framesByAp[ap] ?: 0)
        }.sortedByDescending { it.clients.size }
    }
}
