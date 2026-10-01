package dev.retrovision.core.analysis

import dev.retrovision.core.identity.DeviceCategories
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    private val fixes = listOf(GeoFix(0, 45.0, 11.0), GeoFix(600_000, 45.0, 11.0))
    private fun analyze(vararg s: EntitySighting) =
        Analyzer(AnalysisConfig(lookbackMs = 3600_000L)).analyze(600_000, s.toList(), fixes).entities.single()

    private fun wifi(addr: Long, kind: WifiKind, ssid: String, bssid: Long? = null, t: Long = 60_000) = EntitySighting(
        "w",
        Sighting(t, Radio.WIFI, MacAddress(addr), -50, wifi = WifiDetail(kind, 6, ssid.toByteArray(), bssid?.let { MacAddress(it) }, 1, ByteArray(0))),
    )

    private fun ble(kind: BleAddressKind, ad: ByteArray) = EntitySighting(
        "b", Sighting(60_000, Radio.BLE, MacAddress(0xC0_1122_3344_55L), -60, ble = BleDetail(kind, 1, ad)),
    )

    @Test fun randomisedClientSearchingAndJoining() {
        val client = 0x02_1111_2222_33L // locally administered
        val ap = 0x00_AAAA_BBBB_CCL
        val r = analyze(
            wifi(client, WifiKind.PROBE_REQ, "CasaRossi"),
            wifi(client, WifiKind.PROBE_REQ, ""),
            wifi(client, WifiKind.AUTH, "", ap, 70_000),
            wifi(client, WifiKind.ASSOC_REQ, "CasaRossi", ap, 71_000),
            wifi(ap, WifiKind.AUTH, "", ap, 70_500), // AP's reply: not a join by the client
        )
        assertEquals(EntityKind.WIFI_CLIENT, r.kind)
        assertEquals(DeviceCategory.WIFI_CLIENT, r.category)
        assertEquals(MacTrust.ROTATING, r.macTrust)
        assertEquals(setOf("CasaRossi"), r.probedSsids)
        assertEquals(2, r.probeRequests)
        assertEquals(1, r.wildcardProbes)
        val j = r.joinAttempts.single()
        assertEquals(MacAddress(ap), j.bssid)
        assertEquals("CasaRossi", j.ssid)
        assertEquals(WifiKind.ASSOC_REQ, j.kind)
        assertEquals(2, j.count)
    }

    @Test fun bleCategoriesAndTrust() {
        // Appearance 0x00C1 = watch (category 3)
        val watch = analyze(ble(BleAddressKind.RANDOM_STATIC, byteArrayOf(3, 0x19, 0xC1.toByte(), 0x00)))
        assertEquals(DeviceCategory.WATCH, watch.category)
        assertEquals(MacTrust.UNTIL_REBOOT, watch.macTrust)
        // Apple Proximity Pairing (type 0x07) = AirPods-like audio, rotating address
        val pods = analyze(ble(BleAddressKind.RANDOM_RESOLVABLE, byteArrayOf(5, 0xFF.toByte(), 0x4C, 0x00, 0x07, 0x19)))
        assertEquals(DeviceCategory.AUDIO, pods.category)
        assertEquals(MacTrust.ROTATING, pods.macTrust)
        // Name only
        val tv = analyze(ble(BleAddressKind.PUBLIC, byteArrayOf(10, 0x09) + "[TV] Sala".toByteArray()))
        assertEquals(DeviceCategory.TV, tv.category)
        assertEquals("[TV] Sala", tv.bleName)
        assertEquals(MacTrust.STABLE, tv.macTrust)
    }

    @Test fun appearanceTable() {
        assertEquals(DeviceCategory.PHONE, DeviceCategories.fromAppearance(0x0040))
        assertEquals(DeviceCategory.COMPUTER, DeviceCategories.fromAppearance(0x0080))
        assertEquals(DeviceCategory.INPUT, DeviceCategories.fromAppearance(0x03C1))
        assertEquals(DeviceCategory.AUDIO, DeviceCategories.fromAppearance(0x0941))
    }
}
