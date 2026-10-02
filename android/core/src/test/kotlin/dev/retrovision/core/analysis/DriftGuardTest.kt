package dev.retrovision.core.analysis

import dev.retrovision.core.model.GeoFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftGuardTest {
    private fun fix(t: Long, dNorthM: Double, acc: Float = 20f, speed: Float? = 0.2f) =
        GeoFix(t, 44.5 + dNorthM / 111_320.0, 11.3, acc, speed)

    @Test fun stillPhoneRejectsSilentDrift() {
        val g = DriftGuard()
        assertTrue(g.accept(fix(0, 0.0), still = true))
        assertTrue(g.accept(fix(5_000, 12.0), still = true)) // within tolerance
        assertFalse(g.accept(fix(10_000, 120.0), still = true)) // jumped 120 m while sitting
        assertEquals(1L, g.rejected)
    }

    @Test fun walkingIsNeverRejected() {
        val g = DriftGuard()
        assertTrue(g.accept(fix(0, 0.0), still = true))
        assertTrue(g.accept(fix(60_000, 80.0, speed = 1.3f), still = false))
        assertTrue(g.accept(fix(120_000, 160.0, speed = 1.3f), still = false))
    }

    @Test fun smoothCarTrustedByDopplerSpeed() {
        val g = DriftGuard()
        assertTrue(g.accept(fix(0, 0.0), still = true))
        // accelerometer calm on a smooth road, but Doppler says 20 m/s
        assertTrue(g.accept(fix(5_000, 100.0, speed = 20f), still = true))
    }

    @Test fun anchorResetsAfterMoving() {
        val g = DriftGuard()
        g.accept(fix(0, 0.0), still = true)
        g.accept(fix(5_000, 500.0, speed = 2f), still = false) // moved
        assertTrue(g.accept(fix(10_000, 505.0), still = true)) // new anchor here
        assertFalse(g.accept(fix(15_000, 650.0), still = true))
    }
}
