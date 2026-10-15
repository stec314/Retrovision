// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.VisitTimeline
import dev.retrovision.core.model.GeoFix

/** The data behind the charts (timeline, coverage, signal, week-hour, places matrix). UI-free. */
object ChartsData {
    // ---- timeline -----------------------------------------------------------------------

    /** A device to draw as a lane: the ids its sightings are stored under. */
    class LaneSpec(val key: String, val ids: List<String>, val label: String, val flagged: Boolean)

    class Lane(val spec: LaneSpec, val marks: List<LongArray>)

    /** One of your stays: [routine] = at a confirmed routine place. */
    class Band(val fromMs: Long, val toMs: Long, val routine: Boolean, val lat: Double, val lon: Double)

    /** Per bucket of [bucketMs] from [fromMs]: was each receiver listening, and was there a good GPS fix. */
    class Coverage(val fromMs: Long, val bucketMs: Long, val probe: BooleanArray, val phone: BooleanArray, val gps: BooleanArray)

    class Timeline(val fromMs: Long, val toMs: Long, val lanes: List<Lane>, val bands: List<Band>, val coverage: Coverage)

    suspend fun timeline(
        dao: AppDao, fromMs: Long, toMs: Long, specs: List<LaneSpec>, familiar: List<FamiliarPlace>, maxFixAccuracyM: Int,
    ): Timeline {
        val span = toMs - fromMs
        val markBucket = maxOf(60_000L, span / 300)
        val lanes = specs.map { sp -> Lane(sp, dao.heardBucketsBetween(sp.ids, fromMs, toMs, markBucket, 2000).map { longArrayOf(it.t0, maxOf(it.t1, it.t0 + markBucket / 2)) }) }
        val fixes = dao.fixesThinned(fromMs, toMs, 30_000L).map { it.toFix() }
        val confirmed = familiar.filter { it.state == FamiliarPlace.State.CONFIRMED }
        val bands = VisitTimeline.build(fixes).map { v -> Band(v.startMs, v.endMs, confirmed.any { it.contains(v.lat, v.lon) }, v.lat, v.lon) }

        val covBucket = maxOf(60_000L, span / 200)
        val n = ((span + covBucket - 1) / covBucket).toInt()
        val probe = BooleanArray(n); val phone = BooleanArray(n); val gps = BooleanArray(n)
        val k0 = fromMs / covBucket
        val stride = when { span > 2 * 86_400_000L -> 7; span > 8 * 3_600_000L -> 3; else -> 1 }
        for (b in dao.receiverBuckets(fromMs, toMs, covBucket, stride)) {
            val i = (b.k - k0).toInt()
            if (i in 0 until n) { probe[i] = probe[i] || b.probe != 0; phone[i] = phone[i] || b.phone != 0 }
        }
        for (b in dao.fixBuckets(fromMs, toMs, covBucket)) {
            val i = (b.k - k0).toInt()
            if (i in 0 until n && b.acc <= maxFixAccuracyM) gps[i] = true
        }
        return Timeline(fromMs, toMs, lanes, bands, Coverage(k0 * covBucket, covBucket, probe, phone, gps))
    }

    // ---- signal and movement ----------------------------------------------------------------

    /** Signal samples and how far you had moved (metres since [fromMs]) over the same span. */
    class Signal(val fromMs: Long, val toMs: Long, val rssi: List<Pair<Long, Int>>, val travelled: List<Pair<Long, Double>>)

    suspend fun signal(dao: AppDao, ids: List<String>, fromMs: Long, toMs: Long): Signal {
        val raw = dao.signalBetween(ids, fromMs, toMs, 20_000)
        // At most ~600 points: keep the strongest of each slot (what a person reads as "the signal").
        val slot = maxOf(1L, (toMs - fromMs) / 600)
        val rssi = raw.groupBy { it.timeMs / slot }.map { (_, g) -> g.maxBy { it.rssi }.let { it.timeMs to it.rssi } }
        val fixes = dao.fixesThinned(fromMs, toMs, 15_000L).map { it.toFix() }.filter { it.accuracyM <= 50f }
        val travelled = ArrayList<Pair<Long, Double>>()
        var d = 0.0
        var prev: GeoFix? = null
        for (f in fixes) {
            prev?.let { p -> val step = dev.retrovision.core.analysis.Geo.distanceM(p, f); if (step > maxOf(p.accuracyM, f.accuracyM)) d += step }
            prev = f
            travelled += f.timeMs to d
        }
        return Signal(fromMs, toMs, rssi, travelled)
    }

    // ---- week x hour ----------------------------------------------------------------------

    /** Distinct days heard per weekday (0 = Monday) and hour, over every stored sighting. */
    class WeekHour(val days: IntArray, val totalDays: Int, val firstMs: Long, val lastMs: Long) {
        fun at(dow: Int, hour: Int) = days[dow * 24 + hour]
    }

    suspend fun weekHour(dao: AppDao, ids: List<String>): WeekHour {
        val times = if (ids.size <= 1) dao.timesFor(ids.first(), 200_000) else dao.timesForAny(ids, 200_000)
        val cells = Array(7 * 24) { HashSet<Long>() }
        val all = HashSet<Long>()
        val cal = java.util.Calendar.getInstance()
        for (t in times) {
            cal.timeInMillis = t.timeMs
            val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
            val day = Catalog.localDay(t.timeMs)
            cells[dow * 24 + cal.get(java.util.Calendar.HOUR_OF_DAY)] += day
            all += day
        }
        return WeekHour(IntArray(7 * 24) { cells[it].size }, all.size, times.firstOrNull()?.timeMs ?: 0L, times.lastOrNull()?.timeMs ?: 0L)
    }

    // ---- devices x places ---------------------------------------------------------------------

    class Column(val label: String, val routine: Boolean)

    /** For each device: days heard at each routine place, and distinct other places (~200 m cells). */
    class MatrixRow(val spec: LaneSpec, val routineDays: IntArray, val otherPlaces: Int, val otherDays: Int)

    class Matrix(val columns: List<Column>, val rows: List<MatrixRow>, val fromMs: Long)

    suspend fun matrix(dao: AppDao, specs: List<LaneSpec>, familiar: List<FamiliarPlace>, fromMs: Long, toMs: Long): Matrix {
        val routine = familiar.filter { it.state == FamiliarPlace.State.CONFIRMED }.take(8)
        val fixes = dao.fixesThinned(fromMs, toMs, 60_000L)
        val times = LongArray(fixes.size) { fixes[it].timeMs }
        fun fixAt(t: Long): FixRow? {
            var i = java.util.Arrays.binarySearch(times, t)
            if (i < 0) i = -i - 1
            val c = listOfNotNull(fixes.getOrNull(i - 1), fixes.getOrNull(i)).minByOrNull { kotlin.math.abs(it.timeMs - t) } ?: return null
            return if (kotlin.math.abs(c.timeMs - t) <= 5 * 60_000L) c else null
        }
        val rows = specs.map { sp ->
            val rDays = Array(routine.size) { HashSet<Long>() }
            val cells = HashSet<Long>()
            val oDays = HashSet<Long>()
            for (b in dao.heardBucketsBetween(sp.ids, fromMs, toMs, 10 * 60_000L, 5000)) {
                val t = (b.t0 + b.t1) / 2
                val f = fixAt(t) ?: continue
                val day = Catalog.localDay(t)
                val i = routine.indexOfFirst { it.contains(f.lat, f.lon) }
                if (i >= 0) rDays[i] += day
                else { cells += (Math.floor(f.lat / 0.002).toLong() shl 32) xor Math.floor(f.lon / 0.003).toLong(); oDays += day }
            }
            MatrixRow(sp, IntArray(routine.size) { rDays[it].size }, cells.size, oDays.size)
        }
        return Matrix(routine.map { Column(it.label.ifBlank { "#${it.id}" }, true) } + Column("", false), rows, fromMs)
    }
}
