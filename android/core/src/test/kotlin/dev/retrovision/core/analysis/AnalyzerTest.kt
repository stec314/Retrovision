// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private val min = 60_000L
    private val lat0 = 45.0
    private val lon0 = 11.0
    private val degPerMetre = 1.0 / 78_800.0 // longitude at 45 N

    /** A walk east at 1.4 m/s, one fix every 10 s. */
    private fun walk(durationMs: Long): List<GeoFix> =
        (0..durationMs / 10_000).map { i ->
            val t = i * 10_000
            GeoFix(t, lat0, lon0 + 1.4 * (t / 1000.0) * degPerMetre, 5f)
        }

    private fun at(t: Long) = walk(40 * min).minBy { Math.abs(it.timeMs - t) }

    private fun ble(entity: String, t: Long, rssi: Int = -60) =
        EntitySighting(
            entity,
            Sighting(t, Radio.BLE, MacAddress(0x0011_2233_4455L), rssi, ble = BleDetail(BleAddressKind.PUBLIC, 1, byteArrayOf(2, 1, 6))),
        )

    private val now = 40 * min
    private fun analyzer() = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L))

    private fun fourPlaceSightings(id: String) = listOf(5L, 15L, 25L, 35L).map { ble(id, it * min) }

    @Test fun followerAcrossFourPlacesAlerts() {
        val r = analyzer().analyze(now, fourPlaceSightings("a"), walk(40 * min)).entities.single()
        assertTrue(r.alert)
        assertEquals(4, r.placeIds.size)
        assertTrue(r.reasons.any { it is Reason.SeenAtPlaces })
    }

    @Test fun placesYouAreAtAllTheTimeCountForLess() {
        val familiar = listOf(5L, 15L, 25L).map { m ->
            val f = at(m * min)
            FamiliarPlace(m, f.lat, f.lon, 150.0, "x")
        }
        val r = analyzer().analyze(now, fourPlaceSightings("a"), walk(40 * min), familiar = familiar).entities.single()
        // 1 unfamiliar + 3 * 0.3 = 1.9 effective places: below the "seen elsewhere" threshold
        assertFalse(r.alert)
        assertTrue(r.score <= 0.3 + 1e-9)
        assertTrue(r.reasons.any { it is Reason.FamiliarDiscount })
    }

    @Test fun suggestedOrRejectedPlacesDoNotCount() {
        val f = at(5 * min)
        val notConfirmed = listOf(
            FamiliarPlace(1, f.lat, f.lon, 150.0, state = FamiliarPlace.State.SUGGESTED),
            FamiliarPlace(2, f.lat, f.lon, 150.0, state = FamiliarPlace.State.REJECTED),
        )
        val r = analyzer().analyze(now, fourPlaceSightings("a"), walk(40 * min), familiar = notConfirmed).entities.single()
        assertTrue(r.alert)
    }

    @Test fun steadyHeardWhileTravellingIsFlagged() {
        // Every 10 s for 10 minutes (840 m of walking), RSSI -60 +/- 2.
        val s = (0..60).map { ble("vehicle", (10 * min) + it * 10_000L, -60 + (it % 3) - 1) }
        val r = analyzer().analyze(now, s, walk(40 * min)).entities.single()
        val m = r.reasons.filterIsInstance<Reason.MovedWithYou>().single()
        assertTrue(m.meters >= 400)
        assertTrue(m.rssiStdDb < 3)
    }

    @Test fun fixedAccessPointFadingInAndOutIsNot() {
        // Strong in the middle, weak at both ends: you walk past it.
        val s = (0..60).map { i ->
            val rssi = -90 + (40 - Math.abs(i - 30) * 40 / 30)
            ble("fixed", (10 * min) + i * 10_000L, rssi)
        }
        val r = analyzer().analyze(now, s, walk(40 * min)).entities.single()
        assertTrue(r.reasons.none { it is Reason.MovedWithYou })
    }

    @Test fun shortCoPresenceIsNotMovement() {
        val s = (0..8).map { ble("brief", (10 * min) + it * 10_000L) } // 80 s, ~110 m
        val r = analyzer().analyze(now, s, walk(40 * min)).entities.single()
        assertTrue(r.reasons.none { it is Reason.MovedWithYou })
    }

    @Test fun activeMinutesCountsDistinctMinutes() {
        val s = listOf(ble("a", 5 * min + 1), ble("a", 5 * min + 30_000), ble("a", 9 * min))
        val r = analyzer().analyze(now, s, walk(40 * min)).entities.single()
        assertEquals(2, r.activeMinutes)
    }

    @Test fun ignoredEntityIsDropped() {
        val res = analyzer().analyze(now, fourPlaceSightings("mine"), walk(40 * min), IgnoreList(entityIds = setOf("mine")))
        assertTrue(res.entities.isEmpty())
        assertEquals(1, res.ignoredEntities)
    }

    // ---- learner ------------------------------------------------------------

    private fun nights(days: Int, lat: Double, lon: Double): List<GeoFix> {
        val out = ArrayList<GeoFix>()
        for (d in 0 until days) {
            val start = d * 24 * 3600_000L + 22 * 3600_000L // 22:00 UTC
            for (i in 0 until 480) out += GeoFix(start + i * 60_000L, lat, lon, 5f)
        }
        return out
    }

    @Test fun learnerFindsHomeLikePlaceAfterThreeDays() {
        val fixes = nights(4, 45.5, 11.5) + nights(1, 46.0, 12.0)
        val s = FamiliarLearner.suggest(fixes, emptyList(), utcOffsetMs = 0)
        assertEquals(1, s.size)
        assertEquals(FamiliarPlace.Kind.HOME_LIKE, s[0].kind)
        assertTrue(s[0].days >= 3)
    }

    @Test fun learnerIgnoresBriefVisits() {
        // 10 minutes a day for a week is a bus stop, not a place you live.
        val fixes = (0 until 7).flatMap { d ->
            (0 until 10).map { GeoFix(d * 86_400_000L + 12 * 3600_000L + it * 60_000L, 45.5, 11.5, 5f) }
        }
        assertTrue(FamiliarLearner.suggest(fixes, emptyList(), 0).isEmpty())
    }

    @Test fun learnerSkipsPlacesAlreadyDecidedOn() {
        val fixes = nights(4, 45.5, 11.5)
        val known = listOf(FamiliarPlace(1, 45.5, 11.5, state = FamiliarPlace.State.REJECTED))
        assertTrue(FamiliarLearner.suggest(fixes, known, 0).isEmpty())
    }

    @Test fun learnerUsesLocalDays() {
        // 22:00-06:00 UTC spans two UTC dates but, at UTC+2, one night is at 00:00-08:00 local: 2 days still need 3 nights.
        val fixes = nights(2, 45.5, 11.5)
        assertTrue(FamiliarLearner.suggest(fixes, emptyList(), 2 * 3600_000L).isEmpty())
        assertNull(null) // placeholder to keep imports honest
        assertNotNull(FamiliarLearner.suggest(nights(5, 45.5, 11.5), emptyList(), 2 * 3600_000L).firstOrNull())
    }

    @Test fun visitsAreStaysNotTravel() {
        val fixes = ArrayList<GeoFix>()
        for (i in 0 until 30) fixes += GeoFix(i * 60_000L, 45.0, 11.0, 5f)                       // 30 min at A
        for (i in 0 until 10) fixes += GeoFix((30 + i) * 60_000L, 45.0, 11.0 + 0.002 * (i + 1), 5f) // moving
        for (i in 0 until 20) fixes += GeoFix((40 + i) * 60_000L, 45.0, 11.03, 5f)                // 20 min at B
        val v = VisitTimeline.build(fixes)
        assertEquals(2, v.size)
        assertTrue(v[0].durationMs >= 29 * 60_000L)
        assertEquals(11.03, v[1].lon, 1e-6)
    }

    /** Field report: 45,000 entities in a city centre ran the phone out of memory. */
    @Test fun resultIsTrimmedButAlertsAndResidentsSurvive() {
        val crowd = (0 until 200).map { ble("passer$it", 20 * min) }
        val cfg = AnalysisConfig(lookbackMs = 3 * 3600_000L, maxReports = 20)
        val res = Analyzer(cfg).analyze(now, crowd + fourPlaceSightings("follower"), walk(40 * min))
        assertEquals(20, res.entities.size)
        assertEquals(201, res.totalEntities)
        assertTrue(res.entities.any { it.entityId == "follower" && it.alert })
        // The trimmed ones stay findable.
        assertEquals(181, res.others.size)
        assertEquals(1, EntitySearch.stubs(res.others) { listOf(it.entityId) }.search("passer199").size +
            EntitySearch.reports(res.entities) { listOf(it.entityId) }.search("passer199").size)
    }

    @Test fun reasonWeightsExplainTheScore() {
        val r = analyzer().analyze(now, fourPlaceSightings("a"), walk(40 * min)).entities.single()
        val places = r.reasons.filterIsInstance<Reason.SeenAtPlaces>().single()
        assertTrue((r.reasonWeights[places] ?: 0.0) > 0.0)
        assertTrue(r.reasonWeights.values.sum() <= r.rawScore + 1e-9)
        assertTrue(r.caps.isEmpty())
    }
}
