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

    private fun probeRssi(addr: MacAddress, t: Long, seq: Int, rssi: Int, ies: ByteArray) =
        Sighting(t, Radio.WIFI, addr, rssi, wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ByteArray(0), null, seq, ies))

    @Test fun wifiDoesNotLinkAcrossBigRssiJump() {
        // Two same-model phones (same fingerprint) whose sequence numbers happen to line up, but
        // one is much closer than the other: the RSSI guard refuses the false merge.
        val r = EntityResolver()
        val ies = byteArrayOf(0x01, 0x04, 0x02, 0x04, 0x0b, 0x16, 0x32, 0x08)
        r.resolve(probeRssi(rnd(0x01), 0, 100, -40, ies))
        val b = r.resolve(probeRssi(rnd(0x02), 20_000, 104, -85, ies)) // 45 dB apart
        assertFalse(b.linkedToExisting)
        assertEquals(0L, r.linksMade)
    }

    @Test fun wifiStillLinksWithinRssiTolerance() {
        val r = EntityResolver()
        val ies = byteArrayOf(0x01, 0x04, 0x02, 0x04, 0x0b, 0x16, 0x32, 0x08)
        r.resolve(probeRssi(rnd(0x01), 0, 100, -55, ies))
        val b = r.resolve(probeRssi(rnd(0x02), 20_000, 104, -68, ies)) // 13 dB, normal fading
        assertTrue(b.linkedToExisting)
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

    // ---- BLE: handover (identical advert, new address, seconds after the old one went quiet) ----
    private fun uuids16(vararg u: Int) =
        byteArrayOf((u.size * 2 + 1).toByte(), 0x03) + u.flatMap { listOf((it and 0xFF).toByte(), (it shr 8).toByte()) }.toByteArray()

    private fun serviceData(uuid: Int, vararg bytes: Int) =
        byteArrayOf((bytes.size + 3).toByte(), 0x16, (uuid and 0xFF).toByte(), (uuid shr 8).toByte()) + bytes.map { it.toByte() }.toByteArray()

    /** Field case: a phone advertising "Stefano " + Current Time Service, new RPA every ~8 min. */
    private val phoneName = adv(name("Stefano "), uuids16(0x1805))

    @Test fun handoverLinksPlainNamedRotation() {
        val r = EntityResolver()
        val a = r.resolve(ble(rnd(0x01), 0, -45, phoneName))
        r.resolve(ble(rnd(0x01), 60_000, -46, phoneName))
        val b = r.resolve(ble(rnd(0x02), 65_000, -44, phoneName)) // 5 s handover
        assertEquals(a.entityId, b.entityId)
        assertTrue(b.linkedToExisting)
        assertEquals(1L, r.handoverLinksMade)
        assertEquals(0L, r.bleLinksMade)
    }

    @Test fun handoverNeedsTheOldAddressToGoQuiet() {
        val r = EntityResolver()
        r.resolve(ble(rnd(0x01), 10_000, -45, phoneName))
        val b = r.resolve(ble(rnd(0x02), 10_400, -45, phoneName)) // both talking: two devices
        assertFalse(b.linkedToExisting)
    }

    @Test fun handoverExpiresAfter30s() {
        val r = EntityResolver()
        r.resolve(ble(rnd(0x01), 0, -45, phoneName))
        val b = r.resolve(ble(rnd(0x02), 31_000, -45, phoneName))
        assertFalse(b.linkedToExisting)
    }

    @Test fun handoverRespectsTighterRssiTolerance() {
        val r = EntityResolver()
        r.resolve(ble(rnd(0x01), 0, -45, phoneName))
        val b = r.resolve(ble(rnd(0x02), 5_000, -55, phoneName)) // 10 dB: beyond 8
        assertFalse(b.linkedToExisting)
    }

    @Test fun handoverLinksIdenticalVariedPayloadWithoutName() {
        val r = EntityResolver()
        val sd = adv(serviceData(0xFCF1, 0x04, 0x53, 0xE9, 0xBD, 0x2E, 0x70, 0x83, 0xB9, 0xF5, 0xAC, 0x18, 0x96))
        val a = r.resolve(ble(rnd(0x01), 0, -33, sd))
        val b = r.resolve(ble(rnd(0x02), 4_000, -32, sd))
        assertEquals(a.entityId, b.entityId)
    }

    @Test fun handoverRefusesLowVarietyPayload() {
        // Field case: Google FE9F service data of all zeros, identical on many devices.
        val r = EntityResolver()
        val zeros = adv(serviceData(0xFE9F, *IntArray(20)))
        r.resolve(ble(rnd(0x01), 0, -60, zeros))
        val b = r.resolve(ble(rnd(0x02), 3_000, -60, zeros))
        assertFalse(b.linkedToExisting)
    }

    @Test fun handoverDeclinesWhenAmbiguous() {
        val r = EntityResolver()
        r.resolve(ble(rnd(0x01), 0, -45, phoneName))
        r.resolve(ble(rnd(0x02), 0, -47, phoneName))
        val c = r.resolve(ble(rnd(0x03), 5_000, -46, phoneName)) // fits both quiet trails
        assertFalse(c.linkedToExisting)
    }

    @Test fun handoverKeyRules() {
        assertEquals(null, EntityResolver.handoverKey(adv(name("5AM0452823")))) // serial: name path
        assertEquals(null, EntityResolver.handoverKey(adv(manuf(0x4C, 0x00, 0x10, 0x05, 0x01, 0x22, 0x33, 0x44, 0x55))))
        assertEquals(null, EntityResolver.handoverKey(adv(uuids16(0xFEF3)))) // nothing distinctive
        val k = EntityResolver.handoverKey(phoneName)!!
        assertFalse(k.startsWith("0201")) // Flags dropped: a probe that adds or drops them still matches
    }

    @Test fun rotatingBleIsDecidedByAddressKindNotTheWifiBit() {
        // Field case: RPAs like 4d:39:21:… have the 802.11 U/L bit clear; they still rotate.
        val r = EntityResolver()
        val a = r.resolve(ble(MacAddress(0x4D_3921_B8AD_1FL), 0, -45, phoneName))
        val b = r.resolve(ble(MacAddress(0x51_3CD2_2E4B_33L), 5_000, -45, phoneName))
        assertEquals(a.entityId, b.entityId)
        val c = r.resolve(ble(MacAddress(0x4D_0000_0000_01L), 0, -60, adv(name("5AM0452823"))))
        val d = r.resolve(ble(MacAddress(0x51_0000_0000_02L), 10_000, -61, adv(name("5AM0452823"))))
        assertEquals(c.entityId, d.entityId)
    }
}
