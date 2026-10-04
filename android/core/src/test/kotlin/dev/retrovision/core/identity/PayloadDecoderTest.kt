// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadDecoderTest {
    private fun ad(type: Int, vararg b: Int) = byteArrayOf((b.size + 1).toByte(), type.toByte()) + b.map { it.toByte() }.toByteArray()
    private fun name(n: String) = byteArrayOf((n.length + 1).toByte(), 0x09) + n.toByteArray()

    @Test fun airPodsProModelFromProximityPairing() {
        // Apple (0x004C), type 0x07 len 0x19: prefix 01, model 0x0E20, status, battery...
        val d = PayloadDecoder.decode(AdvertisementInfo.of(ad(0xFF, 0x4C, 0x00, 0x07, 0x05, 0x01, 0x0E, 0x20, 0x55, 0x88)))
        assertEquals("AirPods Pro", d?.model)
    }

    @Test fun nearbyInfoDriving() {
        val d = PayloadDecoder.decode(AdvertisementInfo.of(ad(0xFF, 0x4C, 0x00, 0x10, 0x03, 0x1D, 0x18, 0x00)))
        assertEquals(PayloadDecoder.AppleActivity.DRIVING, d?.activity)
    }

    @Test fun fastPairModelInPairingMode() {
        val d = PayloadDecoder.decode(AdvertisementInfo.of(ad(0x16, 0x2C, 0xFE, 0x00, 0x00, 0x06)))
        assertEquals("Google Pixel Buds", d?.model)
    }

    @Test fun nothingToSayIsNull() {
        assertNull(PayloadDecoder.decode(AdvertisementInfo.of(name("Hello"))))
    }

    @Test fun carNameIsAVehicleInTheCatalog() {
        val s = Sighting(0, Radio.BLE, MacAddress(0x02_11_22_33_44_55L), -60, ble = BleDetail(BleAddressKind.RANDOM_STATIC, 4, name("BMW 12345")))
        assertTrue(NotableCatalog.match(s).any { it.kind == NotableKind.VEHICLE && !it.kind.attention })
    }
}
