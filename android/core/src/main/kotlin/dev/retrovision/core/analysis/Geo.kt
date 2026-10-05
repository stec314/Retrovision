// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: GeoFix, b: GeoFix): Double = distanceM(a.lat, a.lon, b.lat, b.lon)
}

/** A cluster of phone positions: somewhere you have been ("place"). */
class Place(val id: Int, lat: Double, lon: Double) {
    var lat: Double = lat
        private set
    var lon: Double = lon
        private set
    var fixes: Int = 1
        private set
    var firstMs: Long = Long.MAX_VALUE
        private set
    var lastMs: Long = Long.MIN_VALUE
        private set

    internal fun add(fix: GeoFix) {
        // Running centroid; fine at the 100 m scale (no antimeridian concerns).
        fixes++
        lat += (fix.lat - lat) / fixes
        lon += (fix.lon - lon) / fixes
        touch(fix.timeMs)
    }

    internal fun touch(t: Long) {
        if (t < firstMs) firstMs = t
        if (t > lastMs) lastMs = t
    }
}

/**
 * Leader clustering, same idea as CYT's 100 m location grouping: a fix joins
 * the nearest place whose centroid is within [radiusM], otherwise it starts a
 * new place. Deterministic for a given input order (chronological).
 */
class PlaceClusterer(private val radiusM: Double = 100.0) {
    private val _places = ArrayList<Place>()
    val places: List<Place> get() = _places

    fun assign(fix: GeoFix): Place {
        var best: Place? = null
        var bestD = Double.MAX_VALUE
        for (p in _places) {
            val d = Geo.distanceM(fix.lat, fix.lon, p.lat, p.lon)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        if (best != null && bestD <= radiusM) {
            best.add(fix)
            return best
        }
        return Place(_places.size, fix.lat, fix.lon).also {
            it.touch(fix.timeMs)
            _places += it
        }
    }
}

/**
 * Chronological phone positions with nearest-in-time lookup. A sighting is
 * only placed when a fix exists within [maxGapMs], or when it falls in a gap of at most
 * [stationaryBridgeMs] whose two sides are within [stationaryRadiusM] (you stood still);
 * otherwise it has no place (we never guess a location while you move).
 */
class FixTimeline(
    fixes: List<GeoFix>,
    private val maxGapMs: Long = 60_000,
    /**
     * Longest gap bridged while you stand still: when the fixes on both sides of a gap are within
     * [stationaryRadiusM] of each other you did not go anywhere, so the gap gets that place.
     * Field data: indoors in a city centre the phone went 14–20 min without a usable fix and 58.6%
     * of the busiest stop's sightings had no place. 0 disables.
     */
    private val stationaryBridgeMs: Long = 0,
    private val stationaryRadiusM: Double = 75.0,
) {
    private val sorted = fixes.sortedBy { it.timeMs }
    private val times = LongArray(sorted.size) { sorted[it].timeMs }

    val fixes: List<GeoFix> get() = sorted

    fun nearest(timeMs: Long): GeoFix? {
        if (sorted.isEmpty()) return null
        var i = times.binarySearch(timeMs)
        if (i >= 0) return sorted[i]
        i = -i - 1
        val cand = listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i))
        val best = cand.minBy { kotlin.math.abs(it.timeMs - timeMs) }
        if (kotlin.math.abs(best.timeMs - timeMs) <= maxGapMs) return best
        // Standing still through a gap: both sides agree on where you were.
        if (stationaryBridgeMs > 0 && cand.size == 2) {
            val (before, after) = cand
            if (after.timeMs - before.timeMs <= stationaryBridgeMs && Geo.distanceM(before, after) <= stationaryRadiusM) return best
        }
        return null
    }
}
