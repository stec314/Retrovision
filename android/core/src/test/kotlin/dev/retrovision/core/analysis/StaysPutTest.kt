// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Walking loops around a city-centre block for two hours: a shop's access point in the middle is
 * heard at many 100 m "places", for the whole time, from every side. That is not following.
 */
class StaysPutTest {
    private val lat0 = 44.835
    private val lon0 = 11.620
    private val mLat = 1.0 / 111_320.0
    private val mLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))
    private val now = 2 * 3600_000L

    /** Loops of a 400 x 300 m block at 1.2 m/s, a fix every 5 s. Coordinates in metres from the block's SW corner. */
    private fun loopXY(t: Long): Pair<Double, Double> {
        val per = 1400.0
        var d = (t / 1000.0 * 1.2) % per
        return when {
            d < 400 -> d to 0.0
            d < 700 -> { d -= 400; 400.0 to d }
            d < 1100 -> { d -= 700; 400.0 - d to 300.0 }
            else -> { d -= 1100; 0.0 to 300.0 - d }
        }
    }

    private fun fix(t: Long): GeoFix { val (x, y) = loopXY(t); return GeoFix(t, lat0 + y * mLat, lon0 + x * mLon, 5f) }
    private val fixes = (0..now / 5000).map { fix(it * 5000) }

    private fun ap(id: String, x: Double, y: Double, rnd: Random) = (0..now / 15_000).mapNotNull { i ->
        val t = i * 15_000
        val (px, py) = loopXY(t)
        val d = maxOf(1.0, Math.hypot(px - x, py - y))
        val rssi = (-35 - 22 * Math.log10(d) + rnd.nextGaussian() * 4).toInt()
        if (rssi < -92) null else EntitySighting(
            id,
            Sighting(
                t, Radio.WIFI, MacAddress(0x0A18D6220167L), rssi,
                wifi = WifiDetail(WifiKind.BEACON, 6, "TolinoLibraccio".toByteArray(), MacAddress(0x0A18D6220167L), 0, ByteArray(0)),
                probeId = "probe",
            ),
        )
    }

    /** A tag in your bag: same signal everywhere, give or take noise. */
    private fun carried(id: String, rnd: Random) = (0..now / 15_000).map { i ->
        val t = i * 15_000
        EntitySighting(
            id,
            Sighting(
                t, Radio.BLE, MacAddress(0x00AABBCCDDEEL), (-55 + rnd.nextGaussian() * 5).toInt(),
                ble = BleDetail(BleAddressKind.PUBLIC, 0, byteArrayOf(2, 1, 6)), probeId = "probe",
            ),
        )
    }

    private val analyzer = Analyzer(AnalysisConfig(lookbackMs = 2 * 3600_000L))

    @Test fun shopApInsideTheBlockIsNotAFollower() {
        val r = analyzer.analyze(now, ap("ap", 200.0, 150.0, Random(1)), fixes).entities.single()
        assertTrue(r.placeIds.size >= 3)
        assertTrue(r.reasons.any { it is Reason.StaysPut })
        assertFalse(r.alert)
        // A multi-SSID router's locally administered BSSID is not a phone hotspot.
        assertTrue(r.reasons.none { it is Reason.MovingAccessPoint })
    }

    @Test fun apOnOneSideIsNotAFollowerEither() {
        val r = analyzer.analyze(now, ap("ap", 50.0, -30.0, Random(2)), fixes).entities.single()
        assertTrue(r.reasons.any { it is Reason.StaysPut })
        assertFalse(r.alert)
    }

    @Test fun somethingCarriedWithYouIsNeverCalledFixed() {
        // Many seeds: the split-half estimate must not invent a fade from noise.
        for (seed in 1L..30L) {
            val r = analyzer.analyze(now, carried("tag", Random(seed)), fixes).entities.single()
            assertTrue("seed $seed", r.reasons.none { it is Reason.StaysPut })
        }
    }

    /** Old-town reality: walls and crowds swamp the fade. Only heard around the block, never beyond. */
    @Test fun apHeardOnlyAroundOneAreaNeverAlerts() {
        val rnd = Random(5)
        val s = (0..now / 15_000).map { i ->
            val t = i * 15_000
            EntitySighting(
                "gree",
                Sighting(
                    t, Radio.WIFI, MacAddress(0x502CC6020546L), (-80 + rnd.nextGaussian() * 8).toInt(),
                    wifi = WifiDetail(WifiKind.BEACON, 1, "c6020546".toByteArray(), MacAddress(0x502CC6020546L), 0, ByteArray(0)),
                    probeId = "probe",
                ),
            )
        }
        val r = analyzer.analyze(now, s, fixes).entities.single()
        assertTrue(r.reasons.any { it is Reason.OneAreaOnly || it is Reason.StaysPut })
        assertFalse(r.alert)
    }

    /** Same for what is not an access point: a probing smart plug and a TV's Bluetooth around the block. */
    @Test fun fixedClientsAndBluetoothHeardOnlyAroundOneAreaNeverAlert() {
        val rnd = Random(6)
        /** Heard whenever you are within [reachM] of (x, y), at a signal that does not follow distance. */
        fun heard(id: String, x: Double, y: Double, reachM: Double, mk: (Long, Int) -> Sighting) = (0..now / 15_000).mapNotNull { i ->
            val t = i * 15_000
            val (px, py) = loopXY(t)
            if (Math.hypot(px - x, py - y) > reachM) null else EntitySighting(id, mk(t, (-80 + rnd.nextGaussian() * 8).toInt()))
        }
        val plug = heard("plug", 200.0, 150.0, 1_000.0) { t, rssi ->
            Sighting(t, Radio.WIFI, MacAddress(0x24A160388581L), rssi,
                wifi = WifiDetail(WifiKind.PROBE_REQ, 1, ByteArray(0), null, 0, ByteArray(0)), probeId = "probe")
        }
        val tv = heard("tv", 200.0, 0.0, 120.0) { t, rssi -> // on the south side, ~100 m of Bluetooth reach
            Sighting(t, Radio.BLE, MacAddress(0xBC351E5A99C7L), rssi, ble = BleDetail(BleAddressKind.PUBLIC, 0, byteArrayOf(2, 1, 6)), probeId = "probe")
        }
        for (r in analyzer.analyze(now, plug + tv, fixes).entities) {
            assertTrue(r.placeIds.size >= 2)
            assertTrue(r.entityId, r.reasons.any { it is Reason.OneAreaOnly || it is Reason.StaysPut })
            assertFalse(r.entityId, r.alert)
        }
    }

    /** A Bluetooth device still there 2 km later is not "one area". */
    @Test fun bluetoothThatTravelsFarStillAlerts() {
        val walk = (0..now / 5000).map { i -> GeoFix(i * 5000, lat0, lon0 + (i * 5000 / 1000.0 * 1.4) * mLon, 5f) } // 10 km east
        val r = analyzer.analyze(now, carried("bag", Random(8)), walk).entities.single()
        assertTrue(r.reasons.none { it is Reason.OneAreaOnly || it is Reason.StaysPut })
        assertTrue(r.alert)
    }

    /** A hotspot that is still there 2 km later is not "one area". */
    @Test fun apThatTravelsFarStillAlerts() {
        val walk = (0..now / 5000).map { i -> GeoFix(i * 5000, lat0, lon0 + (i * 5000 / 1000.0 * 1.4) * mLon, 5f) } // 10 km east
        val s = (0..now / 15_000).map { i ->
            val t = i * 15_000
            EntitySighting(
                "car",
                Sighting(
                    t, Radio.WIFI, MacAddress(0x00AABB112233L), -60,
                    wifi = WifiDetail(WifiKind.BEACON, 6, "Golf".toByteArray(), MacAddress(0x00AABB112233L), 0, ByteArray(0)),
                    probeId = "probe",
                ),
            )
        }
        val r = analyzer.analyze(now, s, walk).entities.single()
        assertTrue(r.reasons.none { it is Reason.OneAreaOnly || it is Reason.StaysPut })
        assertTrue(r.alert)
    }

    @Test fun rotatedAddressesShowHowTheyWereLinked() {
        val a = MacAddress(0x02AAAAAAAAAAL)
        val b = MacAddress(0x02BBBBBBBBBBL)
        fun probe(mac: MacAddress, t: Long) = EntitySighting(
            "wifi:$a",
            Sighting(t, Radio.WIFI, mac, -60, wifi = WifiDetail(WifiKind.PROBE_REQ, 1, ByteArray(0), null, 0, ByteArray(0))),
        )
        val r = analyzer.analyze(now, listOf(probe(a, 1000), probe(a, 2000), probe(b, 70_000)), fixes).entities.single()
        assertTrue(r.addressLinks.map { it.via } == listOf(LinkVia.ORIGINAL, LinkVia.SEQUENCE))
    }

    @Test fun visitsSayWhenAndAtWhichOfYourPlaces() {
        val r = analyzer.analyze(now, ap("ap", 200.0, 150.0, Random(1)), fixes).entities.single()
        assertTrue(r.visits.size >= 3)
        assertTrue(r.visits.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) // oldest first, no overlap
        assertTrue(r.visits.sumOf { it.sightings } == r.sightings)
        assertTrue(r.visits.all { it.placeId >= 0 && it.lat != null })
    }

    @Test fun tooFewSamplesDecideNothing() {
        val f = fixes.take(10).map { Triple(it, -60, "p") }
        assertNull(analyzer.stationary(f))
    }
}
