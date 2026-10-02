package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {
    private val now = 10 * 3600_000L
    private val m = 1.0 / 111_320.0

    private fun probe(id: String, mac: Long, t: Long, ssid: String) = EntitySighting(
        id, Sighting(t, Radio.WIFI, MacAddress(mac), -60, wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ssid.toByteArray(), null, 0, ByteArray(0))),
    )

    // ---- network linking ----
    @Test fun linksRotatedAddressesAskingForSameRareNetworks() {
        val s = listOf("Hotel_Rimini", "Casa_Bianchi", "Bar_Sport_2").flatMap { listOf(probe("a", 0x02_00_00_00_00_01L, 0, it), probe("b", 0x02_00_00_00_00_02L, 60_000, it)) }
        val l = NetworkLinker.link(s)
        assertEquals(l.root["a"], l.root["b"])
    }

    @Test fun commonNetworksDoNotLink() {
        val s = (1..6).flatMap { n -> listOf("eduroam", "FreeWiFi_Airport").map { probe("d$n", 0x02_00_00_00_00_00L + n, 0, it) } }
        assertTrue(NetworkLinker.link(s).root.isEmpty())
    }

    @Test fun onlyYourOwnNetworksDoNotLinkHousehold() {
        val own = setOf("CasaSte", "CasaSte_5G")
        val s = own.flatMap { listOf(probe("me", 0x02_00_00_00_00_01L, 0, it), probe("partner", 0x02_00_00_00_00_02L, 0, it)) }
        assertTrue(NetworkLinker.link(s, own).root.isEmpty())
    }

    // ---- someone asks for your network ----
    private fun fixAt(t: Long, northM: Double) = GeoFix(t, 44.5 + northM * m, 11.3, 5f, 1.0f)

    @Test fun askingForYourNetworkAwayFromHomeIsFlagged() {
        val t0 = now - 60 * 60_000L
        val fixes = (0..60).map { fixAt(t0 + it * 60_000L, if (it < 30) 0.0 else 2000.0) }
        val home = FamiliarPlace(1, 44.5, 11.3, 150.0)
        val atHome = probe("x", 0x02_00_00_00_00_05L, t0 + 5 * 60_000L, "CasaSte")
        val away = probe("y", 0x02_00_00_00_00_06L, t0 + 45 * 60_000L, "CasaSte")
        val r = Analyzer().analyze(now, listOf(atHome, away), fixes, IgnoreList(apSsids = setOf("CasaSte")), familiar = listOf(home))
        assertTrue(r.entities.first { it.entityId == "y" }.reasons.any { it is Reason.ProbesForYourNetwork })
        assertTrue(r.entities.first { it.entityId == "x" }.reasons.none { it is Reason.ProbesForYourNetwork })
    }

    // ---- groups ----
    @Test fun devicesAtTheSamePlacesAndTimesFormAGroup() {
        val t0 = now - 90 * 60_000L
        val fixes = (0..90).map { fixAt(t0 + it * 60_000L, (it / 15) * 500.0) } // 6 places
        fun dev(id: String, mac: Long) = (0..90 step 2).map { probe(id, mac, t0 + it * 60_000L, "") }
        val other = (0..40 step 2).map { probe("o", 0x12_00_00_00_00_09L, t0 + it * 60_000L, "") }
        val r = Analyzer(AnalysisConfig(lookbackMs = 3 * 3600_000L)).analyze(
            now, dev("p", 0x12_00_00_00_00_01L) + dev("w", 0x12_00_00_00_00_02L) + other, fixes,
        )
        val g = r.entities.first { it.entityId == "p" }.reasons.filterIsInstance<Reason.TravelsInGroup>().single()
        assertEquals(2, g.size)
        assertTrue(r.entities.first { it.entityId == "o" }.reasons.none { it is Reason.TravelsInGroup })
    }
}
