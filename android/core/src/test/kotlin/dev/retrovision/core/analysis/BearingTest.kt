package dev.retrovision.core.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class BearingTest {
    /** Samples along a short walk; RSSI falls with distance to a fixed emitter. */
    private fun walkToward(ex: Double, ny: Double, path: List<Pair<Double, Double>>): List<BearingEstimator.Sample> =
        path.mapIndexed { i, (x, y) ->
            val d = hypot(ex - x, ny - y)
            BearingEstimator.Sample(x, y, (-30 - 0.6 * d).toInt(), i * 1000L)
        }

    private val walkNorth = (0..12).map { 0.0 to it * 3.0 }   // 0..36 m north
    private val walkEast = (0..12).map { it * 3.0 to 0.0 }

    private fun angErr(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0; if (d > 180) d = 360 - d; return d
    }

    @Test fun deviceToNorth() {
        val e = BearingEstimator.estimate(walkToward(0.0, 400.0, walkNorth))!!
        assertTrue("bearing ${e.bearingDeg}", angErr(e.bearingDeg, 0.0) < 15)
        assertTrue("conf ${e.confidence}", e.confidence > 0.9)
    }

    @Test fun deviceToEast() {
        val e = BearingEstimator.estimate(walkToward(400.0, 0.0, walkEast))!!
        assertTrue("bearing ${e.bearingDeg}", angErr(e.bearingDeg, 90.0) < 15)
    }

    @Test fun deviceToSouthWest() {
        val path = (0..12).map { -it * 2.0 to -it * 2.0 } // walking SW
        val e = BearingEstimator.estimate(walkToward(-400.0, -400.0, path))!!
        assertTrue("bearing ${e.bearingDeg}", angErr(e.bearingDeg, 225.0) < 20)
    }

    @Test fun coMovingHasNoBearing() {
        // RSSI constant while the phone moves a lot (device travels with you).
        val s = walkNorth.mapIndexed { i, (x, y) -> BearingEstimator.Sample(x, y, -55, i * 1000L) }
        val e = BearingEstimator.estimate(s)
        // Either refused, or returned with ~zero confidence.
        assertTrue(e == null || e.confidence < 0.1)
    }

    @Test fun notEnoughMovement() {
        val s = (0..12).map { BearingEstimator.Sample(it * 0.3, 0.0, -40 - it, it * 1000L) } // < 15 m spread
        assertNull(BearingEstimator.estimate(s))
    }

    @Test fun tooFewSamples() {
        assertNull(BearingEstimator.estimate(walkToward(0.0, 400.0, walkNorth).take(4)))
    }

    @Test fun ewmaSmooths() {
        var v: Double? = null
        v = BearingEstimator.ewma(v, -60); assertTrue(v == -60.0)
        v = BearingEstimator.ewma(v, -40); assertTrue(v!! in -60.0..-40.0)
    }
}
