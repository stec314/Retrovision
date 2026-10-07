// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.analysis.Analyzer
import dev.retrovision.core.analysis.EntitySighting
import dev.retrovision.core.analysis.IgnoreList
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSignatureTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Real adverts from a field backup.
    private val stefano = hex("020102090953746566616e6f2003030518") // "Stefano " + Current Time Service
    private val fmdn = hex("0201061916aafe40c6e8d4ad4d28a3f96b510734")   // Google Find Hub EID
    private val nameless = hex("0201021716f1fc0453e9bd2e7083b9f5ac1896b5")

    @Test fun namedAdvertGetsNameAndShape() {
        val s = BleSignature.of(stefano)
        assertEquals("n=Stefano|u=1805", s)
        assertEquals("Stefano", BleSignature.displayName(s!!))
    }

    @Test fun trackersAndNamelessAdvertsNeverGetOne() {
        assertNull(BleSignature.of(fmdn))
        assertNull(BleSignature.of(nameless))
    }

    @Test fun ignoreRowsSplitIntoIdsAndSignatures() {
        val l = IgnoreList.fromRows(listOf("ble:aa:bb:cc:dd:ee:ff", "sig:n=Stefano|u=1805"))
        assertEquals(setOf("ble:aa:bb:cc:dd:ee:ff"), l.entityIds)
        assertEquals(setOf("n=Stefano|u=1805"), l.bleSignatures)
    }

    @Test fun signatureIgnoresEveryRotatedAddress() {
        val sightings = (0 until 6).map { i ->
            val mac = MacAddress(0x4D_0000_0000_00L + i)
            EntitySighting("ble:$mac", Sighting(i * 600_000L, Radio.BLE, mac, -45, ble = BleDetail(BleAddressKind.RANDOM_RESOLVABLE, 1, stefano)))
        }
        val now = 6 * 600_000L
        val open = Analyzer().analyze(now, sightings, emptyList(), IgnoreList())
        assertTrue(open.entities.size + open.others.size > 0)
        val r = Analyzer().analyze(now, sightings, emptyList(), IgnoreList(bleSignatures = setOf("n=Stefano|u=1805")))
        assertEquals(0, r.entities.size + r.others.size)
        assertNotNull(open.entities.firstOrNull()?.bleSignature ?: open.others.firstOrNull())
    }
}
