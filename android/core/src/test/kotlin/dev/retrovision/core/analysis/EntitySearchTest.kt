// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.MacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySearchTest {
    private fun dev(id: String, mac: Long, probed: Set<String> = emptySet(), ssids: Set<String> = emptySet(), name: String? = null) = EntityReport(
        entityId = id, kind = EntityKind.WIFI_CLIENT, score = 0.0, alert = false, reasons = emptyList(), placeIds = emptySet(),
        windows = emptySet(), firstSeenMs = 0, lastSeenMs = 0, sightings = 1, activeMinutes = 1, maxRssi = -60,
        addresses = setOf(MacAddress(mac)), ssids = ssids + probed, tracker = null, bleCompanyId = null, mobileAp = null,
        track = emptyList(), probedSsids = probed, bleName = name,
    )

    private val phone = dev("a", 0x02AABBCCDDEEL, probed = setOf("Città-WiFi", "Hotel Duomo"))
    private val band = dev("b", 0xC0FFEE123456L, name = "Mi Band 8 A1B2")

    @Test fun findsSearchedNetworkCaseAndAccentInsensitive() {
        assertTrue(EntitySearch.matches(phone, "citta"))
        assertTrue(EntitySearch.matches(phone, "HOTEL duomo"))
        assertFalse(EntitySearch.matches(band, "hotel"))
    }

    @Test fun findsAddressInAnyNotation() {
        assertTrue(EntitySearch.matches(phone, "02:aa:bb"))
        assertTrue(EntitySearch.matches(phone, "aabbcc"))
        assertTrue(EntitySearch.matches(phone, "BB-CC-DD"))
        assertFalse(EntitySearch.matches(phone, "ffff"))
    }

    @Test fun allWordsMustMatch() {
        assertTrue(EntitySearch.matches(band, "band a1b2"))
        assertFalse(EntitySearch.matches(band, "band hotel"))
    }

    @Test fun extraUiTextIsSearchable() {
        assertTrue(EntitySearch.matches(band, "xiaomi", listOf("Xiaomi Communications")))
    }

    /** Field report: 45,000 devices in a city centre; typing in the search box froze the app. */
    @Test fun indexSearchesFiftyThousandDevicesQuickly() {
        val many = (0 until 50_000).map { i -> dev("e$i", 0x020000000000L + i, probed = setOf("Net${i % 997}"), name = "Dev $i") }
        val t0 = System.nanoTime()
        val idx = EntitySearch.Index(many) { listOf("Vendor ${it.entityId}") }
        val built = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val hits = idx.search("net42 dev")
        val searched = (System.nanoTime() - t1) / 1_000_000
        assertTrue(hits.isNotEmpty())
        assertTrue("build $built ms", built < 3_000)
        assertTrue("search $searched ms", searched < 500)
        assertEquals(1, idx.search("02:00:00:00:c3:4f").size) // 0xC34F = 49999
    }

    @Test fun searchedNetworksCountDevices() {
        val other = dev("c", 0x02000000000AL, probed = setOf("Hotel Duomo"))
        assertEquals(listOf("Hotel Duomo" to 2, "Città-WiFi" to 1), EntitySearch.searchedNetworks(listOf(phone, other, band)))
    }
}
