// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SightingBucketsTest {
    private fun ble(id: String, t: Long, rssi: Int = -60, adv: Int = 0, merged: Int = 1) = EntitySighting(
        id, Sighting(t, Radio.BLE, MacAddress(0x112233445566L), rssi, merged, ble = BleDetail(BleAddressKind.PUBLIC, adv, byteArrayOf(2, 1, 6))),
    )

    private fun probe(id: String, t: Long, ssid: String) = EntitySighting(
        id, Sighting(t, Radio.WIFI, MacAddress(0x0211223344FFL), -70, wifi = WifiDetail(WifiKind.PROBE_REQ, 1, ssid.toByteArray(), null, 0, ByteArray(0))),
    )

    @Test fun repeatsCollapseAndCountsAreSummed() {
        val b = SightingBuckets(60_000)
        for (i in 0 until 60) b.add(ble("a", i * 1000L, rssi = -60 - i % 3, merged = 2))
        val s = b.snapshot(0).single()
        assertEquals(120, s.sighting.mergedCount)
        assertEquals(59_000L, s.sighting.timeMs) // newest kept
        assertEquals(60L, b.rawCount())
    }

    @Test fun differentKindsAndSsidsStaySeparate() {
        val b = SightingBuckets(60_000)
        b.add(ble("a", 1000, adv = 0)); b.add(ble("a", 2000, adv = 4))
        b.add(probe("p", 1000, "home")); b.add(probe("p", 2000, "work")); b.add(probe("p", 3000, "home"))
        assertEquals(4, b.snapshot(0).size)
    }

    @Test fun closedBucketsDrainOnceAndOpenOnesWait() {
        val b = SightingBuckets(10_000)
        b.add(ble("a", 1_000)); b.add(ble("a", 9_000)); b.add(ble("a", 12_000))
        val first = b.drainClosed(15_000)
        assertEquals(1, first.size)
        assertEquals(2, first[0].sighting.mergedCount)
        assertEquals(0, b.drainClosed(15_000).size)
        assertEquals(1, b.drainAll().size)
    }

    @Test fun oldSlotsArePrunedAndTheCapDropsTheOldest() {
        val b = SightingBuckets(1_000, maxSlots = 3)
        for (i in 0 until 5) b.add(ble("a", i * 1000L))
        assertEquals(3, b.size)
        assertEquals(2, b.evictedSlots.toInt())
        assertEquals(listOf(3000L, 4000L), b.snapshot(3000).map { it.sighting.timeMs })
    }
}
