// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.WifiKind

/**
 * Links randomised Wi-Fi addresses that ask for the same RARE networks by name.
 *
 * A phone's list of remembered networks ("Hotel_Rimini", "Casa_Bianchi") survives MAC
 * rotation and even days. Two addresses that both ask for two or more networks almost nobody
 * else around asks for are very likely the same device. Common names (eduroam, a chain's
 * hotspot, an airport) are not rare and never link anything.
 *
 * Limits: modern phones ask by name mostly for hidden networks, so most devices give nothing to
 * link on. People sharing a home or office can share rare names: you'll see that as a link
 * between two of your household's devices — the reason is shown, judge it.
 */
object NetworkLinker {
    class Links(
        /** entityId -> root entityId of its linked group (only for linked entities). */
        val root: Map<String, String>,
        /** root -> number of rare networks that tied the group together. */
        val shared: Map<String, Int>,
    )

    /** A network asked for by at most this many devices in the window counts as rare. */
    const val MAX_ASKERS = 3
    const val MIN_SHARED_RARE = 2
    const val MIN_JACCARD = 0.5

    fun link(sightings: List<EntitySighting>, ownSsids: Set<String> = emptySet()): Links {
        val asked = HashMap<String, MutableSet<String>>()
        for (es in sightings) {
            val w = es.sighting.wifi ?: continue
            if (w.kind != WifiKind.PROBE_REQ || !es.sighting.address.isLocallyAdministered) continue
            val t = w.ssidText
            if (t.isNotEmpty() && !t.startsWith("<hex:")) asked.getOrPut(es.entityId) { HashSet() } += t
        }
        val askers = HashMap<String, Int>()
        for (set in asked.values) for (ssid in set) askers[ssid] = (askers[ssid] ?: 0) + 1
        val ids = asked.keys.filter { asked[it]!!.size >= MIN_SHARED_RARE }.sorted()
        val parent = HashMap<String, String>()
        fun find(x: String): String { var y = x; while (parent[y] != null && parent[y] != y) y = parent[y]!!; return y }
        val sharedBy = HashMap<String, Int>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = asked[ids[i]]!!; val b = asked[ids[j]]!!
            val common = a.intersect(b)
            val rare = common.filter { (askers[it] ?: 0) <= MAX_ASKERS }
            // At least one rare name must not be one of your own networks (your household shares those).
            if (rare.size < MIN_SHARED_RARE || rare.all { it in ownSsids }) continue
            if (common.size.toDouble() / a.union(b).size < MIN_JACCARD) continue
            val ra = find(ids[i]); val rb = find(ids[j])
            if (ra != rb) {
                val (keep, drop) = if (ra < rb) ra to rb else rb to ra
                parent[drop] = keep
                parent.putIfAbsent(keep, keep)
                sharedBy[keep] = maxOf(sharedBy[keep] ?: 0, sharedBy[drop] ?: 0, rare.size)
            }
        }
        val root = HashMap<String, String>()
        for (id in parent.keys) root[id] = find(id)
        val shared = HashMap<String, Int>()
        for (r in root.values.toSet()) shared[r] = sharedBy[r] ?: MIN_SHARED_RARE
        return Links(root, shared)
    }
}
