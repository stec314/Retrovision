// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

/**
 * Devices that move together: heard at the same places at overlapping times, at most of the places
 * either of them was heard. A phone, a watch and earbuds on one person; a car's head unit, its
 * tyre sensors and the driver's phone; two people walking together. Shown as a group, not scored:
 * the scoring has its own, stricter group rule near the alert threshold ([Reason.TravelsInGroup]).
 *
 * Only your own places count, so "together" means "together while near you". Fixed devices (a
 * router, a device heard in one area only) are left out: everything heard in your home is
 * "together" with your router.
 */
object CoTravel {
    class Input(val id: String, val visits: List<DeviceVisit>)

    class Group(
        val id: String,
        val members: List<String>,
        /** Places where at least two members were heard at the same time. */
        val sharedPlaces: Int,
        val firstMs: Long,
        val lastMs: Long,
    )

    /** Minimum places two devices must share, at the same time, to be grouped. */
    const val MIN_SHARED = 2
    /** Share of the smaller device's places that must be shared. */
    const val MIN_SHARE_SMALL = 0.7
    /** Share of the larger device's places that must be shared (both go mostly together). */
    const val MIN_SHARE_LARGE = 0.5
    /** Two visits at one place count as "at the same time" when they overlap within this. */
    const val SLACK_MS = 5 * 60_000L
    /** A crowded place (station, mall) gives no pairs: everyone there would be "together". */
    const val MAX_AT_PLACE = 400
    const val MAX_GROUP = 12

    fun find(inputs: List<Input>): List<Group> {
        val eligible = inputs.filter { inp -> inp.visits.asSequence().filter { it.placeId >= 0 }.map { it.placeId }.distinct().count() >= MIN_SHARED }
        if (eligible.size < 2) return emptyList()
        val placesOf = IntArray(eligible.size) { i -> eligible[i].visits.asSequence().filter { it.placeId >= 0 }.map { it.placeId }.distinct().count() }

        // place -> visits there (device index, start, end)
        val byPlace = HashMap<Int, MutableList<LongArray>>()
        eligible.forEachIndexed { i, inp ->
            for (v in inp.visits) if (v.placeId >= 0) byPlace.getOrPut(v.placeId) { ArrayList() } += longArrayOf(i.toLong(), v.startMs - SLACK_MS, v.endMs + SLACK_MS)
        }
        // pair -> places shared at the same time
        val shared = HashMap<Long, Int>()
        val n = eligible.size.toLong()
        for ((_, list) in byPlace) {
            if (list.map { it[0] }.distinct().size > MAX_AT_PLACE) continue
            list.sortBy { it[1] }
            val pairsHere = HashSet<Long>()
            for (a in list.indices) {
                val va = list[a]
                var b = a + 1
                while (b < list.size && list[b][1] <= va[2]) {
                    val vb = list[b]
                    if (vb[0] != va[0]) {
                        val lo = minOf(va[0], vb[0]); val hi = maxOf(va[0], vb[0])
                        pairsHere += lo * n + hi
                    }
                    b++
                }
            }
            for (p in pairsHere) shared[p] = (shared[p] ?: 0) + 1
        }

        val parent = IntArray(eligible.size) { it }
        fun root(x: Int): Int { var y = x; while (parent[y] != y) { parent[y] = parent[parent[y]]; y = parent[y] }; return y }
        val strength = HashMap<Int, Int>()
        for ((p, k) in shared) {
            if (k < MIN_SHARED) continue
            val a = (p / n).toInt(); val b = (p % n).toInt()
            val small = minOf(placesOf[a], placesOf[b]).toDouble()
            val large = maxOf(placesOf[a], placesOf[b]).toDouble()
            if (k / small < MIN_SHARE_SMALL || k / large < MIN_SHARE_LARGE) continue
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[ra] = rb
            strength[a] = maxOf(strength[a] ?: 0, k); strength[b] = maxOf(strength[b] ?: 0, k)
        }
        val clusters = eligible.indices.filter { it in strength }.groupBy { root(it) }.values.filter { it.size >= 2 }
        return clusters.map { idx ->
            // A chain that grew past a handful of devices is a crowd moving with you (a bus), not one
            // person: keep the most strongly linked members.
            val keep = idx.sortedByDescending { strength[it] ?: 0 }.take(MAX_GROUP)
            val ids = keep.map { eligible[it].id }.sorted()
            val visits = keep.flatMap { eligible[it].visits }
            Group(ids.first(), ids, keep.maxOf { strength[it] ?: 0 }, visits.minOf { it.startMs }, visits.maxOf { it.endMs })
        }.sortedByDescending { it.sharedPlaces }
    }
}
