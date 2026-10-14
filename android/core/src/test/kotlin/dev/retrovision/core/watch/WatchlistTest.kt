// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.watch

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import dev.retrovision.core.watch.Watchlist.EventKind
import dev.retrovision.core.watch.Watchlist.Kind
import dev.retrovision.core.watch.Watchlist.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {
    private val min = 60_000L
    private val addr = MacAddress.parse("aa:bb:cc:11:22:33")

    /** BLE advert with a complete local name (AD type 0x09). */
    private fun named(name: String): ByteArray {
        val n = name.toByteArray()
        return byteArrayOf((n.size + 1).toByte(), 0x09) + n
    }

    private fun ble(t: Long, rssi: Int = -60, a: MacAddress = addr, ad: ByteArray = ByteArray(0)) =
        Sighting(t, Radio.BLE, a, rssi, ble = BleDetail(BleAddressKind.PUBLIC, 1, ad))

    private fun probe(t: Long, ssid: String) = Sighting(
        t, Radio.WIFI, MacAddress.parse("02:00:00:00:00:01"), -50,
        wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ssid.toByteArray(), null, 1, ByteArray(0)),
    )

    @Test fun returnsAfterAbsence() {
        val e = Watchlist.Engine(listOf(Rule(1, "car", Kind.ADDRESS, "AA-BB-CC-11-22-33", minAbsenceMs = 30 * min, lastHeardMs = 1)))
        assertEquals(EventKind.RETURNED, e.onSighting(ble(40 * min), "x").single().kind)
        // Still around: no new event.
        assertTrue(e.onSighting(ble(41 * min), "x").isEmpty())
        assertTrue(e.onSighting(ble(60 * min), "x").isEmpty())
        // Away 31 min, back.
        val ev = e.onSighting(ble(91 * min), "x").single()
        assertEquals(EventKind.RETURNED, ev.kind)
        assertEquals(31 * min, ev.absentMs)
    }

    @Test fun neverHeardFiresFirstSeenOnce() {
        val e = Watchlist.Engine(listOf(Rule(1, "n", Kind.NAME, "flipper")))
        assertEquals(EventKind.FIRST_SEEN, e.onSighting(ble(1000, ad = named("Flipper Zero")), "x").single().kind)
        assertTrue(e.onSighting(ble(2000, ad = named("Flipper Zero")), "x").isEmpty())
    }

    @Test fun weakSignalDoesNotCount() {
        val e = Watchlist.Engine(listOf(Rule(1, "near", Kind.ADDRESS, addr.toString(), minRssi = -70, lastHeardMs = 1)))
        assertTrue(e.onSighting(ble(40 * min, rssi = -85), "x").isEmpty())
        assertEquals(1, e.onSighting(ble(41 * min, rssi = -65), "x").size)
    }

    @Test fun lateFrameNeverMovesTimeBack() {
        val e = Watchlist.Engine(listOf(Rule(1, "a", Kind.ADDRESS, addr.toString(), lastHeardMs = 100 * min)))
        assertTrue(e.onSighting(ble(50 * min), "x").isEmpty())
        assertEquals(100 * min, e.lastHeard()[1L])
    }

    @Test fun namePatterns() {
        assertTrue(Watchlist.nameMatches("pixel", "Stefano's Pixel 9"))
        assertTrue(Watchlist.nameMatches("cafe", "Café Wi-Fi"))
        assertTrue(Watchlist.nameMatches("RV-*", "rv-probe-feffaa"))
        assertFalse(Watchlist.nameMatches("RV-*", "my RV-probe"))
        assertFalse(Watchlist.nameMatches("", "anything"))
    }

    @Test fun nameRuleMatchesNetworkAskedFor() {
        val e = Watchlist.Engine(listOf(Rule(1, "home", Kind.NAME, "CasaRossi", lastHeardMs = 1)))
        val ev = e.onSighting(probe(40 * min, "CasaRossi_5G"), "x").single()
        assertEquals("CasaRossi_5G", ev.what)
    }

    @Test fun entityRule() {
        val e = Watchlist.Engine(listOf(Rule(1, "e", Kind.ENTITY, "ble:aa:bb:cc:11:22:33", lastHeardMs = 1)))
        assertTrue(e.onSighting(ble(40 * min), "ble:other").isEmpty())
        assertEquals(1, e.onSighting(ble(41 * min), "ble:aa:bb:cc:11:22:33").size)
    }

    @Test fun signatureRule() {
        val ad = named("Galaxy Watch6")
        val sig = dev.retrovision.core.identity.BleSignature.of(ad)!!
        val e = Watchlist.Engine(listOf(Rule(1, "w", Kind.SIGNATURE, sig, lastHeardMs = 1)))
        // Another address, same advert shape: still matches (rotation).
        assertEquals(1, e.onSighting(ble(40 * min, a = MacAddress.parse("5a:00:00:00:00:09"), ad = ad), "x").size)
        assertTrue(e.onSighting(ble(80 * min, ad = named("Other")), "x").isEmpty())
    }

    @Test fun goneFiresOnceAfterPresence() {
        val e = Watchlist.Engine(listOf(Rule(1, "bag", Kind.ADDRESS, addr.toString(), onReturn = false, onAbsence = true, absenceMs = 5 * min, lastHeardMs = 1)))
        // Not heard since start: no absence alert (it may simply not be with you today).
        assertTrue(e.tick(100 * min).isEmpty())
        e.onSighting(ble(100 * min), "x")
        assertTrue(e.tick(104 * min).isEmpty())
        assertEquals(EventKind.GONE, e.tick(106 * min).single().kind)
        assertTrue(e.tick(120 * min).isEmpty())
        // Back, then gone again: fires again.
        e.onSighting(ble(121 * min), "x")
        assertEquals(1, e.tick(127 * min).size)
    }

    @Test fun updateKeepsState() {
        val r = Rule(1, "a", Kind.ADDRESS, addr.toString(), lastHeardMs = 1)
        val e = Watchlist.Engine(listOf(r))
        e.onSighting(ble(40 * min), "x")
        e.update(listOf(r, Rule(2, "b", Kind.NAME, "x")))
        // Heard a minute ago: not a return.
        assertTrue(e.onSighting(ble(41 * min), "x").isEmpty())
    }

    @Test fun disabledRulesIgnored() {
        val e = Watchlist.Engine(listOf(Rule(1, "a", Kind.ADDRESS, addr.toString(), enabled = false)))
        assertTrue(e.isEmpty)
        assertTrue(e.onSighting(ble(1), "x").isEmpty())
    }
}
