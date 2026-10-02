// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrospectiveTest {
    private val lat0 = 45.0
    private val lon0 = 11.0
    private val degPerM = 1.0 / 78_800.0
    private val hour = 3_600_000L

    private fun fixAt(t: Long, metresEast: Double) = GeoFix(t, lat0, lon0 + metresEast * degPerM, 5f)

    /** Phone path across ~8 hours, moving to a new place each hour. */
    private fun dayFixes(): List<GeoFix> = (0..8).flatMap { h ->
        (0..5).map { fixAt(h * hour + it * 60_000L, h * 2000.0) } // 2 km apart per hour
    }

    private fun ble(entity: String, t: Long) = EntitySighting(
        entity, Sighting(t, Radio.BLE, MacAddress(0x00AABBCCDD01L), -60, ble = BleDetail(BleAddressKind.PUBLIC, 1, byteArrayOf(2, 1, 6))),
    )

    @Test fun recurringDeviceAcrossHoursScoresHigh() {
        val now = 9 * hour
        // A device present in hours 1,3,5,7 at those far-apart places.
        val sightings = listOf(1, 3, 5, 7).flatMap { h -> (0..3).map { ble("stalker", h * hour + it * 60_000L) } }
        val cfg = retrospectiveConfig(spanMs = 10 * hour)
        val r = Analyzer(cfg).analyze(now, sightings, dayFixes()).entities.single()
        assertTrue("periods reason", r.reasons.any { it is Reason.SeenAcrossPeriods })
        assertTrue("places", r.placeIds.size >= 4)
        assertTrue("score ${r.score}", r.score >= 0.7)
        assertTrue(r.alert)
    }

    @Test fun liveWindowMissesTheOldFollower() {
        val now = 9 * hour
        val sightings = listOf(1, 3, 5, 7).flatMap { h -> (0..3).map { ble("stalker", h * hour + it * 60_000L) } }
        // Live config: 2 h lookback from "now" at hour 9 -> the hour-7 sightings are 2h old, barely in range,
        // and the recency windows (20 min) see nothing -> much lower score than retrospective.
        val live = Analyzer(AnalysisConfig(lookbackMs = 2 * hour)).analyze(now, sightings, dayFixes()).entities
        assertTrue("live should not strongly flag an old follower", live.isEmpty() || live.first().score < 0.7)
    }
}
