// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.probe

/**
 * Wi-Fi channel plans the user can pick. The probe hears one channel at a time, so every extra
 * channel takes listening time away from the others. What can move with you on Wi-Fi is a phone
 * (probe requests: only on 2.4 GHz and the non-DFS 5 GHz channels) or a portable hotspot (phone or
 * car: any 2.4 GHz channel, 5 GHz non-DFS; almost never DFS, which needs radar detection).
 *
 * - FOCUSED (0, default): 2.4 GHz 1/6/11 three times a cycle, plus 3 and 9 once (a 2.4 GHz receiver
 *   also decodes neighbouring channels, so this covers hotspots on 1..11), and on dual-band probes
 *   the 5 GHz channels where phones may probe (36-48, 149-165). No DFS. ~2.5 s.
 * - BALANCED (1): adds 2.4 GHz 4, 8, 13 and six DFS channels; ~3.3 s.
 * - FULL (2): every channel the probe can tune; ~4.6 s on the C5. For mapping a place's networks.
 *
 * 5 GHz entries are sent only to dual-band probes.
 */
object ChannelPlans {
    private val primaries = listOf(1 to 150, 6 to 150, 11 to 150)
    private val low5 = listOf(36 to 110, 40 to 110, 44 to 110, 48 to 110)
    private val high5 = listOf(149 to 110, 153 to 110, 157 to 110, 161 to 110, 165 to 90)

    fun hops(plan: Int, dualBand: Boolean = true): List<Pair<Int, Int>> {
        val all = when (plan) {
            1 -> primaries + low5 + listOf(3 to 100, 4 to 100) + primaries + high5 + listOf(8 to 100, 9 to 100, 13 to 100) +
                primaries + listOf(52 to 80, 60 to 80, 100 to 80, 116 to 80, 132 to 80, 140 to 80)
            2 -> primaries + listOf(2, 3, 4, 5).map { it to 100 } + low5 + primaries + listOf(7, 8, 9, 10, 12, 13).map { it to 100 } + high5 +
                primaries + listOf(52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144).map { it to 80 }
            else -> primaries + low5 + listOf(3 to 80) + primaries + high5 + listOf(9 to 80) + primaries
        }
        return if (dualBand) all else all.filter { it.first <= 14 }
    }

    private val probeChannels = setOf(1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161, 165)

    /** Share of the cycle spent on the channels where phones send probe requests (dual-band). */
    fun probeShare(plan: Int): Double {
        val h = hops(plan)
        return h.filter { it.first in probeChannels }.sumOf { it.second }.toDouble() / h.sumOf { it.second }
    }

    fun cycleMs(plan: Int): Int = hops(plan).sumOf { it.second }

    private val primaryChannels = setOf(1, 6, 11)

    /**
     * Splits [plan] between several probes listening at the same time ([dual] = each probe can tune
     * 5 GHz), so each one dwells longer on fewer channels instead of all of them hopping the same list.
     * 5 GHz goes to dual-band probes only; 2.4 GHz goes to the single-band ones if there are any
     * (the dual-band ones then stay on 5 GHz), otherwise it is shared too. The busy channels 1/6/11
     * are dealt first so they spread out, and keep a double dwell. A probe never ends up idle.
     */
    fun split(plan: Int, dual: List<Boolean>): List<List<Pair<Int, Int>>> {
        if (dual.size <= 1) return dual.map { hops(plan, it) }
        val distinct = LinkedHashMap<Int, Int>()
        hops(plan, true).forEach { (c, d) -> distinct.putIfAbsent(c, d) }
        val ch24 = distinct.entries.filter { it.key <= 14 }.map { it.key to it.value }
            .sortedBy { if (it.first in primaryChannels) 0 else 1 }
        val ch5 = distinct.entries.filter { it.key > 14 }.map { it.key to it.value }
        val dualIdx = dual.indices.filter { dual[it] }
        val singleIdx = dual.indices.filter { !dual[it] }
        val out = List(dual.size) { mutableListOf<Pair<Int, Int>>() }
        if (dualIdx.isNotEmpty()) ch5.forEachIndexed { i, c -> out[dualIdx[i % dualIdx.size]] += c }
        val takers = singleIdx.ifEmpty { dualIdx }
        ch24.forEachIndexed { i, (c, d) -> out[takers[i % takers.size]] += c to (if (c in primaryChannels) d * 2 else d) }
        return out.mapIndexed { i, l -> if (l.isEmpty()) hops(plan, dual[i]) else l.sortedBy { it.first } }
    }
}
