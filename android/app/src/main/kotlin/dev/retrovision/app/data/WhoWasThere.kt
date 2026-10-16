// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import dev.retrovision.core.analysis.Geo

/**
 * "Who was there": the devices your receivers heard during a stay, or every time you were at a
 * place over some days; and the ones heard at two of them (the intersection). Everything your
 * receivers hear is around you, so a place is turned into the times you were there (from your
 * GPS track) and the question becomes "what was heard then".
 */
object WhoWasThere {
    sealed class Slot {
        abstract val label: String

        /** One stretch of time (a stay of the timeline). */
        class Time(val fromMs: Long, val toMs: Long, override val label: String) : Slot()

        /** Every time you were within [radiusM] of a point over [days] days. */
        class Area(val lat: Double, val lon: Double, val radiusM: Double, val days: Int, override val label: String) : Slot()
    }

    class Hit(val entityId: String, val n: Int, val firstMs: Long, val lastMs: Long, val maxRssi: Int)

    class Result(val hits: List<Hit>, val intervals: Int)

    private const val PAD_MS = 2 * 60_000L
    private const val MAX_INTERVALS = 300

    /** The times covered by a slot, merged, at most [MAX_INTERVALS]. */
    suspend fun intervals(dao: AppDao, slot: Slot, now: Long = System.currentTimeMillis()): List<LongArray> = when (slot) {
        is Slot.Time -> listOf(longArrayOf(slot.fromMs, slot.toMs))
        is Slot.Area -> {
            val from = now - slot.days * 86_400_000L
            val fixes = dao.fixesThinned(from, now, 30_000L)
            val out = ArrayList<LongArray>()
            for (f in fixes) {
                if (Geo.distanceM(f.lat, f.lon, slot.lat, slot.lon) > slot.radiusM + f.accuracyM.coerceAtMost(50f)) continue
                val last = out.lastOrNull()
                if (last != null && f.timeMs - PAD_MS <= last[1]) last[1] = f.timeMs + PAD_MS
                else out += longArrayOf(f.timeMs - PAD_MS, f.timeMs + PAD_MS)
            }
            if (out.size > MAX_INTERVALS) out.subList(out.size - MAX_INTERVALS, out.size).toList() else out
        }
    }

    /** Devices heard during [slot], your own (ignored) devices left out, most often heard first. */
    suspend fun devices(dao: AppDao, slot: Slot): Result {
        val iv = intervals(dao, slot)
        val mine = dao.ignoresNow().map { it.entityId }.toSet()
        val acc = HashMap<String, Hit>()
        for (i in iv) {
            for (e in dao.entitiesBetween(i[0], i[1])) {
                if (e.entityId in mine) continue
                val p = acc[e.entityId]
                acc[e.entityId] = if (p == null) Hit(e.entityId, e.n, e.firstMs, e.lastMs, e.maxRssi)
                else Hit(e.entityId, p.n + e.n, minOf(p.firstMs, e.firstMs), maxOf(p.lastMs, e.lastMs), maxOf(p.maxRssi, e.maxRssi))
            }
        }
        return Result(acc.values.sortedByDescending { it.n }, iv.size)
    }

    /** Devices heard during both slots. */
    suspend fun both(dao: AppDao, a: Slot, b: Slot): Result {
        val ra = devices(dao, a)
        val rb = devices(dao, b).hits.associateBy { it.entityId }
        val hits = ra.hits.mapNotNull { h ->
            val o = rb[h.entityId] ?: return@mapNotNull null
            Hit(h.entityId, h.n + o.n, minOf(h.firstMs, o.firstMs), maxOf(h.lastMs, o.lastMs), maxOf(h.maxRssi, o.maxRssi))
        }
        return Result(hits.sortedByDescending { it.n }, ra.intervals)
    }
}
