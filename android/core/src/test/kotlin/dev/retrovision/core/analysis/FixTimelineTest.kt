// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FixTimelineTest {
    private val min = 60_000L
    private val degPerM = 1.0 / 111_000.0

    private fun fix(t: Long, northM: Double = 0.0) = GeoFix(t, 44.494 + northM * degPerM, 11.355, 5f)

    @Test fun withoutBridgingAGapHasNoPlace() {
        val tl = FixTimeline(listOf(fix(0), fix(15 * min)), maxGapMs = min)
        assertNull(tl.nearest(7 * min))
    }

    @Test fun standingStillBridgesTheGap() {
        // Field case: indoors, 15 min without a usable fix, same spot before and after.
        val tl = FixTimeline(listOf(fix(0), fix(15 * min, northM = 30.0)), maxGapMs = min, stationaryBridgeMs = 20 * min)
        val f = assertNotNullAndGet(tl.nearest(7 * min))
        assertEquals(0L, f.timeMs) // the nearer side
        assertEquals(15 * min, tl.nearest(9 * min)!!.timeMs)
    }

    @Test fun movingDuringTheGapIsNotBridged() {
        val tl = FixTimeline(listOf(fix(0), fix(10 * min, northM = 800.0)), maxGapMs = min, stationaryBridgeMs = 20 * min)
        assertNull(tl.nearest(5 * min))
    }

    @Test fun tooLongAGapIsNotBridged() {
        val tl = FixTimeline(listOf(fix(0), fix(45 * min)), maxGapMs = min, stationaryBridgeMs = 20 * min)
        assertNull(tl.nearest(20 * min))
    }

    @Test fun afterTheLastFixNothingIsGuessed() {
        val tl = FixTimeline(listOf(fix(0), fix(min)), maxGapMs = min, stationaryBridgeMs = 20 * min)
        assertNull(tl.nearest(10 * min))
    }

    private fun assertNotNullAndGet(f: GeoFix?): GeoFix { assertNotNull(f); return f!! }
}
