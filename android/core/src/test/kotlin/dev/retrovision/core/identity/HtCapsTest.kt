// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtCapsTest {
    /** Builds an HT Capabilities element (IE 45) carrying [caps]/[ampdu]/[ext]. */
    private fun ie45(caps: Int, ampdu: Int = 0x1b, ext: Int = 0, lenOverride: Int? = null): ByteArray {
        val content = ByteArray(26)
        content[0] = (caps and 0xFF).toByte()
        content[1] = ((caps ushr 8) and 0xFF).toByte()
        content[2] = ampdu.toByte()
        content[19] = (ext and 0xFF).toByte()
        content[20] = ((ext ushr 8) and 0xFF).toByte()
        val len = lenOverride ?: content.size
        return byteArrayOf(45, len.toByte()) + content.copyOf(len)
    }

    @Test fun decodesRealDeviceValues() {
        // 0x01ad: OnePlus Nord / Huawei P10 / Redmi Note 9S in Puig et al. (2026)
        val p = HtCaps.fromIes(ie45(0x01ad))!!
        assertTrue(p.ldpc)                 // bit 0
        assertFalse(p.chWidth40)           // bit 1 -> 20 MHz only
        assertEquals(3, p.smPowerSave)     // bits 2-3 -> disabled
        assertFalse(p.greenfield)          // bit 4
        assertTrue(p.shortGi20)            // bit 5
        assertFalse(p.shortGi40)           // bit 6
        assertTrue(p.txStbc)               // bit 7
        assertEquals(1, p.rxStbc)          // bits 8-9 -> 1 stream
        assertFalse(p.fortyIntolerant)     // bit 14

        // 0x402d: iPhone 12 / 7 — Forty MHz Intolerant set, no STBC
        val a = HtCaps.fromIes(ie45(0x402d))!!
        assertTrue(a.ldpc)
        assertTrue(a.shortGi20)
        assertFalse(a.txStbc)
        assertEquals(0, a.rxStbc)
        assertTrue(a.fortyIntolerant)
    }

    @Test fun summaryIsReadable() {
        assertEquals("HT 20MHz · LDPC · SGI20 · TxSTBC · 1×RxSTBC", HtCaps.fromIes(ie45(0x01ad))!!.summary())
    }

    @Test fun hammingSeparatesClassesButNotSameModel() {
        val nord = HtCaps.fromIes(ie45(0x01ad))!!
        val p10 = HtCaps.fromIes(ie45(0x01ad))!!
        val iphone = HtCaps.fromIes(ie45(0x402d))!!
        // Same model -> identical HT caps -> Hamming 0 (why it can't drive linking).
        assertEquals(0, HtCaps.hamming(nord, p10))
        assertTrue(HtCaps.hamming(nord, iphone) > 0)
    }

    @Test fun missingOrShortElementIsNull() {
        assertNull(HtCaps.fromIes(byteArrayOf(0, 0))) // SSID IE only
        assertNull(HtCaps.fromIes(ie45(0x01ad, lenOverride = 10))) // too short for ext caps
        assertNull(HtCaps.fromIes(ByteArray(0)))
    }
}
