// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.probe

/**
 * Wi-Fi channel plans the user can pick. The trade-off is time: the probe hears one channel at a
 * time, so every extra channel takes listening time away from the others.
 *
 * - FOCUSED (0): the probe's own default. On the C5: 2.4 GHz 1/6/11, the 5 GHz channels where
 *   phones may send probe requests (36-48, 149-165) and three DFS channels; ~2.6 s per cycle.
 *   On 2.4 GHz-only probes, all 13 channels with 1/6/11 favoured.
 * - BALANCED (1): adds 2.4 GHz 3, 4, 8, 9, 13 (access points far from 1/6/11) and six DFS
 *   channels; ~3.3 s.
 * - FULL (2): every channel the probe can tune, once per cycle (primaries three times); ~4.6 s on
 *   the C5. Probes skip the 5 GHz entries they cannot tune.
 */
object ChannelPlans {
    private val focused24 = listOf(1 to 150, 6 to 150, 11 to 150)
    private val low5 = listOf(36 to 110, 40 to 110, 44 to 110, 48 to 110)
    private val high5 = listOf(149 to 110, 153 to 110, 157 to 110, 161 to 110, 165 to 90)

    fun hops(plan: Int): List<Pair<Int, Int>> = when (plan) {
        1 -> focused24 + low5 + listOf(3 to 100, 4 to 100) + focused24 + high5 + listOf(8 to 100, 9 to 100, 13 to 100) +
            focused24 + listOf(52 to 80, 60 to 80, 100 to 80, 116 to 80, 132 to 80, 140 to 80)
        2 -> focused24 + listOf(2, 3, 4, 5).map { it to 100 } + low5 + focused24 + listOf(7, 8, 9, 10, 12, 13).map { it to 100 } + high5 +
            focused24 + listOf(52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144).map { it to 80 }
        else -> emptyList() // the probe's own default
    }

    /** Share of the cycle spent on the channels where phones send probe requests. */
    fun probeShare(plan: Int): Double {
        val h = hops(plan).ifEmpty { return 0.91 }
        val probeCh = setOf(1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161, 165)
        return h.filter { it.first in probeCh }.sumOf { it.second }.toDouble() / h.sumOf { it.second }
    }

    fun cycleMs(plan: Int): Int = hops(plan).sumOf { it.second }.takeIf { it > 0 } ?: 2560
}
