// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estimates the *indicative* geographic direction toward a transmitter from how its RSSI
 * changes as the phone moves. With a single omnidirectional antenna there is no true
 * angle-of-arrival; the only usable cue is that moving toward an emitter raises its RSSI.
 *
 * Method: least-squares fit of a plane  rssi ≈ a + b·east + c·north  over recent samples.
 * The gradient (b, c) points toward increasing signal, i.e. toward the device. Confidence is
 * the fit's R² (how plane-like the signal field is), gated by how far the phone actually moved.
 *
 * A device that moves *with* the phone keeps a roughly constant RSSI regardless of position,
 * so the gradient collapses and confidence is ~0 — correctly refusing to invent a direction.
 */
object BearingEstimator {
    /** East/north are metres in a local tangent plane; rssi in dBm (negative). */
    class Sample(val east: Double, val north: Double, val rssi: Int, val timeMs: Long)

    /** [bearingDeg] is geographic (0 = north, 90 = east). [confidence] is 0..1 (R²). */
    class Estimate(val bearingDeg: Double, val confidence: Double)

    fun estimate(samples: List<Sample>, minSpreadM: Double = 15.0, minSamples: Int = 8): Estimate? {
        if (samples.size < minSamples) return null
        val n = samples.size.toDouble()

        // Need real spatial spread, otherwise the fit is meaningless.
        val exs = samples.map { it.east }
        val nys = samples.map { it.north }
        val spread = hypot(exs.max() - exs.min(), nys.max() - nys.min())
        if (spread < minSpreadM) return null

        // Centre the data for numerical stability.
        val mx = exs.average()
        val my = nys.average()
        val mr = samples.map { it.rssi.toDouble() }.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var sxr = 0.0; var syr = 0.0; var srr = 0.0
        for (s in samples) {
            val x = s.east - mx; val y = s.north - my; val r = s.rssi - mr
            sxx += x * x; sxy += x * y; syy += y * y
            sxr += x * r; syr += y * r; srr += r * r
        }
        if (srr < 1e-9) return Estimate(0.0, 0.0) // RSSI did not change: no direction

        val det = sxx * syy - sxy * sxy
        val trace = sxx + syy
        var b: Double
        var c: Double
        if (det < 1e-6 * (trace * trace + 1e-9)) {
            // Collinear walk (a straight line): only the component of direction along the path
            // is observable. Project onto the dominant movement axis and report ahead/behind.
            // Largest-eigenvalue eigenvector of [sxx sxy; sxy syy].
            val lambda = (trace + sqrt(max(0.0, trace * trace - 4 * det))) / 2.0
            var ux = sxy
            var uy = lambda - sxx
            if (hypot(ux, uy) < 1e-9) { ux = lambda - syy; uy = sxy }
            val un = hypot(ux, uy)
            if (un < 1e-9) return null
            ux /= un; uy /= un
            // Slope of rssi along u, then the gradient is slope·u.
            var spp = 0.0; var spr = 0.0
            for (s in samples) {
                val proj = (s.east - mx) * ux + (s.north - my) * uy
                spp += proj * proj
                spr += proj * (s.rssi - mr)
            }
            if (spp < 1e-9) return null
            val slope = spr / spp
            b = slope * ux; c = slope * uy
        } else {
            // Solve [sxx sxy; sxy syy] · [b; c] = [sxr; syr]
            b = (syy * sxr - sxy * syr) / det
            c = (sxx * syr - sxy * sxr) / det
        }
        if (hypot(b, c) < 1e-6) return Estimate(0.0, 0.0)

        val ssExplained = b * sxr + c * syr
        val r2 = (ssExplained / srr).coerceIn(0.0, 1.0)

        // Gradient points toward increasing RSSI = toward the device. Compass: atan2(east, north).
        var deg = Math.toDegrees(atan2(b, c))
        if (deg < 0) deg += 360.0
        return Estimate(deg, r2)
    }

    /** Smooth scalar RSSI for the live display (EWMA). */
    fun ewma(prev: Double?, value: Int, alpha: Double = 0.4): Double =
        if (prev == null) value.toDouble() else prev + alpha * (value - prev)
}
