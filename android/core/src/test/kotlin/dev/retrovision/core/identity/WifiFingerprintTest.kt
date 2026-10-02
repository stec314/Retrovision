package dev.retrovision.core.identity

import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiFingerprintTest {
    private fun ie(id: Int, vararg body: Int) = byteArrayOf(id.toByte(), body.size.toByte()) + body.map { it.toByte() }.toByteArray()
    private fun ext(ext: Int, vararg body: Int) = ie(255, ext, *body)

    private val rates = ie(1, 0x02, 0x04, 0x0b, 0x16, 0x0c, 0x12, 0x18, 0x24)
    private val ht = ie(45, 0xef, 0x01, 0x1b, 0xff, 0xff, 0, 0, 0)
    private val heCaps = ext(35, 0x01, 0x78, 0x10, 0x1a, 0x00, 0x00)
    private val ehtCaps = ext(108, 0x00, 0x00, 0x22, 0x22)

    /** A Wi-Fi 6/7-ish probe request body; [ssid], [opClass], [hessid] etc. vary per request. */
    private fun probe(
        ssid: String = "",
        he: ByteArray = heCaps,
        eht: ByteArray = ehtCaps,
        opClass: Int = 81,
        hessid: Boolean = false,
        extReq: Int = 0x7f,
        mldId: Int = 0,
    ): ByteArray =
        ie(0, *ssid.toByteArray().map { it.toInt() }.toIntArray()) + rates + ie(3, 6) + ie(59, opClass, 81, 115, 128) +
            ht + (if (hessid) ie(107, 0x0f, 1, 2, 3, 4, 5, 6) else ie(107, 0x0f)) + ext(10, extReq) +
            he + eht + ext(107, 0x10, 0x00, mldId) + ie(221, 0x00, 0x17, 0xf2, 0x0a, 0x00, 0x01)

    private fun fp(ies: ByteArray, truncated: Boolean = false) = WifiFingerprint.of(ies, truncated)

    @Test fun heAndEhtCapabilitiesAreHashed() {
        val base = fp(probe())
        assertNotNull(base)
        assertNotEquals(base, fp(probe(he = ext(35, 0x01, 0x78, 0x10, 0x1a, 0x00, 0x01))))
        assertNotEquals(base, fp(probe(eht = ext(108, 0x00, 0x00, 0x22, 0x23))))
        // Dropping HE/EHT altogether (an older Wi-Fi 5 phone) is a different device class.
        assertNotEquals(base, fp(probe(he = ByteArray(0), eht = ByteArray(0))))
    }

    @Test fun requestSpecificFieldsAreIgnored() {
        val base = fp(probe())
        assertEquals(base, fp(probe(ssid = "CasaRossi")))
        assertEquals(base, fp(probe(opClass = 128))) // current operating class follows the band
        assertEquals(base, fp(probe(hessid = true))) // HESSID names the target network
        assertEquals(base, fp(probe(extReq = 0x01))) // Extended Request
        assertEquals(base, fp(probe(mldId = 7))) // probe-request multi-link names the target AP MLD
    }

    @Test fun supportedOperatingClassesListStillCounts() {
        val a = probe()
        val b = a.copyOf()
        val i = findElement(a, 59)
        b[i + 2 + 2] = 116 // change one supported class, not the current one
        assertNotEquals(fp(a), fp(b))
    }

    @Test fun truncatedListGivesNoFingerprint() {
        assertNull(fp(probe(), truncated = true))
        assertNull(fp(ByteArray(0)))
    }

    @Test fun truncatedProbeIsNeverLinked() {
        val r = EntityResolver()
        fun s(t: Long, mac: Long, seq: Int, truncated: Boolean) = Sighting(
            t, Radio.WIFI, MacAddress(mac), -50,
            wifi = WifiDetail(WifiKind.PROBE_REQ, 6, ByteArray(0), null, seq, probe(), truncated),
        )
        val first = r.resolve(s(0, 0x02_0000_0000_01L, 100, truncated = false))
        val linked = r.resolve(s(10_000, 0x02_0000_0000_02L, 101, truncated = false))
        assertEquals(first.entityId, linked.entityId)
        val cut = r.resolve(s(20_000, 0x02_0000_0000_03L, 102, truncated = true))
        assertFalse(cut.linkedToExisting)
        assertNotEquals(first.entityId, cut.entityId)
    }

    private fun findElement(ies: ByteArray, id: Int): Int {
        var pos = 0
        while (pos + 2 <= ies.size) {
            if ((ies[pos].toInt() and 0xFF) == id) return pos
            pos += 2 + (ies[pos + 1].toInt() and 0xFF)
        }
        error("element $id not found")
    }
}
