// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.wire

import dev.retrovision.core.wire.ReconnectPolicy.Failure
import dev.retrovision.core.wire.ReconnectPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    private fun policy() = ReconnectPolicy(directAttempts = 3, baseDelayMs = 500, maxDelayMs = 8_000, windowsBeforeRescan = 2)

    @Test fun unknownAddressScansFirstAtOnce() {
        val p = policy()
        assertEquals(ReconnectPolicy.Step(Mode.SCAN, 0), p.next())
        p.onFailure(Mode.SCAN, Failure.NOT_FOUND)
        assertEquals(Mode.SCAN, p.next().mode)
        assertEquals(500, p.next().delayMs)
    }

    @Test fun directWithBackoffThenBackground() {
        val p = policy()
        p.setAddressKnown(true)
        assertEquals(ReconnectPolicy.Step(Mode.DIRECT, 0), p.next())
        p.onFailure(Mode.DIRECT, Failure.TRANSIENT)
        assertEquals(ReconnectPolicy.Step(Mode.DIRECT, 500), p.next())
        p.onFailure(Mode.DIRECT, Failure.TRANSIENT)
        assertEquals(ReconnectPolicy.Step(Mode.DIRECT, 1_000), p.next())
        p.onFailure(Mode.DIRECT, Failure.NOT_FOUND)
        assertEquals(ReconnectPolicy.Step(Mode.BACKGROUND, 0), p.next())
    }

    @Test fun dropReconnectsImmediately() {
        val p = policy()
        p.setAddressKnown(true)
        repeat(5) { p.onFailure(Mode.DIRECT, Failure.TRANSIENT) }
        p.onConnected()
        p.onDropped()
        assertEquals(ReconnectPolicy.Step(Mode.DIRECT, 0), p.next())
    }

    @Test fun longSilenceRescans() {
        val p = policy()
        p.setAddressKnown(true)
        repeat(3) { p.onFailure(Mode.DIRECT, Failure.NOT_FOUND) }
        assertEquals(Mode.BACKGROUND, p.next().mode)
        p.onFailure(Mode.BACKGROUND, Failure.NOT_FOUND)
        assertEquals(Mode.BACKGROUND, p.next().mode)
        p.onFailure(Mode.BACKGROUND, Failure.NOT_FOUND)
        assertEquals(Mode.SCAN, p.next().mode)
        // A scan that finds nothing goes back to waiting in the background.
        p.onFailure(Mode.SCAN, Failure.NOT_FOUND)
        assertEquals(Mode.BACKGROUND, p.next().mode)
    }

    @Test fun twoAuthFailuresRepairTheBondOnce() {
        val p = policy()
        p.setAddressKnown(true)
        p.onFailure(Mode.DIRECT, Failure.AUTH)
        assertFalse(p.next().repairBond)
        p.onFailure(Mode.DIRECT, Failure.AUTH)
        assertTrue(p.next().repairBond)
        assertFalse(p.next().repairBond)
    }

    @Test fun authFailuresMustBeConsecutive() {
        val p = policy()
        p.setAddressKnown(true)
        p.onFailure(Mode.DIRECT, Failure.AUTH)
        p.onFailure(Mode.DIRECT, Failure.TRANSIENT)
        p.onFailure(Mode.DIRECT, Failure.AUTH)
        assertFalse(p.next().repairBond)
    }

    @Test fun missingServiceSlowsDown() {
        val p = policy()
        p.setAddressKnown(true)
        p.onFailure(Mode.DIRECT, Failure.NO_SERVICE)
        p.onFailure(Mode.DIRECT, Failure.NO_SERVICE)
        assertEquals(ReconnectPolicy.Step(Mode.DIRECT, 30_000), p.next())
    }

    @Test fun backoffIsCapped() {
        val p = ReconnectPolicy(directAttempts = 50, baseDelayMs = 500, maxDelayMs = 8_000)
        p.setAddressKnown(true)
        repeat(30) { p.onFailure(Mode.DIRECT, Failure.TRANSIENT) }
        assertEquals(8_000, p.next().delayMs)
    }
}
