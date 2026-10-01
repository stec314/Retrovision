package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix

/**
 * A place where you routinely are (home, work...). Devices seen *only* there are usually
 * neighbours and colleagues, so such places count for less in the persistence score.
 */
data class FamiliarPlace(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 150.0,
    val label: String = "",
    val state: State = State.CONFIRMED,
    val kind: Kind = Kind.FREQUENT,
) {
    enum class State { SUGGESTED, CONFIRMED, REJECTED }
    enum class Kind { HOME_LIKE, FREQUENT }

    fun contains(lat: Double, lon: Double): Boolean = Geo.distanceM(this.lat, this.lon, lat, lon) <= radiusM
}

class FamiliarConfig(
    /** Leader-clustering radius for the learner. */
    val clusterRadiusM: Double = 100.0,
    /** A day counts for a cluster when you spent at least this long there. */
    val minDwellPerDayMs: Long = 30 * 60_000L,
    /** ...and the cluster is suggested after this many such days. */
    val minDays: Int = 3,
    /** Gaps between fixes longer than this are not counted as dwelling. */
    val maxGapMs: Long = 5 * 60_000L,
    /** Share of dwell between 22:00 and 06:00 local time above which a place looks like home. */
    val nightShare: Double = 0.4,
)

/** A place the learner proposes; the user confirms or rejects it. */
class FamiliarSuggestion(val lat: Double, val lon: Double, val days: Int, val dwellMs: Long, val kind: FamiliarPlace.Kind)

/**
 * Finds places you keep coming back to, from your own GPS history. Pure function of the
 * fixes: it never looks at any other device.
 */
object FamiliarLearner {
    fun suggest(
        fixes: List<GeoFix>,
        known: List<FamiliarPlace>,
        utcOffsetMs: Long,
        cfg: FamiliarConfig = FamiliarConfig(),
    ): List<FamiliarSuggestion> {
        if (fixes.size < 2) return emptyList()
        val sorted = fixes.sortedBy { it.timeMs }
        val clusterer = PlaceClusterer(cfg.clusterRadiusM)
        val placeOf = sorted.map { clusterer.assign(it) }

        class Acc {
            val dwellByDay = HashMap<Long, Long>()
            var total = 0L
            var night = 0L
        }
        val acc = HashMap<Int, Acc>()
        for (i in 0 until sorted.size - 1) {
            if (placeOf[i].id != placeOf[i + 1].id) continue
            val gap = sorted[i + 1].timeMs - sorted[i].timeMs
            if (gap <= 0 || gap > cfg.maxGapMs) continue
            val a = acc.getOrPut(placeOf[i].id) { Acc() }
            val local = sorted[i].timeMs + utcOffsetMs
            val day = Math.floorDiv(local, 86_400_000L)
            a.dwellByDay.merge(day, gap, Long::plus)
            a.total += gap
            val hour = Math.floorMod(local, 86_400_000L) / 3_600_000L
            if (hour >= 22 || hour < 6) a.night += gap
        }

        val out = ArrayList<FamiliarSuggestion>()
        for (p in clusterer.places) {
            val a = acc[p.id] ?: continue
            val days = a.dwellByDay.values.count { it >= cfg.minDwellPerDayMs }
            if (days < cfg.minDays) continue
            // Skip anything already decided on (confirmed, rejected or still pending).
            if (known.any { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) <= maxOf(it.radiusM, cfg.clusterRadiusM) }) continue
            val kind = if (a.total > 0 && a.night.toDouble() / a.total >= cfg.nightShare) {
                FamiliarPlace.Kind.HOME_LIKE
            } else {
                FamiliarPlace.Kind.FREQUENT
            }
            out += FamiliarSuggestion(p.lat, p.lon, days, a.total, kind)
        }
        return out.sortedByDescending { it.dwellMs }
    }
}

/** One stay at a place, from your own GPS fixes. */
class Visit(val lat: Double, val lon: Double, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Your own day as a list of stays: where you were and for how long. */
object VisitTimeline {
    /**
     * Consecutive fixes that stay within [radiusM] of the stay's centroid form one visit;
     * stays shorter than [minDwellMs] are travel and are dropped.
     *
     * With [outlierToleranceMs] > 0, fixes that jump out of the radius (indoor network fixes,
     * multipath) do not end the visit as long as a fix back inside comes within that time.
     */
    fun build(
        fixes: List<GeoFix>,
        radiusM: Double = 100.0,
        minDwellMs: Long = 3 * 60_000L,
        outlierToleranceMs: Long = 0L,
    ): List<Visit> {
        val sorted = fixes.sortedBy { it.timeMs }
        val out = ArrayList<Visit>()
        var i = 0
        while (i < sorted.size) {
            var lat = sorted[i].lat
            var lon = sorted[i].lon
            var n = 1
            var lastIn = i
            var j = i + 1
            fun inside(f: GeoFix) = Geo.distanceM(f.lat, f.lon, lat, lon) <= radiusM
            while (j < sorted.size && sorted[j].timeMs - sorted[lastIn].timeMs <= 10 * 60_000L) {
                if (inside(sorted[j])) {
                    n++
                    lat += (sorted[j].lat - lat) / n
                    lon += (sorted[j].lon - lon) / n
                    lastIn = j
                    j++
                    continue
                }
                if (outlierToleranceMs <= 0) break
                var k = j + 1
                while (k < sorted.size && sorted[k].timeMs - sorted[j].timeMs <= outlierToleranceMs && !inside(sorted[k])) k++
                if (k < sorted.size && sorted[k].timeMs - sorted[j].timeMs <= outlierToleranceMs) j = k else break
            }
            val v = Visit(lat, lon, sorted[i].timeMs, sorted[lastIn].timeMs)
            if (v.durationMs >= minDwellMs) out += v
            i = lastIn + 1
        }
        return out
    }
}
