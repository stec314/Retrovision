package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix

/**
 * Your own movements in the analysis window, split into stops (stays of a few minutes or
 * more) and the legs between them.
 *
 * This is what "place" means for the analysis. Counting every 100 m of road as a place made
 * anyone travelling with you (a bus, a train) look like a follower; a follower is someone who
 * turns up again where you *stop*, or who moves with you on separate legs of your day.
 */
class Journey private constructor(
    val stops: List<Stop>,
    val legs: List<Leg>,
    val places: List<Place>,
) {
    class Stop(
        val index: Int,
        val placeId: Int,
        val lat: Double,
        val lon: Double,
        val startMs: Long,
        val endMs: Long,
        /** The part of the stay that counts: without the minutes of arriving and leaving. */
        val coreStartMs: Long,
        val coreEndMs: Long,
    ) {
        val fix: GeoFix get() = GeoFix(startMs, lat, lon)
    }

    class Leg(val index: Int, val startMs: Long, val endMs: Long)

    /** The stop whose core contains [timeMs], if any. */
    fun stopAt(timeMs: Long): Stop? = stops.firstOrNull { timeMs in it.coreStartMs..it.coreEndMs }

    /** The leg containing [timeMs], if any. */
    fun legAt(timeMs: Long): Leg? = legs.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    companion object {
        /** A position fix that jumps away for less than this does not end a stop. */
        private const val OUTLIER_TOLERANCE_MS = 60_000L
        /** Fixes this inaccurate say nothing about whether you moved. */
        private const val MAX_ACCURACY_M = 250f

        fun of(fixes: List<GeoFix>, radiusM: Double, minDwellMs: Long, edgeMs: Long): Journey {
            val sorted = fixes.sortedBy { it.timeMs }
            if (sorted.isEmpty()) return Journey(emptyList(), emptyList(), emptyList())
            val usable = sorted.filter { it.accuracyM <= MAX_ACCURACY_M }
            val visits = VisitTimeline.build(usable, radiusM, minDwellMs, OUTLIER_TOLERANCE_MS)

            val clusterer = PlaceClusterer(radiusM)
            val stops = visits.mapIndexed { i, v ->
                val place = clusterer.assign(GeoFix(v.startMs, v.lat, v.lon))
                place.touch(v.endMs)
                // A visit starts as soon as you are within the radius, which on foot is a minute or
                // more before you actually stop. Arriving and leaving are measured from when you
                // were close to where you settled.
                val settled = usable.filter {
                    it.timeMs in v.startMs..v.endMs && Geo.distanceM(it.lat, it.lon, v.lat, v.lon) <= radiusM / 2
                }
                val (cs, ce) = core(
                    settled.firstOrNull()?.timeMs ?: v.startMs,
                    settled.lastOrNull()?.timeMs ?: v.endMs,
                    edgeMs,
                )
                Stop(i, place.id, v.lat, v.lon, v.startMs, v.endMs, cs, ce)
            }

            val legs = ArrayList<Leg>()
            var cursor = sorted.first().timeMs
            for (s in stops) {
                if (s.startMs > cursor) legs += Leg(legs.size, cursor, s.startMs)
                cursor = maxOf(cursor, s.endMs)
            }
            val end = sorted.last().timeMs
            if (end > cursor) legs += Leg(legs.size, cursor, end + 1)
            return Journey(stops, legs, clusterer.places)
        }

        private fun core(start: Long, end: Long, edgeMs: Long): Pair<Long, Long> {
            if (end - start > 2 * edgeMs + 60_000L) return (start + edgeMs) to (end - edgeMs)
            val mid = start + (end - start) / 2
            return (mid - 30_000L) to (mid + 30_000L)
        }
    }
}
