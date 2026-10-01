package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.DeviceCategories
import dev.retrovision.core.identity.CategoryHints
import dev.retrovision.core.identity.AdvertisementInfo
import dev.retrovision.core.identity.MobileAp
import dev.retrovision.core.identity.TrackerClassifier
import dev.retrovision.core.identity.TrackerKind
import dev.retrovision.core.model.BleAdvType
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

data class AnalysisConfig(
    /** How far back an analysis run looks. */
    val lookbackMs: Long = 2 * 3600_000L,
    /** CYT time windows, minutes before "now": [0,5), [5,10), [10,15), [15,20). */
    val windowMinutes: List<Int> = listOf(5, 10, 15, 20),
    val placeRadiusM: Double = 100.0,
    val fixMaxGapMs: Long = 60_000,
    /** Alert when score ≥ this AND the entity was seen at ≥ [alertMinPlaces] places. */
    val alertScore: Double = 0.7,
    val alertMinPlaces: Int = 3,
    /** Span and travel at which the respective sub-scores saturate. */
    val spanSaturationMs: Long = 30 * 60_000L,
    val travelSaturationM: Double = 2_000.0,
    /** A place you are at all the time counts this much, relative to an unfamiliar one. */
    val familiarWeight: Double = 0.3,
)

/** Things the user told us to ignore: own devices, baseline, own networks. */
data class IgnoreList(
    val entityIds: Set<String> = emptySet(),
    val addresses: Set<MacAddress> = emptySet(),
    /** Access points with these SSIDs (your own networks) are ignored. */
    val apSsids: Set<String> = emptySet(),
)

/** One sighting already attributed to an entity by the EntityResolver. */
class EntitySighting(val entityId: String, val sighting: Sighting)

/** Why an entity scored what it scored. Rendered (and localised) by the UI. */
sealed class Reason {
    data class SeenAtPlaces(val places: Int) : Reason()
    /** Some of those places are ones you are at all the time (home, work), so they count for less. */
    /** Seen continuously, at a steady signal strength, while you travelled [meters]. A fixed neighbour cannot do that. */
    data class MovedWithYou(val meters: Double, val rssiStdDb: Double) : Reason()
    data class FamiliarDiscount(val familiarPlaces: Int, val unfamiliarPlaces: Int) : Reason()
    data class PresentInWindows(val windows: Int, val of: Int) : Reason()
    data class SeenFor(val durationMs: Long) : Reason()
    data class TravelledWithYou(val meters: Double) : Reason()
    data class Tracker(val kind: TrackerKind, val separatedFromOwner: Boolean?) : Reason()
    data class MovingAccessPoint(val kind: MobileAp.Kind, val ssid: String) : Reason()
    data class RotatedAddresses(val addresses: Int) : Reason()
}

enum class EntityKind { WIFI_CLIENT, WIFI_AP, BLE_DEVICE, BLE_TRACKER }

class EntityReport(
    val entityId: String,
    val kind: EntityKind,
    val score: Double,
    val alert: Boolean,
    val reasons: List<Reason>,
    val placeIds: Set<Int>,
    /** Indices into [AnalysisConfig.windowMinutes] where the entity was present. */
    val windows: Set<Int>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sightings: Int,
    /** Distinct minutes in which it was heard at least once. */
    val activeMinutes: Int,
    val maxRssi: Int,
    val addresses: Set<MacAddress>,
    /** SSIDs this client asked for (CYT probe analysis) or the AP's own SSID. */
    val ssids: Set<String>,
    val tracker: TrackerKind?,
    /** Bluetooth SIG company id from the manufacturer data, if any advertisement carried one. */
    val bleCompanyId: Int?,
    val mobileAp: MobileAp.Kind?,
    /** Chronological (time, place) track. Used only for analysis, never exported or drawn per device. */
    val track: List<Pair<Long, GeoFix>>,
    /** Best guess of what the device is (phone, watch, router, tracker...). */
    val category: DeviceCategory = DeviceCategory.BLE_OTHER,
    /** Least stable address kind seen: rotating addresses cannot be trusted to identify the device. */
    val macTrust: MacTrust = MacTrust.STABLE,
    /** Networks this client asked for by name in probe requests: it is looking for them. */
    val probedSsids: Set<String> = emptySet(),
    /** Probe requests heard, and how many of them were wildcard (any network). */
    val probeRequests: Int = 0,
    val wildcardProbes: Int = 0,
    /** Authentication/association requests this client sent: it was actually joining these networks. */
    val joinAttempts: List<JoinAttempt> = emptyList(),
    /** BLE local name, if it advertised one. */
    val bleName: String? = null,
)

/** A client trying to connect to an access point (auth / (re)association request). */
data class JoinAttempt(val bssid: MacAddress, val ssid: String, val kind: WifiKind, val count: Int, val lastMs: Long)

class AnalysisResult(
    val nowMs: Long,
    val places: List<Place>,
    /** Sorted by score, descending. Ignored entities are not included. */
    val entities: List<EntityReport>,
    val ignoredEntities: Int,
) {
    val alerts: List<EntityReport> get() = entities.filter { it.alert }
}

/**
 * Persistence / following analysis (CYT surveillance_detector + multi-location
 * tracking, extended with trackers and moving APs).
 *
 * score = 0.40·places + 0.20·windows + 0.15·span + 0.25·travel (+ bonuses), each
 * sub-score in [0,1]. An entity seen at a single place is capped at 0.3: being
 * near you for a long time in one spot is a neighbour, not a follower.
 * Every contribution is reported as a [Reason] so the user can judge it.
 */
class Analyzer(private val config: AnalysisConfig = AnalysisConfig()) {

    fun analyze(
        nowMs: Long,
        sightings: List<EntitySighting>,
        fixes: List<GeoFix>,
        ignore: IgnoreList = IgnoreList(),
        familiar: List<FamiliarPlace> = emptyList(),
    ): AnalysisResult {
        val from = nowMs - config.lookbackMs
        val timeline = FixTimeline(fixes.filter { it.timeMs in from..nowMs }, config.fixMaxGapMs)
        val clusterer = PlaceClusterer(config.placeRadiusM)
        val placeOfFix = HashMap<GeoFix, Place>()
        for (f in timeline.fixes) placeOfFix[f] = clusterer.assign(f)
        val confirmed = familiar.filter { it.state == FamiliarPlace.State.CONFIRMED }
        val familiarIds = clusterer.places.filter { p -> confirmed.any { it.contains(p.lat, p.lon) } }.map { it.id }.toSet()

        val byEntity = sightings.asSequence()
            .filter { it.sighting.timeMs in from..nowMs }
            .groupBy { it.entityId }

        var ignored = 0
        val reports = ArrayList<EntityReport>(byEntity.size)
        for ((id, list) in byEntity) {
            if (isIgnored(id, list, ignore)) {
                ignored++
                continue
            }
            reports += score(id, list.sortedBy { it.sighting.timeMs }, nowMs, timeline, placeOfFix, familiarIds)
        }
        reports.sortWith(compareByDescending<EntityReport> { it.score }.thenBy { it.entityId })
        return AnalysisResult(nowMs, clusterer.places, reports, ignored)
    }

    private fun isIgnored(id: String, list: List<EntitySighting>, ignore: IgnoreList): Boolean {
        if (id in ignore.entityIds) return true
        if (list.any { it.sighting.address in ignore.addresses }) return true
        if (ignore.apSsids.isNotEmpty() && list.any { s ->
                val w = s.sighting.wifi
                w != null && (w.kind == WifiKind.BEACON || w.kind == WifiKind.PROBE_RESP) && w.ssidText in ignore.apSsids
            }
        ) return true
        return false
    }

    private fun score(
        id: String,
        list: List<EntitySighting>,
        nowMs: Long,
        timeline: FixTimeline,
        placeOfFix: Map<GeoFix, Place>,
        familiarIds: Set<Int>,
    ): EntityReport {
        val first = list.first().sighting.timeMs
        val last = list.last().sighting.timeMs
        val addresses = LinkedHashSet<MacAddress>()
        val ssids = LinkedHashSet<String>()
        val places = LinkedHashSet<Int>()
        val windows = HashSet<Int>()
        val track = ArrayList<Pair<Long, GeoFix>>()
        var maxRssi = Int.MIN_VALUE
        var tracker: TrackerKind? = null
        var companyId: Int? = null
        var separated: Boolean? = null
        var mobileAp: MobileAp.Kind? = null
        var mobileSsid = ""
        var isAp = false
        var lastPlace = -1
        val hints = CategoryHints()
        var trust = MacTrust.STABLE
        val probed = LinkedHashSet<String>()
        var probeReqs = 0
        var wildcard = 0
        val joins = LinkedHashMap<MacAddress, JoinAttempt>()
        fun lower(t: MacTrust) { if (t.ordinal > trust.ordinal) trust = t }

        for (es in list) {
            val s = es.sighting
            addresses += s.address
            if (s.rssi != 0) maxRssi = maxOf(maxRssi, s.rssi)
            windowIndex(nowMs - s.timeMs)?.let { windows += it }

            if (s.radio == Radio.WIFI && s.address.isLocallyAdministered) lower(MacTrust.ROTATING)
            s.wifi?.let { w ->
                val text = w.ssidText
                when (w.kind) {
                    WifiKind.BEACON, WifiKind.PROBE_RESP -> {
                        isAp = true
                        if (text.isNotEmpty()) ssids += text
                        if (mobileAp == null) {
                            MobileAp.classify(text, w.bssid ?: s.address)?.let {
                                mobileAp = it
                                mobileSsid = text
                            }
                        }
                    }
                    WifiKind.PROBE_REQ -> {
                        probeReqs += maxOf(1, s.mergedCount)
                        if (text.isNotEmpty()) { ssids += text; probed += text } else wildcard += maxOf(1, s.mergedCount)
                    }
                    WifiKind.AUTH, WifiKind.ASSOC_REQ, WifiKind.REASSOC_REQ -> {
                        // Frames sent by the client (transmitter is not the AP itself).
                        val ap = w.bssid
                        if (ap != null && ap != s.address) {
                            val prev = joins[ap]
                            val ssid = text.ifEmpty { prev?.ssid.orEmpty() }
                            val kind = if (w.kind == WifiKind.AUTH && prev != null) prev.kind else w.kind
                            joins[ap] = JoinAttempt(ap, ssid, kind, (prev?.count ?: 0) + 1, maxOf(prev?.lastMs ?: 0L, s.timeMs))
                        }
                    }
                    else -> Unit
                }
            }
            s.ble?.let { b ->
                when (b.addressKind) {
                    BleAddressKind.RANDOM_RESOLVABLE, BleAddressKind.RANDOM_NON_RESOLVABLE -> lower(MacTrust.ROTATING)
                    BleAddressKind.RANDOM_STATIC -> lower(MacTrust.UNTIL_REBOOT)
                    else -> Unit
                }
                if (b.advType == BleAdvType.SCAN_RSP) hints.add(AdvertisementInfo.of(b.advData))
                if (b.advType != BleAdvType.SCAN_RSP) {
                    val info = AdvertisementInfo.of(b.advData)
                    hints.add(info)
                    if (companyId == null) companyId = info.manufacturerId
                    TrackerClassifier.classify(info)?.let { m ->
                        if (tracker == null || TrackerClassifier.isTag(m.kind)) tracker = m.kind
                        if (m.separated == true) separated = true
                        else if (m.separated == false && separated == null) separated = false
                    }
                }
            }

            timeline.nearest(s.timeMs)?.let { fix ->
                val p = placeOfFix[fix] ?: return@let
                places += p.id
                if (p.id != lastPlace) {
                    track += s.timeMs to fix
                    lastPlace = p.id
                }
            }
        }

        val activeMinutes = list.map { it.sighting.timeMs / 60_000L }.toSet().size
        val coMove = coMovement(list, timeline)

        val placeFixes = track.map { it.second }
        var travel = 0.0
        for (i in placeFixes.indices) for (j in i + 1 until placeFixes.size) {
            travel = maxOf(travel, Geo.distanceM(placeFixes[i], placeFixes[j]))
        }

        val nPlaces = places.size
        val nFamiliar = places.count { it in familiarIds }
        val nUnfamiliar = nPlaces - nFamiliar
        // Familiar places still count (a stalker knows where you live) but for less.
        val effPlaces = nUnfamiliar + config.familiarWeight * nFamiliar
        val sPlaces = ((effPlaces - 1) / 3.0).coerceIn(0.0, 1.0)
        val sWindows = windows.size.toDouble() / config.windowMinutes.size
        val sSpan = ((last - first).toDouble() / config.spanSaturationMs).coerceIn(0.0, 1.0)
        val sTravel = (travel / config.travelSaturationM).coerceIn(0.0, 1.0)
        var score = 0.40 * sPlaces + 0.20 * sWindows + 0.15 * sSpan + 0.25 * sTravel

        val reasons = ArrayList<Reason>()
        if (nPlaces >= 2) reasons += Reason.SeenAtPlaces(nPlaces)
        if (nFamiliar > 0 && nPlaces >= 2) reasons += Reason.FamiliarDiscount(nFamiliar, nUnfamiliar)
        if (windows.size >= 2) reasons += Reason.PresentInWindows(windows.size, config.windowMinutes.size)
        if (last - first >= 5 * 60_000L) reasons += Reason.SeenFor(last - first)
        if (travel >= 200) reasons += Reason.TravelledWithYou(travel)

        val tk = tracker
        if (tk != null) {
            reasons += Reason.Tracker(tk, separated)
            if (effPlaces >= 2 && TrackerClassifier.isTag(tk)) score += if (separated == true) 0.20 else 0.10
        }
        val ap = mobileAp
        if (ap != null && ap != MobileAp.Kind.WIFI_DIRECT) {
            reasons += Reason.MovingAccessPoint(ap, mobileSsid)
            if (effPlaces >= 2) score += 0.10
        }
        if (coMove != null) {
            reasons += Reason.MovedWithYou(coMove.first, coMove.second)
            score += 0.15
        }
        if (addresses.size > 1) reasons += Reason.RotatedAddresses(addresses.size)

        if (effPlaces < 2) score = minOf(score, 0.3)
        score = score.coerceIn(0.0, 1.0)

        val radio = list.first().sighting.radio
        val kind = when {
            radio == Radio.BLE && tk != null && TrackerClassifier.isTag(tk) -> EntityKind.BLE_TRACKER
            radio == Radio.BLE -> EntityKind.BLE_DEVICE
            isAp -> EntityKind.WIFI_AP
            else -> EntityKind.WIFI_CLIENT
        }

        return EntityReport(
            entityId = id,
            kind = kind,
            score = score,
            alert = score >= config.alertScore && effPlaces >= config.alertMinPlaces - 1e-9,
            reasons = reasons,
            placeIds = places,
            windows = windows,
            firstSeenMs = first,
            lastSeenMs = last,
            sightings = list.sumOf { maxOf(1, it.sighting.mergedCount) },
            activeMinutes = activeMinutes,
            maxRssi = if (maxRssi == Int.MIN_VALUE) 0 else maxRssi,
            addresses = addresses,
            ssids = ssids,
            tracker = tk,
            bleCompanyId = companyId,
            mobileAp = mobileAp,
            track = track,
            category = when (kind) {
                EntityKind.WIFI_AP -> DeviceCategories.forWifiAp(mobileAp)
                EntityKind.WIFI_CLIENT -> DeviceCategory.WIFI_CLIENT
                else -> DeviceCategories.forBle(tk, tk != null && TrackerClassifier.isTag(tk), hints)
            },
            macTrust = trust,
            probedSsids = probed,
            probeRequests = probeReqs,
            wildcardProbes = wildcard,
            joinAttempts = joins.values.sortedByDescending { it.lastMs },
            bleName = hints.name,
        )
    }

    /**
     * Longest stretch in which the entity was heard without a gap of more than [COMOVE_GAP_MS]
     * while the phone moved at least [COMOVE_MIN_M], with a steady signal. Returns
     * (metres travelled, RSSI standard deviation) or null.
     */
    private fun coMovement(list: List<EntitySighting>, timeline: FixTimeline): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var start = 0
        for (i in 1..list.size) {
            val endOfSegment = i == list.size ||
                list[i].sighting.timeMs - list[i - 1].sighting.timeMs > COMOVE_GAP_MS
            if (!endOfSegment) continue
            val seg = list.subList(start, i)
            start = i
            if (seg.size < 4) continue
            val t0 = seg.first().sighting.timeMs
            val t1 = seg.last().sighting.timeMs
            if (t1 - t0 < 120_000) continue
            val a = timeline.nearest(t0) ?: continue
            val b = timeline.nearest(t1) ?: continue
            val d = Geo.distanceM(a, b)
            if (d < COMOVE_MIN_M) continue
            val rssi = seg.map { it.sighting.rssi }.filter { it != 0 }
            if (rssi.size < 4) continue
            val mean = rssi.average()
            val std = Math.sqrt(rssi.sumOf { (it - mean) * (it - mean) } / rssi.size)
            if (mean < -90 || std > COMOVE_MAX_STD) continue
            if (best == null || d > best.first) best = d to std
        }
        return best
    }

    /** Index of the CYT window containing `ageMs`, or null if older than the last one. */
    fun windowIndex(ageMs: Long): Int? {
        if (ageMs < 0) return 0
        val minutes = ageMs / 60_000.0
        val i = config.windowMinutes.indexOfFirst { minutes < it }
        return if (i >= 0) i else null
    }

    private companion object {
        const val COMOVE_GAP_MS = 90_000L
        const val COMOVE_MIN_M = 400.0
        const val COMOVE_MAX_STD = 7.5
    }
}
