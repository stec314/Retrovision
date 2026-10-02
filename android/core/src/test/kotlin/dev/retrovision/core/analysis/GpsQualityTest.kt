// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsQualityTest {
    private val min = 60_000L
    private val lat0 = 45.0; private val lon0 = 11.0
    private val degPerM = 1.0 / 78_800.0

    private fun ble(t: Long) = EntitySighting("x", Sighting(t, Radio.BLE, MacAddress(0x0011_2233_4455L), -55, ble = BleDetail(BleAddressKind.PUBLIC, 1, byteArrayOf(2, 1, 6))))
    private fun sightings() = (0..40).map { ble(it * min / 2) } // steady RSSI, many samples

    /** Phone physically stationary, but GPS drifts up to 600 m with poor accuracy. */
    private fun driftFixes(accuracyM: Float) = (0..80).map {
        val jitter = (if (it % 2 == 0) 600.0 else -600.0) * degPerM
        GeoFix(it * min / 4, lat0 + jitter, lon0, accuracyM)
    }

    @Test fun indoorDriftDoesNotFakeCoMovementOrTravel() {
        val a = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L))
        val poor = a.analyze(20 * min, sightings(), driftFixes(150f)).entities.singleOrNull()
        // With poor GPS, the drift must not create travel/co-movement/places.
        if (poor != null) {
            assertFalse(poor.reasons.any { it is Reason.MovedWithYou })
            assertFalse(poor.reasons.any { it is Reason.TravelledWithYou })
            assertTrue(poor.score < 0.5)
        }
    }

    @Test fun goodGpsWithRealMovementStillWorks() {
        val a = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L))
        // Real travel east, accurate fixes.
        val fixes = (0..80).map { GeoFix(it * min / 4, lat0, lon0 + it * 20.0 * degPerM, 8f) }
        val r = a.analyze(20 * min, sightings(), fixes).entities.single()
        assertTrue("should see travel with good GPS", r.reasons.any { it is Reason.TravelledWithYou || it is Reason.MovedWithYou })
    }
}
