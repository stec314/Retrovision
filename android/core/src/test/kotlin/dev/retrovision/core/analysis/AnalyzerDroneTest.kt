package dev.retrovision.core.analysis

import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.NotableKind
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerDroneTest {
    private val now = 10 * 3600_000L

    private fun ad(type: Int, vararg b: Int) = byteArrayOf((b.size + 1).toByte(), type.toByte()) + b.map { it.toByte() }.toByteArray()

    private fun ble(id: String, t: Long, data: ByteArray) =
        EntitySighting(id, Sighting(t, Radio.BLE, MacAddress(0x02_00_00_00_00_01L), -70, ble = BleDetail(BleAddressKind.PUBLIC, 4, data)))

    @Test fun remoteIdDroneAtTwoPlacesIsADroneAndScoresHigher() {
        val basicId = listOf(0x02, 0x12) + "SER-1".padEnd(20, '\u0000').map { it.code } + List(3) { 0 }
        val rid = ad(0x16, *(listOf(0xFA, 0xFF, 0x0D, 0x01) + basicId).toIntArray())
        val plain = ad(0x09, *"Thing".map { it.code }.toIntArray())
        val t0 = now - 30 * 60_000L
        // you at two places 1 km apart
        val fixes = (0..30).map { GeoFix(t0 + it * 60_000L, 44.50 + if (it > 15) 0.009 else 0.0, 11.30, 5f) }
        val times = listOf(t0 + 60_000L, t0 + 5 * 60_000L, t0 + 20 * 60_000L, t0 + 25 * 60_000L)
        val r = Analyzer().analyze(
            now,
            times.map { ble("drone", it, rid) } + times.map { ble("thing", it, plain) },
            fixes,
        )
        val drone = r.entities.first { it.entityId == "drone" }
        val thing = r.entities.first { it.entityId == "thing" }
        assertEquals(DeviceCategory.DRONE, drone.category)
        assertEquals("SER-1", drone.droneId)
        assertTrue(drone.reasons.any { it is Reason.Drone && it.remoteId })
        assertEquals(0.15, drone.score - thing.score, 1e-9)
    }

    @Test fun notableReasonIsInformational() {
        val flipper = ad(0x09, *"Flipper Xyz".map { it.code }.toIntArray())
        val r = Analyzer().analyze(now, listOf(ble("f", now - 60_000, flipper)), emptyList())
        val e = r.entities.single()
        assertTrue(e.reasons.any { it is Reason.Notable && it.kind == NotableKind.HACKING })
        assertTrue(e.score <= 0.3)
    }
}
