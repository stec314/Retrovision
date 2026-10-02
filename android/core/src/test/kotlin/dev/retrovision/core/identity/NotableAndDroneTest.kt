// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.analysis.Drones
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotableAndDroneTest {
    private fun ad(type: Int, vararg b: Int) = byteArrayOf((b.size + 1).toByte(), type.toByte()) + b.map { it.toByte() }.toByteArray()
    private fun name(n: String) = byteArrayOf((n.length + 1).toByte(), 0x09) + n.toByteArray()
    private fun ble(addr: Long, data: ByteArray, kind: BleAddressKind = BleAddressKind.RANDOM_NON_RESOLVABLE, t: Long = 0, rssi: Int = -60) =
        Sighting(t, Radio.BLE, MacAddress(addr), rssi, ble = BleDetail(kind, 4, data))
    private fun beacon(bssid: Long, ssid: String, ies: ByteArray = ByteArray(0), t: Long = 0, ch: Int = 6, rssi: Int = -60) =
        Sighting(t, Radio.WIFI, MacAddress(bssid), rssi, wifi = WifiDetail(WifiKind.BEACON, ch, ssid.toByteArray(), MacAddress(bssid), 0, ies))

    // ---- DULT ----
    @Test fun dultSeparatedTagIsATracker() {
        val m = TrackerClassifier.classify(ad(0x16, 0xB2, 0xFC, 0x01, 0x00))!!
        assertEquals(TrackerKind.DULT_TAG, m.kind)
        assertEquals(true, m.separated)
        assertTrue(TrackerClassifier.isTag(m.kind))
        assertEquals(false, TrackerClassifier.classify(ad(0x16, 0xB2, 0xFC, 0x01, 0x01))!!.separated)
    }

    @Test fun brandWinsButDultGivesSeparation() {
        val adv = ad(0x03, 0x33, 0xFE) + ad(0x16, 0xB2, 0xFC, 0x01, 0x00)
        val m = TrackerClassifier.classify(adv)!!
        assertEquals(TrackerKind.CHIPOLO, m.kind)
        assertEquals(true, m.separated)
    }

    // ---- notable catalog ----
    @Test fun catalogLoadsFromResources() {
        val names = NotableCatalog.signatures.map { it.name }
        assertTrue(names.size >= 50)
        assertTrue("Flipper Zero" in names)
        assertTrue(NotableCatalog.signatures.all { it.note.isNotBlank() || it.kind == NotableKind.DRONE || true })
    }

    @Test fun flipperByDefaultName() {
        val hits = NotableCatalog.match(ble(0x02_11_22_33_44_55L, name("Flipper Abc12")))
        assertEquals(listOf("Flipper Zero"), hits.map { it.name })
        assertEquals(NotableKind.HACKING, hits.single().kind)
    }

    @Test fun ordinaryDeviceIsNotNotable() {
        assertTrue(NotableCatalog.match(ble(0x02_11_22_33_44_55L, name("My Headphones"))).isEmpty())
        assertTrue(NotableCatalog.match(beacon(0x00_11_22_33_44_55L, "HomeWifi")).isEmpty())
    }

    @Test fun pwnagotchiByBssidAndPineappleBySsid() {
        assertTrue(NotableCatalog.match(beacon(0xDE_AD_BE_EF_DE_ADL, "x")).any { it.name == "Pwnagotchi" })
        assertTrue(NotableCatalog.match(beacon(0x02_00_00_00_00_01L, "Pineapple_1A2B")).any { it.name == "Hak5 Pineapple" })
    }

    @Test fun axonByServiceDataPayloadAnywhere() {
        // service data under some UUID containing "BWCDEVICE"
        val payload = "BWCDEVICE".toByteArray().map { it.toInt() }.toIntArray()
        val adv = ad(0x16, 0x34, 0x12, *payload)
        assertTrue(NotableCatalog.match(ble(0x02_11_22_33_44_55L, adv)).any { it.name == "Axon" })
    }

    @Test fun uuid128IsParsedLittleEndian() {
        // Limitless 632DE001-604C-446B-A80F-7963E950F3FB, little-endian on air
        val be = "632DE001604C446BA80F7963E950F3FB".chunked(2).map { it.toInt(16) }
        val adv = ad(0x07, *be.reversed().toIntArray())
        assertEquals(setOf("632DE001-604C-446B-A80F-7963E950F3FB"), AdvertisementInfo.of(adv).serviceUuids128)
        assertTrue(NotableCatalog.match(ble(0x02_11_22_33_44_55L, adv)).any { it.kind == NotableKind.RECORDER })
    }

    @Test fun ouiOnRandomBleAddressIsIgnored() {
        // Flipper OUI 0C:FA:22 only counts on a public address.
        val pub = ble(0x0C_FA_22_00_00_01L, ByteArray(0), BleAddressKind.PUBLIC)
        val rnd = ble(0x0C_FA_22_00_00_01L, ByteArray(0), BleAddressKind.RANDOM_NON_RESOLVABLE)
        assertTrue(NotableCatalog.match(pub).any { it.name == "Flipper Zero" })
        assertTrue(NotableCatalog.match(rnd).none { it.name == "Flipper Zero" })
    }

    // ---- Remote ID ----
    private fun le32(v: Int) = listOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v ushr 24) and 0xFF)
    private fun le16(v: Int) = listOf(v and 0xFF, (v shr 8) and 0xFF)

    private fun basicId(id: String): List<Int> =
        listOf(0x02, (1 shl 4) or 2) + id.padEnd(20, '\u0000').map { it.code } + List(3) { 0 }

    private fun location(lat: Double, lon: Double, heightM: Double): List<Int> {
        val h = ((heightM + 1000) / 0.5).toInt()
        return listOf(0x12, 0x20, 90, 20, 0) + le32((lat * 1e7).toInt()) + le32((lon * 1e7).toInt()) +
            le16(0) + le16(h) + le16(h) + listOf(0, 0) + le16(0) + listOf(0, 0)
    }

    private fun system(lat: Double, lon: Double): List<Int> =
        listOf(0x42, 0x01) + le32((lat * 1e7).toInt()) + le32((lon * 1e7).toInt()) + List(15) { 0 }

    @Test fun remoteIdOverBleDecodesEachMessage() {
        fun rid(msg: List<Int>) = ad(0x16, *(listOf(0xFA, 0xFF, 0x0D, 0x01) + msg).toIntArray())
        assertEquals(25, location(44.5, 11.3, 80.0).size)
        assertEquals(25, basicId("X").size)
        assertEquals(25, system(0.0, 0.0).size)
        val a = RemoteId.decode(ble(0x02_00_00_00_00_01L, rid(basicId("1581F5FKD229400F1234"))))!!
        assertEquals("1581F5FKD229400F1234", a.uasId)
        assertEquals(2, a.uaType)
        val l = RemoteId.decode(ble(0x02_00_00_00_00_01L, rid(location(44.5, 11.3, 80.0))))!!
        assertEquals(44.5, l.lat!!, 1e-6)
        assertEquals(11.3, l.lon!!, 1e-6)
        assertEquals(80.0, l.heightM!!, 0.6)
        assertEquals(5.0, l.speedMps!!, 1e-9)
        assertEquals(90.0, l.headingDeg!!, 1e-9)
        val s = RemoteId.decode(ble(0x02_00_00_00_00_01L, rid(system(44.49, 11.31))))!!
        assertEquals(44.49, s.operatorLat!!, 1e-6)
    }

    @Test fun remoteIdOverWifiBeaconMessagePack() {
        val pack = listOf(0xF2, 25, 3) + basicId("DRONE-7") + location(44.5, 11.3, 50.0) + system(44.49, 11.31)
        val ie = listOf(221, 5 + pack.size, 0xFA, 0x0B, 0xBC, 0x0D, 0x07) + pack
        val ies = ie.map { it.toByte() }.toByteArray()
        val d = RemoteId.fromIes(ies)!!
        assertEquals("DRONE-7", d.uasId)
        assertEquals(44.5, d.lat!!, 1e-6)
        assertEquals(44.49, d.operatorLat!!, 1e-6)
        assertNull(RemoteId.fromIes(byteArrayOf(221.toByte(), 4, 0x00, 0x50, 0xF2.toByte(), 1)))
    }

    @Test fun dronesSummaryGivesDistanceAndBearingFromYou() {
        fun rid(msg: List<Int>, t: Long) = ble(0x02_00_00_00_00_09L, ad(0x16, *(listOf(0xFA, 0xFF, 0x0D, 0x01) + msg).toIntArray()), t = t)
        val here = GeoFix(0, 44.5000, 11.3000, 5f)
        // ~1.1 km north
        val list = Drones.summarize(listOf(rid(basicId("SER1"), 0), rid(location(44.5100, 11.3000, 60.0), 1000)), here)
        val d = list.single()
        assertTrue(d.remoteId)
        assertEquals("SER1", d.label)
        assertEquals(1112.0, d.distanceM!!, 15.0)
        assertEquals("N", Drones.compass(d.bearingDeg!!))
    }

    @Test fun signatureOnlyDroneIsListedWithoutPosition() {
        val d = Drones.summarize(listOf(beacon(0x60_60_1F_00_00_01L, "DJI-MINI4-1234")), null).single()
        assertFalse(d.remoteId)
        assertNull(d.distanceM)
        assertNotNull(d.label)
    }
}
