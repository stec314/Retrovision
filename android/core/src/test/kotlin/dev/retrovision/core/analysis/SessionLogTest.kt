// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.identity.EntityResolver
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import dev.retrovision.core.session.SessionLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SessionLogTest {
    private fun wifi(t: Long, seq: Int) = Sighting(
        t, Radio.WIFI, MacAddress.parse("02:11:22:33:44:%02x".format(seq)), -61, 2,
        wifi = WifiDetail(WifiKind.PROBE_REQ, 6, "café".toByteArray(), MacAddress.parse("aa:bb:cc:dd:ee:ff"), seq, byteArrayOf(1, 2, 3), true),
    )

    private val ble = Sighting(
        5_000, Radio.BLE, MacAddress.parse("11:22:33:44:55:66"), -70,
        ble = BleDetail(BleAddressKind.RANDOM_RESOLVABLE, 3, byteArrayOf(2, 1, 6, 0x1a, 0xff.toByte()), -12),
    )

    @Test fun roundTrip() {
        val bos = ByteArrayOutputStream()
        SessionLog.Writer(bos).use { w ->
            w.write(wifi(1000, 7)); w.write(ble); w.write(GeoFix(1500, 45.1, 11.2, 4f, 3.5f)); w.write(GeoFix(2500, 45.1, 11.2, 4f))
        }
        val r = SessionLog.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(2, r.sightings.size)
        assertEquals(2, r.fixes.size)
        val w = r.sightings[0].wifi!!
        assertEquals("café", w.ssidText)
        assertEquals(WifiKind.PROBE_REQ, w.kind)
        assertTrue(w.iesTruncated)
        assertArrayEquals(byteArrayOf(1, 2, 3), w.ies)
        assertEquals(2, r.sightings[0].mergedCount)
        assertEquals(BleAddressKind.RANDOM_RESOLVABLE, r.sightings[1].ble!!.addressKind)
        assertEquals(-12, r.sightings[1].ble!!.txPowerDbm)
        assertEquals(3.5f, r.fixes[0].speedMps)
        assertNull(r.fixes[1].speedMps)
    }

    @Test fun truncatedFileKeepsWhatWasWritten() {
        val bos = ByteArrayOutputStream()
        val w = SessionLog.Writer(bos)
        for (i in 0 until 200) w.write(wifi(i * 1000L, i % 200))
        // No close(): the phone died. Only what was flushed is on disk.
        val bytes = bos.toByteArray()
        val r = SessionLog.read(ByteArrayInputStream(bytes))
        assertTrue("got ${r.sightings.size}", r.sightings.size in 64..200)
    }

    @Test fun garbageIsRejected() {
        val ex = runCatching { SessionLog.read(ByteArrayInputStream(ByteArray(40) { 7 })) }.exceptionOrNull()
        assertTrue(ex != null)
    }

    @Test fun replayGivesTheSameAnalysisAsLive() {
        val s = (0 until 20).map { wifi(it * 30_000L, it) }
        val fixes = (0 until 20).map { GeoFix(it * 30_000L, 45.0, 11.0 + it * 0.002, 5f) }
        val bos = ByteArrayOutputStream()
        SessionLog.Writer(bos).use { w -> s.forEach { w.write(it) }; fixes.forEach { w.write(it) } }
        val rec = SessionLog.read(ByteArrayInputStream(bos.toByteArray()))

        fun run(ss: List<Sighting>, ff: List<GeoFix>): List<Pair<String, Double>> {
            val res = EntityResolver()
            val es = ss.map { EntitySighting(res.resolve(it).entityId, it) }
            return Analyzer().analyze(20 * 30_000L, es, ff).entities.map { it.entityId to it.score }
        }
        assertEquals(run(s, fixes), run(rec.sightings, rec.fixes))
    }
}
