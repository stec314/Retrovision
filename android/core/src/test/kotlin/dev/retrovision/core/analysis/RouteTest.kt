package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private val m = 1.0 / 111_320.0
    private fun at(t: Long, n: Double, e: Double) = GeoFix(t, 44.5 + n * m, 11.3 + e * m / Math.cos(Math.toRadians(44.5)), 5f, 1.4f)

    /** Walk north 400 m, turn east 400 m, turn north 400 m: two clear turns. */
    private fun zigzag(t0: Long): List<GeoFix> {
        val out = ArrayList<GeoFix>()
        var t = t0
        for (i in 0..40) { out += at(t, i * 10.0, 0.0); t += 7_000 }
        for (i in 1..40) { out += at(t, 400.0, i * 10.0); t += 7_000 }
        for (i in 1..40) { out += at(t, 400.0 + i * 10.0, 400.0); t += 7_000 }
        return out
    }

    @Test fun detectsTwoRightAngleTurns() {
        val turns = Route.turns(zigzag(0))
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.angleDeg > 70 })
    }

    @Test fun straightWalkHasNoTurns() {
        val f = (0..80).map { at(it * 7_000L, it * 10.0 + (it % 3) * 2.0, (it % 2) * 3.0) }
        assertEquals(0, Route.turns(f).size)
    }

    @Test fun stopsFromDwell() {
        val f = (0..20).map { at(it * 60_000L, 0.0, 0.0) } + (21..23).map { at(it * 60_000L, 500.0 * (it - 20), 0.0) } +
            (24..40).map { at(it * 60_000L, 2000.0, 0.0) }
        val cl = PlaceClusterer(100.0)
        val place = f.associateWith { cl.assign(it).id }
        val s = Route.stops(f, { place[it] })
        assertEquals(2, s.size)
        assertTrue(s[0].closed)
        assertTrue(!s[1].closed)
    }

    private fun probe(id: Int, t: Long) = EntitySighting(
        "dev$id",
        Sighting(t, Radio.WIFI, MacAddress(0x12_00_00_00_00_00L + id), -60, wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ByteArray(0), null, 0, ByteArray(0))),
    )

    @Test fun followerThatArrivesAfterYouAndLeavesWithYouIsFlagged() {
        val now = 10 * 3600_000L
        val t0 = now - 100 * 60_000L
        // You: stop A (20 min), move 5 min, stop B (20 min), move 5 min, stop C (20 min) ...
        val fixes = ArrayList<GeoFix>()
        fun stay(from: Int, to: Int, n: Double) { for (i in from..to) fixes += at(t0 + i * 60_000L, n, 0.0) }
        stay(0, 20, 0.0); stay(21, 25, 600.0); stay(26, 46, 1500.0); stay(47, 51, 2200.0); stay(52, 72, 3000.0)
        val follower = ArrayList<EntitySighting>()
        // arrives 6 min after you at A and B, follows you out each time
        for (i in 6..24) follower += probe(1, t0 + i * 60_000L)
        for (i in 32..50) follower += probe(1, t0 + i * 60_000L)
        for (i in 58..72) follower += probe(1, t0 + i * 60_000L)
        val resident = (0..20).map { probe(2, t0 + it * 60_000L) } // only at A, there when you arrive
        val r = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L)).analyze(now, follower + resident, fixes)
        val f = r.entities.first { it.entityId == "dev1" }
        val joined = f.reasons.filterIsInstance<Reason.JoinedAfterYou>().single()
        assertEquals(2, joined.stops)
        assertTrue(r.entities.first { it.entityId == "dev2" }.reasons.none { it is Reason.JoinedAfterYou })
        assertTrue(r.stops >= 3)
    }

    @Test fun stayedThroughTurnsCounts() {
        val f = zigzag(0)
        val turns = Route.turns(f)
        val withYou = LongArray(f.size) { f[it].timeMs }
        val b = Route.behaviour(withYou, emptyList(), turns, { _, _ -> true }, { true })
        assertEquals(2, b.stayedThroughTurns)
        val onlyFirstLeg = withYou.filter { it < 280_000L }.toLongArray()
        assertEquals(0, Route.behaviour(onlyFirstLeg, emptyList(), turns, { _, _ -> true }, { true }).stayedThroughTurns)
    }
}
