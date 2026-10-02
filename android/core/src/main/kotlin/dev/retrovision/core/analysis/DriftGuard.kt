// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix

/**
 * Rejects GPS fixes that "move" while the phone is physically still.
 *
 * The accuracy filter only catches fixes the receiver admits are bad. Indoors the receiver often
 * reports ±15–30 m while the position wanders 100 m: that silent drift is what fakes places,
 * travel and "moved with you". Two independent witnesses tell drift from real movement:
 *  - the accelerometer: no motion energy = the phone is not being carried or driven;
 *  - the GPS Doppler speed: computed from carrier frequency shifts, not from positions, so it
 *    stays near 0 during position drift and is reliable in a smooth car where the accelerometer
 *    may look calm.
 * A fix is rejected only when BOTH say "still" and it lies away from where stillness began.
 */
class DriftGuard(
    /** Distance from the anchor beyond which a fix taken while still is considered drift. */
    private val maxStillDriftM: Double = 30.0,
    /** Doppler speed above which we trust the fix regardless of the accelerometer (m/s). Slow walking is ~1 m/s. */
    private val movingSpeedMps: Float = 0.8f,
    /** Rejected fixes moving steadily away from the anchor this many times in a row = real movement. */
    private val escapeAfter: Int = 3,
    private val escapeStepM: Double = 10.0,
) {
    private var anchor: GeoFix? = null
    private var streak = 0
    private var lastRejectedD = 0.0

    var rejected: Long = 0
        private set

    /**
     * @param still the accelerometer reported no motion for the whole recent window.
     * @return true to keep the fix.
     */
    fun accept(fix: GeoFix, still: Boolean): Boolean {
        val speed = fix.speedMps
        val moving = !still || (speed != null && speed >= movingSpeedMps)
        if (moving) {
            anchor = null
            streak = 0
            return true
        }
        val a = anchor
        if (a == null) {
            anchor = fix
            return true
        }
        // Refine the anchor with better fixes taken in the same spot.
        val d = Geo.distanceM(a, fix)
        val tol = maxOf(maxStillDriftM, a.accuracyM.toDouble())
        if (d <= tol) {
            if (fix.accuracyM > 0 && (a.accuracyM == 0f || fix.accuracyM < a.accuracyM)) anchor = fix
            streak = 0
            return true
        }
        // Drift jumps around; real movement the sensors missed keeps getting further away.
        streak = if (streak == 0 || d >= lastRejectedD + escapeStepM) streak + 1 else 1
        lastRejectedD = d
        if (streak >= escapeAfter) {
            anchor = fix
            streak = 0
            return true
        }
        rejected++
        return false
    }
}
