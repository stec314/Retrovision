// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.wire

/**
 * Decides how and when a wireless probe link tries again. Pure logic, so it is tested without a
 * phone; the Android side only carries out the [Step] it is given.
 *
 * Strategy (the usual one for a bonded BLE peripheral that comes and goes):
 *  1. Right after a drop, reconnect at once: the probe advertises fast for 30 s after losing us.
 *  2. A few direct attempts with exponential backoff (0.5 s, 1 s, 2 s…), for transient errors
 *     (GATT 133, a missed connection event during setup).
 *  3. Then a background connection (Android "autoConnect"): the controller waits for the probe's
 *     advertising at low duty, with no timeout and no scan quota. It reconnects within seconds of
 *     the probe coming back in range, at almost no battery cost.
 *  4. Without an address (first time, or a probe that does not answer at the known one for a long
 *     time) the probe is found by scanning for its name.
 *  5. Two authentication failures in a row (stale bond on one side) ask for the bond to be
 *     repaired: forget it on the phone and pair again.
 */
class ReconnectPolicy(
    private val directAttempts: Int = 3,
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 8_000,
    /** How long one background wait lasts before the loop re-checks (cable, forget, settings). */
    val backgroundWindowMs: Long = 60_000,
    /** Background windows without success before the address is checked again by scanning. */
    private val windowsBeforeRescan: Int = 5,
    private val jitter: (Long) -> Long = { it },
) {
    enum class Mode { SCAN, DIRECT, BACKGROUND }

    /** What to do next: wait [delayMs], then try [mode]; [repairBond] = forget the bond first. */
    data class Step(val mode: Mode, val delayMs: Long, val repairBond: Boolean = false)

    enum class Failure {
        /** Connection or setup failed for a reason that usually passes (GATT 133, timeouts). */
        TRANSIENT,
        /** The probe did not answer (direct connect timed out, background window ended, scan empty). */
        NOT_FOUND,
        /** Encryption or bonding refused: usually a stale bond on one side. */
        AUTH,
        /** Connected, but the Retrovision service is missing even after a cache refresh. */
        NO_SERVICE,
    }

    private var knownAddress = false
    private var failures = 0
    private var authFailures = 0
    private var backgroundWindows = 0
    private var serviceFailures = 0
    private var repairPending = false

    /** Number of consecutive failed attempts (for the UI). */
    val consecutiveFailures get() = failures

    fun setAddressKnown(known: Boolean) {
        knownAddress = known
        if (!known) backgroundWindows = 0
    }

    fun next(): Step {
        val repair = repairPending
        repairPending = false
        if (!knownAddress) return Step(Mode.SCAN, if (failures == 0) 0 else backoff(failures), repair)
        if (serviceFailures >= 2) return Step(Mode.DIRECT, 30_000, repair)
        if (failures < directAttempts) return Step(Mode.DIRECT, if (failures == 0) 0 else backoff(failures), repair)
        if (backgroundWindows >= windowsBeforeRescan) {
            // Long silence at the known address: look for the name again (the probe may have
            // been re-flashed onto another board). The address is kept until a scan finds it.
            backgroundWindows = 0
            return Step(Mode.SCAN, 0, repair)
        }
        return Step(Mode.BACKGROUND, 0, repair)
    }

    /** A connection reached the streaming-ready state. */
    fun onConnected() {
        failures = 0
        authFailures = 0
        backgroundWindows = 0
        serviceFailures = 0
    }

    /** An established link dropped: the next attempt is immediate. */
    fun onDropped() {
        failures = 0
        backgroundWindows = 0
    }

    fun onFailure(mode: Mode, kind: Failure) {
        failures++
        when (kind) {
            Failure.AUTH -> {
                authFailures++
                if (authFailures >= 2) { repairPending = true; authFailures = 0 }
            }
            Failure.NO_SERVICE -> serviceFailures++
            else -> authFailures = 0
        }
        if (mode == Mode.BACKGROUND) backgroundWindows++
    }

    private fun backoff(n: Int): Long {
        val shift = (n - 1).coerceIn(0, 20)
        return jitter(minOf(maxDelayMs, baseDelayMs shl shift))
    }
}
