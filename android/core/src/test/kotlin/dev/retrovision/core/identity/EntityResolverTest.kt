// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResolverTest {
    // ---- helpers ----
    /** A locally-administered (random) 48-bit address ending in [tail]. */
    private fun rnd(tail: Long) = MacAddress(0xC2_0000_0000_00L or tail)

    private fun adv(vararg structs: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(byteArrayOf(2, 1, 6).toList()) // Flags
        for (s in structs) out.addAll(s.toList())
        return out.toByteArray()
    }

    private fun name(n: String): ByteArray {
        val b = n.toByteArray()
        return (byteArrayOf((b.size + 1).toByte(), 0x09) + b)
    }

    private fun manuf(vararg bytes: Int) =
        byteArrayOf((bytes.size + 1).toByte(), 0xFF.toByte()) + bytes.map { it.toByte() }.toByteArray()

    private fun ble(addr: MacAddress, t: Long, rssi: Int, advData: ByteArray) =
        Sighting(t, Radio.BLE, addr, rssi, ble = BleDetail(BleAddressKind.RANDOM_RESOLVABLE, 1, advData))

    private fun probe(addr: MacAddress, t: Long, seq: Int, ies: ByteArray) =
        Sighting(t, Radio.WIFI, addr, -50, wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ByteArray(0), null, seq, ies))

    // ---- Wi-Fi regression ----
    @Test fun wifiLinksRotatedProbesByFingerprintAndSeq() {
        val r = EntityResolver()
        val ies = byteArrayOf(0x01, 0x04, 0x02, 0x04, 0x0b, 0x16, 0x32, 0x08)
        val a = r.resolve(probe(rnd(0x01), 0, 100, ies))
        val b = r.resolve(probe(rnd(0x02), 20_000, 104, ies))
        assertEquals(a.entityId, b.entityId)
        assertTrue(b.linkedToExisting)
        assertEquals(1L, r.linksMade)
    }

    // ---- BLE carry-over: the good case ----
    @Test fun bleStitchesRotationOfASerialNamedDevice() {
        val r = EntityResolver()
        val whoop = adv(name("5AM0452823"))
        val a = r.resolve(ble(rnd(0x01), 0, -60, whoop))
        val b = r.resolve(ble(rnd(0x02), 10_000, -62, whoop))
        assertEquals(a.entityId, b.entityId)
        assertTrue(b.linkedToExisting)
        assertEquals(1L, r.bleLinksMade)
    }

    @Test fun bleDoesNotStitchDifferentSerials() {
        val r = EntityResolver()
        r.resolve(ble(rnd(0x01), 0, -60, adv(name("5AM0452823"))))
        val b = r.resolve(ble(rnd(0x02), 10_000, -61, adv(name("5AM0999999"))))
        assertFalse(b.linkedToExisting)
        assertEquals(0L, r.bleLinksMade)
    }

    // ---- BLE: the honest limit — anonymous phones are never stitched ----
    @Test fun bleDoesNotStitchAnonymousPhoneShapes() {
        val r = EntityResolver()
        // Apple continuity, no local name: shared by millions, must not link.
        val continuity = adv(manuf(0x4C, 0x00, 0x10, 0x05, 0x01, 0x02))
        val a = r.resolve(ble(rnd(0x01), 0, -55, continuity))
        val b = r.resolve(ble(rnd(0x02), 3_000, -56, continuity))
        assertNotEquals(a.entityId, b.entityId)
        assertFalse(b.linkedToExisting)
        assertEquals(0L, r.bleLinksMade)
    }

    // ---- BLE: ambiguity declines to link (single-candidate guard) ----
    @Test fun bleDoesNotLinkWhenTwoTrailsAreEquallyPlausible() {
        val r = EntityResolver()
        val band = adv(name("BAND0001"))
        r.resolve(ble(rnd(0x01), 0, -60, band))             // entity 1 @ -60
        val two = r.resolve(ble(rnd(0x02), 0, -73, band))   // 13 dB apart -> entity 2, not linked
        assertFalse(two.linkedToExisting)
        val three = r.resolve(ble(rnd(0x03), 1_000, -66, band)) // within 12 dB of BOTH -> ambiguous
        assertFalse(three.linkedToExisting)
        assertEquals(0L, r.bleLinksMade)
    }

    @Test fun bleRespectsRssiGap() {
        val r = EntityResolver()
        val band = adv(name("TAG12345"))
        r.resolve(ble(rnd(0x01), 0, -40, band))
        val far = r.resolve(ble(rnd(0x02), 5_000, -80, band)) // 40 dB apart -> different device
        assertFalse(far.linkedToExisting)
    }

    @Test fun blePruneForgetsOldTrails() {
        val r = EntityResolver()
        val band = adv(name("TAG12345"))
        r.resolve(ble(rnd(0x01), 0, -50, band))
        r.prune(10 * 60_000L) // past the 5-min BLE window
        val b = r.resolve(ble(rnd(0x02), 10 * 60_000L + 1000, -50, band))
        assertFalse(b.linkedToExisting)
    }
}
