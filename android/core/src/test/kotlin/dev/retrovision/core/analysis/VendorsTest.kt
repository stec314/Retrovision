package dev.retrovision.core.analysis

import dev.retrovision.core.identity.CompanyDb
import dev.retrovision.core.identity.OuiDb
import dev.retrovision.core.model.MacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorsTest {
    private val db = OuiDb.parse(
        sequenceOf(
            "286FB9\tNokia Shanghai Bell",
            "70B3D5A\tSmall Maker Ltd",          // MA-M (28 bit)
            "70B3D5A1B\tTiny Gadget Inc",        // MA-S (36 bit)
            "bad line without tab",
        ),
    )

    @Test fun longestPrefixWins() {
        assertEquals("Nokia Shanghai Bell", db.lookup(MacAddress.parse("28:6f:b9:01:02:03")))
        assertEquals("Small Maker Ltd", db.lookup(MacAddress.parse("70:b3:d5:a0:00:01")))
        assertEquals("Tiny Gadget Inc", db.lookup(MacAddress.parse("70:b3:d5:a1:b0:01")))
    }

    @Test fun randomisedAddressesHaveNoVendor() {
        // second-least-significant bit of the first octet set: locally administered
        assertNull(db.lookup(MacAddress.parse("2a:6f:b9:01:02:03")))
    }

    @Test fun unknownPrefixIsNull() {
        assertNull(db.lookup(MacAddress.parse("00:00:00:01:02:03")))
        assertEquals(3, db.size)
    }

    @Test fun companyIds() {
        val c = CompanyDb.parse(sequenceOf("76\tApple, Inc.", "x\tbad", "6\tMicrosoft"))
        assertEquals("Apple, Inc.", c.name(0x004C))
        assertEquals(2, c.size)
    }
}
