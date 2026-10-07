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

class ApUptimeTest {
    private fun beacon(id: String, bssid: Long, ssid: String, t: Long, bootMs: Long) = EntitySighting(
        id,
        Sighting(t, Radio.WIFI, MacAddress(bssid), -60, wifi = WifiDetail(WifiKind.BEACON, 6, ssid.toByteArray(), MacAddress(bssid), 0, ByteArray(0), tsfUs = (t - bootMs) * 1000)),
    )

    @Test fun renamedHotspotIsTheSameAp() {
        val boot = 1_000_000L
        val a = (0..10).map { beacon("a", 0x02_00_00_00_00_01L, "iPhone di Marco", 2_000_000L + it * 30_000, boot) }
        val b = (0..10).map { beacon("b", 0x02_00_00_00_00_02L, "Hotspot", 2_000_000L + 400_000 + it * 30_000, boot + 300) }
        val l = ApUptimeLinker.link(a + b)
        assertEquals(l.root["a"], l.root["b"])
        assertEquals("iPhone di Marco" to "Hotspot", l.renamed[l.root["a"]])
    }

    @Test fun differentBootIsDifferentAp() {
        val a = (0..10).map { beacon("a", 0x02_00_00_00_00_01L, "X", 2_000_000L + it * 30_000, 1_000_000L) }
        val b = (0..10).map { beacon("b", 0x02_00_00_00_00_02L, "Y", 2_400_000L + it * 30_000, 1_500_000L) }
        assertTrue(ApUptimeLinker.link(a + b).root.isEmpty())
    }

    @Test fun simultaneousSsidsOfOneRadioAreNotMerged() {
        val a = (0..10).map { beacon("a", 0x02_00_00_00_00_01L, "Main", 2_000_000L + it * 30_000, 1_000_000L) }
        val b = (0..10).map { beacon("b", 0x02_00_00_00_00_02L, "Guest", 2_000_000L + it * 30_000, 1_000_000L) }
        assertTrue(ApUptimeLinker.link(a + b).root.isEmpty())
    }

    @Test fun routersRestartedTogetherAfterAPowerCutAreNotOneAp() {
        // Field case: a city block's routers all booted within seconds after an outage; walking
        // past them one after another chained them into one "renamed AP" that followed you.
        val boot = 1_000_000L
        val s = (0 until 6).flatMap { k ->
            val id = "wifi:0$k:1$k:2$k:3$k:4$k:5$k"
            val bssid = (0x011121314151L + k * 0x010101010101L)
            val t0 = 2_000_000L + k * 5 * 60_000L
            listOf(beacon(id, bssid, "Net$k", t0, boot + k * 1_500L), beacon(id, bssid, "Net$k", t0 + 60_000L, boot + k * 1_500L))
        }
        val l = ApUptimeLinker.link(s)
        assertTrue("linked: ${l.root}", l.root.isEmpty())
    }
}
