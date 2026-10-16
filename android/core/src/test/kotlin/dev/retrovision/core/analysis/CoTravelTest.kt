// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoTravelTest {
    private val min = 60_000L
    private fun v(place: Int, from: Long, to: Long) = DeviceVisit(place, null, null, from * min, to * min, 5, -60)

    /** Places 1, 2, 3 at 0-10, 30-40, 60-70 min. */
    private val route = listOf(v(1, 0, 10), v(2, 30, 40), v(3, 60, 70))

    @Test fun phoneAndWatchTogether() {
        val g = CoTravel.find(listOf(CoTravel.Input("phone", route), CoTravel.Input("watch", route.map { v(it.placeId, it.startMs / min + 1, it.endMs / min) })))
        assertEquals(1, g.size)
        assertEquals(listOf("phone", "watch"), g[0].members)
        assertEquals(3, g[0].sharedPlaces)
    }

    @Test fun samePlacesDifferentTimesAreNotTogether() {
        // Same three places, each an hour later: a neighbour on the same route, not a companion.
        val later = route.map { v(it.placeId, it.startMs / min + 120, it.endMs / min + 120) }
        assertTrue(CoTravel.find(listOf(CoTravel.Input("a", route), CoTravel.Input("b", later))).isEmpty())
    }

    @Test fun oneSharedPlaceIsNotEnough() {
        val other = listOf(v(1, 0, 10), v(7, 30, 40), v(8, 60, 70))
        assertTrue(CoTravel.find(listOf(CoTravel.Input("a", route), CoTravel.Input("b", other))).isEmpty())
    }

    @Test fun mostlyApartIsNotTogether() {
        // b shares 2 of a's 3 places but has 6 places of its own: below half of its places.
        val b = listOf(v(1, 0, 10), v(2, 30, 40)) + (10..13).map { v(it, 100L + it * 20, 110L + it * 20) }
        assertTrue(CoTravel.find(listOf(CoTravel.Input("a", route), CoTravel.Input("b", b))).isEmpty())
    }

    @Test fun threeDevicesChainIntoOneGroup() {
        val g = CoTravel.find(listOf(CoTravel.Input("a", route), CoTravel.Input("b", route), CoTravel.Input("c", route), CoTravel.Input("x", listOf(v(9, 0, 5)))))
        assertEquals(1, g.size)
        assertEquals(listOf("a", "b", "c"), g[0].members)
    }

    @Test fun visitsWithoutPlaceIgnored() {
        val noPlace = listOf(v(-1, 0, 10), v(-1, 30, 40), v(-1, 60, 70))
        assertTrue(CoTravel.find(listOf(CoTravel.Input("a", noPlace), CoTravel.Input("b", noPlace))).isEmpty())
    }
}
