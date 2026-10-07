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

class WifiThreatsTest {
    private fun w(kind: WifiKind, addr: Long, ssid: String = "", bssid: Long? = null, t: Long = 0, merged: Int = 1) =
        Sighting(t, Radio.WIFI, MacAddress(addr), -50, merged, wifi = WifiDetail(kind, 6, ssid.toByteArray(), bssid?.let { MacAddress(it) }, 0, ByteArray(0)))

    @Test fun deauthFloodDetected() {
        val frames = (0 until 120).map { w(WifiKind.DEAUTH, 0x121111111111L, bssid = 0xAAAAAAAAAAAAL, t = it * 50L) }
        val t = WifiThreats.detect(frames, emptySet())
        assertEquals(1, t.size)
        assertEquals(WifiThreats.Kind.DEAUTH_FLOOD, t[0].kind)
        assertEquals(MacAddress(0xAAAAAAAAAAAAL), t[0].bssid)
        assertTrue(t[0].severity > 0.2)
    }

    @Test fun normalScatteredDeauthsAreNotAFlood() {
        // 30 deauths spread across 15 different networks = normal background, not an attack.
        val frames = (0 until 30).map { w(WifiKind.DEAUTH, 0x120000000000L + it, bssid = 0xAA0000000000L + (it % 15), t = it.toLong()) }
        assertTrue(WifiThreats.detect(frames, emptySet()).isEmpty())
    }

    @Test fun fewDeauthsOnOneNetworkAreNotAFlood() {
        val frames = (0 until 12).map { w(WifiKind.DEAUTH, 0x121111111111L, bssid = 0xAAL, t = it.toLong()) }
        assertTrue(WifiThreats.detect(frames, emptySet()).isEmpty())
    }

    @Test fun karmaApAnsweringManySsids() {
        val ap = 0xBBBBBBBBBBBBL
        val frames = listOf("home", "office", "Starbucks", "FreeWiFi", "Andrea", "iPhone").mapIndexed { i, ssid ->
            w(WifiKind.PROBE_RESP, ap, ssid, t = i.toLong())
        }
        val t = WifiThreats.detect(frames, emptySet())
        assertTrue(t.any { it.kind == WifiThreats.Kind.KARMA_AP && it.distinctSsids >= 5 })
    }

    @Test fun normalApAnsweringOneSsidIsFine() {
        val frames = (0 until 10).map { w(WifiKind.PROBE_RESP, 0xCCL, "home", t = it.toLong()) }
        assertTrue(WifiThreats.detect(frames, emptySet()).none { it.kind == WifiThreats.Kind.KARMA_AP })
    }

    @Test fun evilTwinOfOwnNetwork() {
        val frames = listOf(
            w(WifiKind.BEACON, 0xDD0000000001L, "CasaMia", bssid = 0xDD0000000001L),
            w(WifiKind.BEACON, 0xEE0000000002L, "CasaMia", bssid = 0xEE0000000002L), // a second AP claiming your SSID
        )
        val t = WifiThreats.detect(frames, setOf("CasaMia"))
        assertTrue(t.any { it.kind == WifiThreats.Kind.EVIL_TWIN_OWN && it.bssids.size == 2 })
    }

    @Test fun ownNetworkFromSingleBssidIsFine() {
        val frames = listOf(w(WifiKind.BEACON, 0xDD1L, "CasaMia", bssid = 0xDD1L))
        assertTrue(WifiThreats.detect(frames, setOf("CasaMia")).isEmpty())
    }

    // ---- Evil twin: confirmed access points, and padded look-alike names ----
    private fun mac(v: Long) = dev.retrovision.core.model.MacAddress(v)

    @Test fun dualBandRouterYouConfirmedIsNotATwin() {
        // Field case: one router, 2.4 and 5 GHz, both confirmed: flagged on every run before.
        val frames = listOf(w(WifiKind.BEACON, 0x141459549ccL, "Home", 0x141459549ccL), w(WifiKind.BEACON, 0x141459549cdL, "Home", 0x141459549cdL))
        val t = WifiThreats.detect(frames, setOf("Home"), trustedBssids = setOf(mac(0x141459549ccL), mac(0x141459549cdL)))
        assertTrue(t.none { it.kind == WifiThreats.Kind.EVIL_TWIN_OWN })
    }

    @Test fun unconfirmedAccessPointNextToYoursIsATwin() {
        val frames = listOf(w(WifiKind.BEACON, 0x141459549ccL, "Home", 0x141459549ccL), w(WifiKind.BEACON, 0x02AABBCCDDEEL, "Home", 0x02AABBCCDDEEL))
        val t = WifiThreats.detect(frames, setOf("Home"), trustedBssids = setOf(mac(0x141459549ccL)))
        val twin = t.single { it.kind == WifiThreats.Kind.EVIL_TWIN_OWN }
        assertEquals(listOf(mac(0x02AABBCCDDEEL)), twin.bssids)
    }

    @Test fun paddedCopiesOfYourNetworkAreCaught() {
        // Field case: "SecRip", "SecRip  ", "SecRip       " from random BSSIDs (beacon spam cloning).
        val frames = listOf("Home  ", "Home       ", "Home\u200B").mapIndexed { i, s -> w(WifiKind.BEACON, 0x020000000010L + i, s, 0x020000000010L + i) }
        val t = WifiThreats.detect(frames, setOf("Home"), trustedBssids = setOf(mac(0x141459549ccL)))
        val twin = t.single { it.kind == WifiThreats.Kind.EVIL_TWIN_OWN }
        assertEquals("Home", twin.ssid)
        assertEquals(3, twin.bssids.size)
        assertEquals(3, twin.ssids.size)
    }

    @Test fun normalisedNamesIgnoreSpacesInvisiblesAndCase() {
        assertEquals("secrip", WifiThreats.normaliseSsid(" SecRip \u200B  "))
    }
}
