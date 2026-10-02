// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociatedClientsTest {
    private fun w(kind: WifiKind, addr: Long, bssid: Long?, ssid: String = "") =
        Sighting(0, Radio.WIFI, MacAddress(addr), -50, wifi = WifiDetail(kind, 6, ssid.toByteArray(), bssid?.let { MacAddress(it) }, 0, ByteArray(0)))

    @Test fun groupsClientsUnderTheirAp() {
        val ap = 0xAAAAAAAAAAAAL
        val data = listOf(
            w(WifiKind.DATA, 0x121111111111L, ap),
            w(WifiKind.DATA, 0x221111111111L, ap),
            w(WifiKind.DATA, 0x121111111111L, ap), // repeat same client
            w(WifiKind.BEACON, ap, ap, "HomeNet"),
        )
        val aps = AssociatedClients.of(data)
        assertEquals(1, aps.size)
        assertEquals(2, aps[0].clients.size)
        assertEquals("HomeNet", aps[0].ssid)
    }

    @Test fun ignoresNonDataAndBroadcast() {
        val ap = 0xBBL
        val s = listOf(
            w(WifiKind.PROBE_REQ, 0x1L, null),
            w(WifiKind.DATA, 0xFFFFFFFFFFFFL, ap), // broadcast client -> ignored
        )
        assertTrue(AssociatedClients.of(s).all { it.clients.isEmpty() } || AssociatedClients.of(s).isEmpty())
    }
}
