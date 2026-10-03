// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.analysis.Verdict.Gap
import dev.retrovision.core.analysis.Verdict.State
import dev.retrovision.core.model.MacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    private fun inputs(
        running: Boolean = true, probe: Boolean = true, ble: Boolean = false, gpsAge: Long? = 2_000, acc: Float? = 5f,
        covered: Long = 30 * 60_000L, analysisAge: Long? = 20_000, truncated: Boolean = false,
        entities: List<Level> = emptyList(), threats: List<Level> = emptyList(),
    ) = Verdict.Inputs(running, probe, ble, gpsAge, acc, 50f, covered, analysisAge, truncated, entities, threats)

    @Test fun fullCoverageAndNothingFoundIsClear() = assertEquals(State.CLEAR, Verdict.of(inputs()).state)

    /** The dangerous case: never "nothing found" when the app could not have seen it. */
    @Test fun probeUnpluggedAndNoPhoneBluetoothCannotTell() {
        val r = Verdict.of(inputs(probe = false, ble = false))
        assertEquals(State.CANT_TELL, r.state)
        assertTrue(Gap.NO_RECEIVER in r.gaps)
    }

    @Test fun phoneOnlyIsPartial() {
        val r = Verdict.of(inputs(probe = false, ble = true))
        assertEquals(State.CLEAR_PARTIAL, r.state)
        assertTrue(Gap.NO_PROBE in r.gaps)
    }

    @Test fun noGpsIsPartialAndJustStartedCannotTell() {
        assertEquals(State.CLEAR_PARTIAL, Verdict.of(inputs(gpsAge = null)).state)
        assertEquals(State.CANT_TELL, Verdict.of(inputs(covered = 5 * 60_000L)).state)
        assertEquals(State.CANT_TELL, Verdict.of(inputs(analysisAge = null)).state)
    }

    @Test fun findingsShowEvenWithGaps() {
        val r = Verdict.of(inputs(probe = false, ble = true, entities = listOf(Level.WORTH_A_LOOK)))
        assertEquals(State.WORTH_A_LOOK, r.state)
        assertEquals(State.STRONG, Verdict.of(inputs(threats = listOf(Level.STRONG))).state)
    }

    @Test fun stoppedIsStopped() = assertEquals(State.STOPPED, Verdict.of(inputs(running = false)).state)

    private fun report(alert: Boolean, score: Double, reasons: List<Reason>) = EntityReport(
        entityId = "x", kind = EntityKind.BLE_DEVICE, score = score, alert = alert, reasons = reasons, placeIds = emptySet(),
        windows = emptySet(), firstSeenMs = 0, lastSeenMs = 0, sightings = 1, activeMinutes = 1, maxRssi = -60,
        addresses = setOf(MacAddress(1L)), ssids = emptySet(), tracker = null, bleCompanyId = null, mobileAp = null, track = emptyList(),
    )

    @Test fun strongNeedsBehaviourNotJustPresence() {
        assertEquals(Level.WORTH_A_LOOK, Levels.of(report(true, 1.0, listOf(Reason.SeenAtPlaces(5), Reason.SeenFor(7_200_000)))))
        assertEquals(Level.STRONG, Levels.of(report(true, 0.8, listOf(Reason.MovedWithYou(900.0, 3.0)))))
        assertEquals(Level.WORTH_A_LOOK, Levels.of(report(true, 0.8, listOf(Reason.StayedThroughTurns(2, 10)))))
        assertEquals(Level.SOME, Levels.of(report(false, 0.6, emptyList())))
        assertEquals(Level.LOW, Levels.of(report(false, 0.2, emptyList())))
    }
}
