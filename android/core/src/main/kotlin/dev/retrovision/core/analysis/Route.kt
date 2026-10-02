// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import kotlin.math.abs

/**
 * Your own route, cut into the two things counter-surveillance cares about:
 *  - **stops** (visits): where you stayed for a while, with arrival and departure;
 *  - **turns**: real changes of direction while moving.
 * Then, per device, how it behaved around them. This is what tells a follower from someone who
 * merely shares your road:
 *  - a follower **arrives after you** at a stop and **leaves with you**;
 *  - a follower **stays with you through turns**; on a straight road everybody "follows" you.
 * Pure function of your own fixes and the device's sighting times.
 */
object Route {
    /** [lat]/[lon]: where the stop began (its anchor). */
    class Stop(val placeId: Int, val arriveMs: Long, val leaveMs: Long, val closed: Boolean, val lat: Double, val lon: Double)

    class Turn(val timeMs: Long, val angleDeg: Double)

    class Config(
        /** A stay shorter than this is passing through, not a stop. */
        val minStopMs: Long = 5 * 60_000L,
        /**
         * Fixes within this distance of where a stop began still belong to it, even if they fall in a
         * neighbouring 100 m place: sitting near a place boundary must not split one stay into fragments.
         */
        val stopMergeM: Double = 150.0,
        /** Heard within this of your arrival = it was already there or came with you. */
        val arrivalSlackMs: Long = 2 * 60_000L,
        /** First heard at least this long after you arrived = it came after you. */
        val lateArrivalMs: Long = 3 * 60_000L,
        /** Heard within this after you left (while you're elsewhere) = it left with you. */
        val leftWithYouMs: Long = 5 * 60_000L,
        /** Direction change that counts as a turn. */
        val minTurnDeg: Double = 60.0,
        /** Path length used to measure the direction before and after a turn. */
        val legM: Double = 60.0,
        /** Heard both this long before and after a turn = it stayed with you through it. */
        val turnWindowMs: Long = 2 * 60_000L,
    )

    /** Stops from your fixes and their place assignment. The last stop is open if you're still there. */
    fun stops(fixes: List<GeoFix>, placeOf: (GeoFix) -> Int?, cfg: Config = Config()): List<Stop> {
        val out = ArrayList<Stop>()
        var cur = -1
        var start = 0L
        var last = 0L
        var anchor: GeoFix? = null
        fun close(closed: Boolean) {
            val a = anchor
            if (cur >= 0 && a != null && last - start >= cfg.minStopMs) out += Stop(cur, start, last, closed, a.lat, a.lon)
        }
        for (f in fixes) {
            val p = placeOf(f) ?: continue
            val a = anchor
            val samePlace = p == cur || (a != null && Geo.distanceM(a, f) <= cfg.stopMergeM)
            if (!samePlace) {
                close(closed = true)
                cur = p; start = f.timeMs; anchor = f
            }
            last = f.timeMs
        }
        close(closed = false)
        return out
    }

    /** Turns along your path: heading over the [Config.legM] before vs after each point. */
    fun turns(fixes: List<GeoFix>, cfg: Config = Config()): List<Turn> {
        // Thin the path to points at least ~legM/3 apart so GPS jitter doesn't look like turning.
        val pts = ArrayList<GeoFix>()
        for (f in fixes) {
            if (pts.isEmpty() || Geo.distanceM(pts.last(), f) >= cfg.legM / 3) pts += f
        }
        val out = ArrayList<Turn>()
        for (i in pts.indices) {
            val before = back(pts, i, cfg.legM) ?: continue
            val after = ahead(pts, i, cfg.legM) ?: continue
            val h1 = Drones.bearing(before.lat, before.lon, pts[i].lat, pts[i].lon)
            val h2 = Drones.bearing(pts[i].lat, pts[i].lon, after.lat, after.lon)
            val d = angleDiff(h1, h2)
            if (d >= cfg.minTurnDeg) {
                val t = pts[i].timeMs
                // One turn may light up several neighbouring points: keep the sharpest per minute.
                val prev = out.lastOrNull()
                if (prev != null && t - prev.timeMs < 60_000L) {
                    if (d > prev.angleDeg) out[out.size - 1] = Turn(t, d)
                } else {
                    out += Turn(t, d)
                }
            }
        }
        return out
    }

    private fun back(p: List<GeoFix>, i: Int, m: Double): GeoFix? {
        for (j in i - 1 downTo 0) if (Geo.distanceM(p[j], p[i]) >= m) return p[j]
        return null
    }

    private fun ahead(p: List<GeoFix>, i: Int, m: Double): GeoFix? {
        for (j in i + 1 until p.size) if (Geo.distanceM(p[i], p[j]) >= m) return p[j]
        return null
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    class Behaviour(val joinedAfterYou: Int, val stayedBehind: Int, val stayedThroughTurns: Int)

    /**
     * @param times sorted sighting times of one device.
     * @param elsewhere true when, at that time, you were away from the given place.
     * @param sensorActive true when the receivers were hearing anything at all at that time
     *   (so "not heard" means absent, not "nothing was recording").
     */
    fun behaviour(
        times: LongArray,
        stops: List<Stop>,
        turns: List<Turn>,
        /** True when at that time you were away from the stop (beyond its merge radius). */
        elsewhere: (Long, Stop) -> Boolean,
        sensorActive: (Long) -> Boolean,
        cfg: Config = Config(),
    ): Behaviour {
        fun heard(from: Long, to: Long): Boolean {
            var i = java.util.Arrays.binarySearch(times, from)
            if (i < 0) i = -i - 1
            return i < times.size && times[i] <= to
        }
        fun firstIn(from: Long, to: Long): Long? {
            var i = java.util.Arrays.binarySearch(times, from)
            if (i < 0) i = -i - 1
            return if (i < times.size && times[i] <= to) times[i] else null
        }
        var joined = 0
        var behind = 0
        for (s in stops) {
            val first = firstIn(s.arriveMs - 60_000L, s.leaveMs) ?: continue
            val atArrival = first <= s.arriveMs + cfg.arrivalSlackMs
            if (!s.closed) continue // you're still here: departure unknown
            val after = firstIn(s.leaveMs + 30_000L, s.leaveMs + cfg.leftWithYouMs)
            val leftWithYou = after != null && elsewhere(after, s)
            val recording = sensorActive(s.leaveMs + cfg.leftWithYouMs / 2)
            if (!atArrival && first >= s.arriveMs + cfg.lateArrivalMs && leftWithYou) joined++
            if (!leftWithYou && recording) behind++
        }
        var through = 0
        for (t in turns) {
            if (heard(t.timeMs - cfg.turnWindowMs, t.timeMs - 10_000L) && heard(t.timeMs + 10_000L, t.timeMs + cfg.turnWindowMs)) through++
        }
        return Behaviour(joined, behind, through)
    }
}
