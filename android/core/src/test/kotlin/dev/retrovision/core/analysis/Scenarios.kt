package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting

/**
 * Synthetic field days for the analyzer: your own route (stops and legs, one fix every 10 s,
 * heading east at 45 N) and the devices heard along it. Shared by the scenario tests and by
 * anyone who wants to compare analyzer versions on the same inputs.
 */
class Route(private val lat: Double = 45.0, private val lon: Double = 11.0) {
    class Span(val startMs: Long, val endMs: Long) {
        val durationMs get() = endMs - startMs
        /** The part of the span from [fromMin] to [toMin] minutes after its start (negative: from its end). */
        fun part(fromMin: Double, toMin: Double): Span {
            fun at(m: Double) = if (m >= 0) startMs + (m * MIN).toLong() else endMs + (m * MIN).toLong()
            return Span(at(fromMin), at(toMin))
        }
    }

    val fixes = ArrayList<GeoFix>()
    var nowMs = 0L
        private set
    private var eastM = 0.0

    private fun fix() {
        fixes += GeoFix(nowMs, lat, lon + eastM * DEG_PER_M, 5f)
    }

    fun stay(minutes: Int): Span = advance(minutes, 0.0)

    fun move(minutes: Int, speedMps: Double): Span = advance(minutes, speedMps)

    private fun advance(minutes: Int, speedMps: Double): Span {
        val start = nowMs
        if (fixes.isEmpty()) fix()
        val steps = minutes * 6
        repeat(steps) {
            nowMs += STEP_MS
            eastM += speedMps * STEP_MS / 1000.0
            fix()
        }
        return Span(start, nowMs)
    }

    companion object {
        const val MIN = 60_000L
        const val STEP_MS = 10_000L
        const val DEG_PER_M = 1.0 / 78_800.0
    }
}

/** Builds the sightings of the scenario. Every entity gets its own stable public BLE address. */
class Air {
    val sightings = ArrayList<EntitySighting>()
    private val macs = HashMap<String, MacAddress>()

    fun heard(entity: String, span: Route.Span, everyMs: Long = 10_000L, rssi: (Int) -> Int = { -65 + (it % 3) - 1 }) {
        val mac = macs.getOrPut(entity) { MacAddress(0x0400_0000_0000L + macs.size) }
        var t = span.startMs
        var i = 0
        while (t <= span.endMs) {
            sightings += EntitySighting(
                entity,
                Sighting(t, Radio.BLE, mac, rssi(i), ble = BleDetail(BleAddressKind.PUBLIC, 1, byteArrayOf(2, 1, 6))),
            )
            t += everyMs
            i++
        }
    }
}

class Scenario(
    val name: String,
    /** The entity the scenario is about. */
    val subject: String,
    val shouldAlert: Boolean,
    val route: Route,
    val air: Air,
    val familiar: List<FamiliarPlace> = emptyList(),
) {
    fun run(analyzer: Analyzer = Analyzer()): EntityReport =
        analyzer.analyze(route.nowMs, air.sightings.sortedBy { it.sighting.timeMs }, route.fixes, familiar = familiar)
            .entities.single { it.entityId == subject }
}

object Scenarios {
    private const val WALK = 1.4
    private const val BUS = 8.0
    private const val CAR = 12.0

    /** Someone on foot who is near you at four separate stops, and out of range in between. */
    fun followerOnFoot(): Scenario {
        val r = Route()
        val air = Air()
        val stops = ArrayList<Route.Span>()
        stops += r.stay(10)
        repeat(3) {
            r.move(8, WALK)
            stops += r.stay(10)
        }
        for (s in stops) air.heard("follower", s.part(3.0, -3.0), everyMs = 30_000L)
        return Scenario("Follower on foot, 4 stops", "follower", true, r, air)
    }

    /** Same follower, but three of the four stops are places you are at all the time. */
    fun followerAtFamiliarPlaces(): Scenario {
        val base = followerOnFoot()
        val stops = VisitTimeline.build(base.route.fixes)
        val familiar = stops.take(3).mapIndexed { i, v -> FamiliarPlace(i.toLong(), v.lat, v.lon, 150.0, "x") }
        return Scenario("Follower, 3 of 4 stops familiar", "follower", false, base.route, base.air, familiar)
    }

    /**
     * Commute: home, wait at the bus stop next to another passenger, 20 minutes on a full bus,
     * walk off together at the other end, office. Thirty other phones ride the same bus.
     */
    fun busCommuter(): Scenario {
        val r = Route()
        val air = Air()
        r.stay(15)
        r.move(4, WALK)
        val busStop = r.stay(6)
        val bus = r.move(20, BUS)
        val office = r.stay(30)
        air.heard("passenger", busStop.part(1.0, 6.0))
        air.heard("passenger", bus)
        air.heard("passenger", office.part(0.0, 1.5))
        for (i in 0 until 30) air.heard("rider$i", bus.part(0.5 + i % 3, -(0.5 + i % 2)))
        return Scenario("Bus commuter (30 riders)", "passenger", false, r, air)
    }

    /** A car that drives behind you on three separate legs, waiting out of range while you stop. */
    fun carTail(crowd: Int = 0): Scenario {
        val r = Route()
        val air = Air()
        r.stay(10)
        repeat(3) { leg ->
            val drive = r.move(6, CAR)
            air.heard("tail", drive.part(0.2, -0.2), rssi = { -70 + (it % 3) - 1 })
            // Cars passing by for a minute or so: not companions.
            for (k in 0 until 3) air.heard("passing$leg-$k", drive.part(1.0 + k, 2.0 + k))
            for (k in 0 until crowd) air.heard("convoy$k", drive.part(0.3, -0.3))
            r.stay(10)
        }
        val name = if (crowd == 0) "Car tail, 3 legs" else "Car tail, 3 legs, $crowd others moving too"
        return Scenario(name, "tail", crowd == 0, r, air)
    }

    /** A neighbour's device, heard all evening while you are home. */
    fun neighbour(): Scenario {
        val r = Route()
        val air = Air()
        val home = r.stay(90)
        air.heard("neighbour", home)
        return Scenario("Neighbour, 90 min at home", "neighbour", false, r, air)
    }

    /** Heard only as you arrive at each stop: someone walking the same way, then gone. */
    fun arrivalEdgesOnly(): Scenario {
        val r = Route()
        val air = Air()
        r.stay(10)
        repeat(3) {
            r.move(8, WALK)
            val stop = r.stay(10)
            air.heard("edge", stop.part(0.0, 1.0))
        }
        return Scenario("Heard only on arrival at 3 stops", "edge", false, r, air)
    }

    fun all(): List<Scenario> = listOf(
        followerOnFoot(), followerAtFamiliarPlaces(), busCommuter(), carTail(), carTail(crowd = 20),
        neighbour(), arrivalEdgesOnly(),
    )
}
