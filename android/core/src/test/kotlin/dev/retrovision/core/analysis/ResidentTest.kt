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

class ResidentTest {
    private val min = 60_000L
    private val lat0 = 45.0; private val lon0 = 11.0
    private val degPerM = 1.0 / 78_800.0
    private fun walk(dur: Long) = (0..dur / 10_000).map { GeoFix(it * 10_000, lat0, lon0 + 1.4 * (it * 10.0) * degPerM, 5f) }
    private fun ble(id: String, t: Long) = EntitySighting(id, Sighting(t, Radio.BLE, MacAddress(0x0011_2233_4455L), -60, ble = BleDetail(BleAddressKind.PUBLIC, 1, byteArrayOf(2, 1, 6))))
    private fun fourPlaces(id: String) = listOf(5L, 15L, 25L, 35L).map { ble(id, it * min) }

    @Test fun residentIsDampedAndCannotAlert() {
        val a = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L))
        val plain = a.analyze(40 * min, fourPlaces("x"), walk(40 * min)).entities.single()
        assertTrue(plain.alert) // without baseline it would alert
        val damped = a.analyze(40 * min, fourPlaces("x"), walk(40 * min), residents = setOf("x")).entities.single()
        assertFalse(damped.alert)
        assertTrue(damped.score <= 0.25 + 1e-9)
        assertTrue(damped.reasons.any { it is Reason.KnownAtRoutine })
    }
}
