// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleRotationTest {
    private val min = 60_000L

    /** Field case: an iPhone in your car, Find My "near owner" frame from a random static address. */
    @Test fun findMyNearOwnerOnRandomStaticIsARotatingIdentity() {
        val adv = byteArrayOf(0x07, 0xFF.toByte(), 0x4C, 0x00, 0x12, 0x02, 0x00, 0x00)
        val mac = MacAddress(0xE6421C5F3FFCL)
        val sightings = (0 until 72).map { i ->
            EntitySighting("ble:$mac", Sighting(i * 10_000L, Radio.BLE, mac, -45, ble = BleDetail(BleAddressKind.RANDOM_STATIC, 4, adv)))
        }
        // Driving at ~25 m/s for 12 minutes.
        val fixes = (0..72).map { GeoFix(it * 10_000L, 44.5 + it * 250.0 / 111_000.0, 11.5, 5f, 25f) }
        val r = Analyzer(AnalysisConfig(lookbackMs = 3600_000L)).analyze(12 * min, sightings, fixes).entities.single()
        assertEquals(MacTrust.ROTATING, r.macTrust)
        assertFalse(r.alert)
    }
}
