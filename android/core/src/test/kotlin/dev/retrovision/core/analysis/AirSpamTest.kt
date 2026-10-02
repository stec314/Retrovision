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

    private fun beacon(bssid: Long, ssid: String, t: Long, rssi: Int, ch: Int = 6) =
        Sighting(t, Radio.WIFI, MacAddress(bssid), rssi, wifi = WifiDetail(WifiKind.BEACON, ch, ssid.toByteArray(), MacAddress(bssid), 0, ByteArray(0)))

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
        val before = (0 until 10).map { beacon(0x00_11_22_00_00_00L + it, "home$it", 0, -70 - it * 2) }
        val flood = (0 until 50).map { beacon(0x02_AA_00_00_00_00L + it, "FreeWifi $it", 150_000L + it * 500, -48 - (it % 4)) }
        val t = WifiThreats.detect(before + flood, emptySet())
        assertEquals(WifiThreats.Kind.BEACON_FLOOD, t.single().kind)
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
