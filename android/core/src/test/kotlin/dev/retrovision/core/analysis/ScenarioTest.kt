package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** End-to-end behaviour on the synthetic days in [Scenarios]. */
class ScenarioTest {

    @Test fun everyScenarioAlertsOnlyWhenItShould() {
        for (s in Scenarios.all()) {
            val r = s.run()
            assertEquals("${s.name}: score %.2f".format(r.score), s.shouldAlert, r.alert)
        }
    }

    @Test fun fellowBusPassengerIsNotAFollower() {
        val r = Scenarios.busCommuter().run()
        assertTrue("score ${r.score}", r.score <= 0.3 + 1e-9)
        val moved = r.reasons.filterIsInstance<Reason.MovedWithYou>().single()
        assertTrue(moved.othersMoving >= 20)
        // Walking off together at the destination is not being there.
        assertTrue(r.stopsPresent <= 1)
    }

    @Test fun carTailIsCaughtOnLegsAlone() {
        val r = Scenarios.carTail().run()
        assertEquals(3, r.legsMovedWith)
        assertEquals(0, r.stopsPresent)
        val moved = r.reasons.filterIsInstance<Reason.MovedWithYou>().single()
        assertEquals(3, moved.legs)
        assertEquals(0, moved.othersMoving)
    }

    @Test fun inAConvoyCoMovementCountsForLess() {
        val alone = Scenarios.carTail().run()
        val crowded = Scenarios.carTail(crowd = 20).run()
        assertTrue(crowded.score < alone.score)
        assertFalse(crowded.alert)
    }

    @Test fun passersByDoNotJoinTheCrowd() {
        // Cars heard for a minute are not "moving with you" and must not dilute the tail.
        val r = Scenarios.carTail().run()
        assertEquals(0, r.reasons.filterIsInstance<Reason.MovedWithYou>().single().othersMoving)
    }

    @Test fun arrivingIsNotBeingThere() {
        val r = Scenarios.arrivalEdgesOnly().run()
        assertEquals(0, r.stopsPresent)
        assertTrue(r.placeIds.isEmpty())
    }

    @Test fun neighbourIsCapped() {
        val r = Scenarios.neighbour().run()
        assertEquals(1, r.placeIds.size)
        assertTrue(r.score <= 0.3 + 1e-9)
    }

    @Test fun journeySplitsStopsAndLegs() {
        val j = Journey.of(Scenarios.followerOnFoot().route.fixes, 100.0, 3 * 60_000L, 2 * 60_000L)
        assertEquals(4, j.stops.size)
        assertEquals(3, j.legs.size)
        assertEquals(4, j.places.size)
        for (s in j.stops) assertTrue(s.coreStartMs < s.coreEndMs)
    }

    @Test fun followerStillCaughtWithNoisyGps() {
        val s = Scenarios.followerOnFoot()
        jitter(s.route.fixes)
        val r = s.run()
        assertEquals(4, r.placeIds.size)
        assertTrue(r.alert)
    }

    @Test fun busPassengerStillNotAFollowerWithNoisyGps() {
        val s = Scenarios.busCommuter()
        jitter(s.route.fixes)
        assertFalse(s.run().alert)
    }

    /** ±25 m of noise on every fix and a 200 m network-fix jump every 3 minutes. */
    private fun jitter(fixes: MutableList<GeoFix>) {
        val rnd = Random(42)
        val deg = Route.DEG_PER_M
        for (i in fixes.indices) {
            val f = fixes[i]
            val big = i % 18 == 9
            val dx = if (big) 200.0 else (rnd.nextDouble() - 0.5) * 50
            val dy = if (big) 0.0 else (rnd.nextDouble() - 0.5) * 50
            fixes[i] = f.copy(lat = f.lat + dy / 111_000.0, lon = f.lon + dx * deg, accuracyM = if (big) 80f else 15f)
        }
    }
}
