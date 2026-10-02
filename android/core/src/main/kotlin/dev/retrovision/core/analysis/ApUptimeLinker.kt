package dev.retrovision.core.analysis

import dev.retrovision.core.model.WifiKind

/**
 * Recognises one access point that changed its name or address, from its uptime.
 *
 * Every beacon carries the AP's TSF timer: microseconds since that radio started. So
 * "reception time − TSF" is the moment the AP booted, a constant until it reboots. A phone
 * hotspot or car Wi-Fi that is renamed, or that rotates its BSSID, keeps the same boot moment;
 * a different AP almost never matches it to within a couple of seconds.
 *
 * To keep coincidences out (thousands of APs in a city, some booted at similar moments), two APs
 * are linked only when one REPLACES the other: the second appears after the first went silent,
 * within [maxHandoverMs], never overlapping. Several SSIDs served by one radio at the same time
 * share a TSF but overlap in time, so they are not merged.
 */
object ApUptimeLinker {
    const val MAX_BOOT_DIFF_MS = 2_000L
    const val MAX_HANDOVER_MS = 30 * 60_000L
    const val MAX_OVERLAP_MS = 60_000L

    class Links(val root: Map<String, String>, val renamed: Map<String, Pair<String, String>>)

    private class Ap(val id: String, val boot: Long, val first: Long, val last: Long, val ssidFirst: String, val ssidLast: String)

    fun link(sightings: List<EntitySighting>): Links {
        val boots = HashMap<String, MutableList<Long>>()
        val span = HashMap<String, LongArray>()
        val firstName = HashMap<String, String>()
        val lastName = HashMap<String, String>()
        for (es in sightings) {
            val w = es.sighting.wifi ?: continue
            if ((w.kind != WifiKind.BEACON && w.kind != WifiKind.PROBE_RESP) || w.tsfUs <= 0) continue
            val t = es.sighting.timeMs
            boots.getOrPut(es.entityId) { ArrayList() } += t - w.tsfUs / 1000
            val sp = span.getOrPut(es.entityId) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
            if (t < sp[0]) { sp[0] = t; firstName[es.entityId] = w.ssidText }
            if (t >= sp[1]) { sp[1] = t; lastName[es.entityId] = w.ssidText }
        }
        val aps = boots.mapNotNull { (id, b) ->
            if (b.size < 2) return@mapNotNull null
            val sorted = b.sorted()
            val med = sorted[sorted.size / 2]
            // An AP that rebooted inside the window has two boot moments: keep only consistent ones.
            if (sorted.last() - sorted.first() > 10 * MAX_BOOT_DIFF_MS) return@mapNotNull null
            val sp = span[id]!!
            Ap(id, med, sp[0], sp[1], firstName[id].orEmpty(), lastName[id].orEmpty())
        }.sortedBy { it.first }
        val root = HashMap<String, String>()
        val renamed = HashMap<String, Pair<String, String>>()
        fun find(x: String): String { var y = x; while (root[y] != null && root[y] != y) y = root[y]!!; return y }
        for (i in aps.indices) for (j in i + 1 until aps.size) {
            val a = aps[i]; val b = aps[j]
            if (kotlin.math.abs(a.boot - b.boot) > MAX_BOOT_DIFF_MS) continue
            val replaces = b.first >= a.last - MAX_OVERLAP_MS && b.first - a.last <= MAX_HANDOVER_MS
            if (!replaces) continue
            val ra = find(a.id); val rb = find(b.id)
            if (ra == rb) continue
            root[rb] = ra
            root.putIfAbsent(ra, ra)
            renamed[ra] = (renamed[ra]?.first ?: a.ssidFirst) to b.ssidLast
        }
        val flat = HashMap<String, String>()
        for (id in root.keys) flat[id] = find(id)
        return Links(flat, renamed)
    }
}
