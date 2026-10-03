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
import org.junit.Assert.assertTrue
import org.junit.Test

class AirSpamTest {
    private val proximityPairing = byteArrayOf(0x0B, 0xFF.toByte(), 0x4C, 0x00, 0x07, 0x19, 0x01, 0x0E, 0x20, 0x55, 0x00, 0x00)

    private fun ble(addr: Long, t: Long, rssi: Int, data: ByteArray = proximityPairing) =
        Sighting(t, Radio.BLE, MacAddress(addr), rssi, ble = BleDetail(BleAddressKind.RANDOM_NON_RESOLVABLE, 4, data))

    /** Beacon IEs after the SSID: rates + RSN. One template per vendor/tool. */
    private val toolTemplate = byteArrayOf(1, 4, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 3, 1, 6)
    private val arubaTemplate = byteArrayOf(1, 8, 1, 2, 3, 4, 5, 6, 7, 8, 3, 1, 1, 48, 4, 1, 0, 0, 0, 221.toByte(), 5, 0, 0x0B, 0x86.toByte(), 1, 2)

    private fun beacon(bssid: Long, ssid: String, t: Long, rssi: Int, ch: Int = 6, ies: ByteArray = toolTemplate) =
        Sighting(t, Radio.WIFI, MacAddress(bssid), rssi, wifi = WifiDetail(WifiKind.BEACON, ch, ssid.toByteArray(), MacAddress(bssid), 0, ies))

    @Test fun flipperStylePopupSpamIsDetected() {
        // 60 fresh addresses in 30 s, each heard once, all at about -55 dBm.
        val s = (0 until 60).map { ble(0xC2_0000_0000_00L + it, 1000L * it / 2, -55 + (it % 3)) }
        val t = WifiThreats.detectBle(s)
        assertEquals(WifiThreats.Kind.BLE_SPAM, t.single().kind)
        assertEquals(60, t.single().count)
    }

    @Test fun crowdOfRealAirPodsIsNotSpam() {
        // 40 people's AirPods: stable addresses heard for a minute, widely spread signal.
        val s = (0 until 40).flatMap { p -> (0 until 6).map { k -> ble(0xC2_0000_0001_00L + p, k * 10_000L, -50 - p) } }
        assertTrue(WifiThreats.detectBle(s).isEmpty())
    }

    @Test fun passersByWithSpreadSignalsAreNotSpam() {
        val s = (0 until 40).map { ble(0xC2_0000_0002_00L + it, it * 1000L, -45 - it) }
        assertTrue(WifiThreats.detectBle(s).isEmpty())
    }

    @Test fun beaconFloodIsDetected() {
        // Counting up from one base address (Deauther-style).
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70 - it * 2) }
        val flood = (0 until 50).map { beacon(0x02_AA_00_00_00_00L + it, "FreeWifi $it", 150_000L + it * 500, -48 - (it % 4)) }
        val t = WifiThreats.detect(before + flood, emptySet())
        assertEquals(WifiThreats.Kind.BEACON_FLOOD, t.single().kind)
        assertEquals(20, t.single().ssids.size)
    }

    @Test fun beaconFloodWithRandomAddressesIsDetected() {
        val rnd = java.util.Random(3)
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70 - it * 2) }
        val flood = (0 until 40).map { beacon((rnd.nextLong() and 0xFEFFFFFFFFFFL) or 0x020000000000L, "Rick $it", 150_000L + it * 500, -55 - (it % 5)) }
        assertEquals(WifiThreats.Kind.BEACON_FLOOD, WifiThreats.detect(before + flood, emptySet()).single().kind)
    }

    /** Field report, Ferrara: walking into range of city and shop Wi-Fi — many multi-SSID APs, all weak. */
    @Test fun walkingIntoCityWifiIsNotAFlood() {
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70) }
        // 29 networks, 13 names, ~10 radios serving 3 names each, same vendor template, all weak.
        fun city(rssi: (Int) -> Int) = (0 until 29).map { i ->
            beacon(0xB4_5D_50_0B_40_00L + (i / 3) * 0x100 + i % 3, "City${i % 13}", 150_000L + i * 1500, rssi(i), ies = arubaTemplate)
        }
        assertTrue(WifiThreats.detect(before + city { -86 - it % 4 }, emptySet()).none { it.kind == WifiThreats.Kind.BEACON_FLOOD })
        // Even standing right under them: few radios, small families.
        assertTrue(WifiThreats.detect(before + city { -60 - it % 4 }, emptySet()).none { it.kind == WifiThreats.Kind.BEACON_FLOOD })
    }

    @Test fun strongButVariedRealNetworksAreNotAFlood() {
        // Same channel, similar strong signal, but each from a different vendor template.
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70) }
        val city = (0 until 30).map { i ->
            beacon(0x00_44_00_00_00_00L + i * 0x10000, "Shop$i", 150_000L + i * 1000, -60 - i % 4, ies = byteArrayOf(1, (2 + i % 7).toByte(), 1, 2, 3, 4, 5, 6, 7, 8).copyOf(2 + 2 + i % 7))
        }
        assertTrue(WifiThreats.detect(before + city, emptySet()).none { it.kind == WifiThreats.Kind.BEACON_FLOOD })
    }

    @Test fun walkingPastManyRealNetworksIsNotAFlood() {
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70) }
        val city = (0 until 40).map { beacon(0x00_33_00_00_00_00L + it, "Net$it", 150_000L + it * 1000, -50 - (it * 7) % 45) }
        assertTrue(WifiThreats.detect(before + city, emptySet()).none { it.kind == WifiThreats.Kind.BEACON_FLOOD })
    }

    @Test fun noFloodRightAfterStartUp() {
        val flood = (0 until 50).map { beacon(0x02_AA_00_00_00_00L + it, "Fake $it", it * 500L, -50) }
        assertTrue(WifiThreats.detect(flood, emptySet()).isEmpty())
    }
}
