// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
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
 *
 * Shared boot moments are not rare: after a neighbourhood power cut every router restarts within
 * seconds (field data: dozens of routers of four providers in one city centre booted within 25 s,
 * and chained ±2 s links merged them into one "renamed AP" that seemed to follow you), and a
 * beacon-spam tool sends hundreds of BSSIDs from one radio. So no link is made when 3 or more
 * different radios booted within [CROWD_WINDOW_MS] of each other, or when an AP could be replaced
 * by more than one candidate.
 */
object ApUptimeLinker {
    const val MAX_BOOT_DIFF_MS = 2_000L
    const val MAX_HANDOVER_MS = 30 * 60_000L
    const val MAX_OVERLAP_MS = 60_000L
    const val CROWD_WINDOW_MS = 60_000L
    const val CROWD_RADIOS = 3

    /** One physical radio's BSSIDs differ in the first and last octet; the middle three stay. */
    private fun radioFamily(id: String): String {
        val h = id.substringAfter(':').split(':')
        return if (h.size == 6) h[2] + h[3] + h[4] else id
    }

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
        // Candidate pairs come from a sweep over boot moments (n log n; a busy window holds thousands
        // of access points), then are applied in first-seen order exactly as an all-pairs scan would.
        val byBoot = aps.indices.sortedBy { aps[it].boot }
        val pairs = ArrayList<Long>()
        for (x in byBoot.indices) {
            var y = x + 1
            while (y < byBoot.size && aps[byBoot[y]].boot - aps[byBoot[x]].boot <= MAX_BOOT_DIFF_MS) {
                val i = minOf(byBoot[x], byBoot[y]); val j = maxOf(byBoot[x], byBoot[y])
                pairs += (i.toLong() shl 32) or j.toLong()
                y++
            }
        }
        pairs.sort()
        // Crowded boot moments: how many different radios booted within the crowd window.
        val crowded = BooleanArray(aps.size)
        var lo = 0
        for (x in byBoot.indices) {
            val bx = aps[byBoot[x]].boot
            while (bx - aps[byBoot[lo]].boot > CROWD_WINDOW_MS) lo++
            var hi = x
            while (hi + 1 < byBoot.size && aps[byBoot[hi + 1]].boot - bx <= CROWD_WINDOW_MS) hi++
            val fams = HashSet<String>()
            for (k in lo..hi) fams += radioFamily(aps[byBoot[k]].id)
            if (fams.size >= CROWD_RADIOS) crowded[byBoot[x]] = true
        }
        val candidates = HashMap<Int, Int>()
        val valid = ArrayList<Long>()
        for (p in pairs) {
            val i = (p ushr 32).toInt(); val j = (p and 0xFFFFFFFFL).toInt()
            if (crowded[i] || crowded[j]) continue
            val a = aps[i]; val b = aps[j]
            if (b.first >= a.last - MAX_OVERLAP_MS && b.first - a.last <= MAX_HANDOVER_MS) {
                valid += p
                candidates[i] = (candidates[i] ?: 0) + 1
            }
        }
        for (p in valid) {
            val i = (p ushr 32).toInt()
            if ((candidates[i] ?: 0) > 1) continue // ambiguous: more than one AP could have replaced it
            val a = aps[i]; val b = aps[(p and 0xFFFFFFFFL).toInt()]
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
