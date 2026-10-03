// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.DeviceCategories
import dev.retrovision.core.identity.CategoryHints
import dev.retrovision.core.identity.AdvertisementInfo
import dev.retrovision.core.identity.MobileAp
import dev.retrovision.core.identity.NotableCatalog
import dev.retrovision.core.identity.NotableKind
import dev.retrovision.core.identity.NotableSignature
import dev.retrovision.core.identity.RemoteId
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
    /**
     * Retrospective mode. When set, the "presence" sub-score counts the number of DISTINCT
     * time buckets of this size in which the entity reappeared (recurrence across the span),
     * instead of the recency windows anchored to "now". Use for offline review of saved data.
     */
    val periodBucketMs: Long? = null,
    /** Buckets at which the recurrence sub-score saturates (retrospective mode). */
    val periodBucketTarget: Int = 6,
    /**
     * GPS fixes worse than this (horizontal accuracy, metres) are dropped before clustering,
     * travel and co-movement, so indoor/urban drift cannot invent places or "moved with you".
     * Fixes with unknown accuracy (0) are kept, so there is no regression when it is unreported.
     */
    val maxFixAccuracyM: Double = 50.0,
)

/** A tuned config for reviewing all saved data over [spanMs]: rewards recurring, travelling presence. */
fun retrospectiveConfig(
    spanMs: Long,
    alertScore: Double = 0.7,
    alertMinPlaces: Int = 3,
    familiarWeight: Double = 0.3,
    maxFixAccuracyM: Double = 50.0,
): AnalysisConfig = AnalysisConfig(
    lookbackMs = spanMs,
    maxFixAccuracyM = maxFixAccuracyM,
    alertScore = alertScore,
    alertMinPlaces = alertMinPlaces,
    familiarWeight = familiarWeight,
    // Presence across the day matters here: let span and travel saturate over the whole review.
    spanSaturationMs = (spanMs / 4).coerceIn(30 * 60_000L, 6 * 3600_000L),
    travelSaturationM = 3_000.0,
    periodBucketMs = 60 * 60_000L,
    periodBucketTarget = 6,
)

/** Things the user told us to ignore: own devices, baseline, own networks. */
data class IgnoreList(
    val entityIds: Set<String> = emptySet(),
    val addresses: Set<MacAddress> = emptySet(),
    /** Access points with these SSIDs (your own networks) are ignored. */
    val apSsids: Set<String> = emptySet(),
    /** Probe-request fingerprints of YOUR phone (calibrated), so its own probes don't count as "someone else". */
    val ownFingerprints: Set<String> = emptySet(),
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
    /** Retrospective: reappeared in this many distinct time periods (e.g. hours) across the review. */
    data class SeenAcrossPeriods(val periods: Int) : Reason()
    /** Learned to belong to your routine places (seen there across many days). */
    object KnownAtRoutine : Reason()
    data class SeenFor(val durationMs: Long) : Reason()
    data class TravelledWithYou(val meters: Double) : Reason()
    data class Tracker(val kind: TrackerKind, val separatedFromOwner: Boolean?) : Reason()
    data class MovingAccessPoint(val kind: MobileAp.Kind, val ssid: String) : Reason()
    data class RotatedAddresses(val addresses: Int) : Reason()
    /** Matches a notable-device signature (pentest tool, ALPR camera, body cam, glasses...). Information, not proof. */
    data class Notable(val name: String, val kind: NotableKind) : Reason()
    /** Not there when you arrived at a stop, appeared later, and left when you left — [stops] times. */
    data class JoinedAfterYou(val stops: Int) : Reason()
    /** Heard right before and right after [turns] of your [of] changes of direction. */
    data class StayedThroughTurns(val turns: Int, val of: Int) : Reason()
    /** Away from your routine places it asked by name for one of YOUR networks: it has been on it. */
    data class ProbesForYourNetwork(val ssids: Set<String>) : Reason()
    /** Several randomised addresses linked because they ask for the same rare networks. */
    data class LinkedByNetworks(val addresses: Int, val sharedSsids: Int) : Reason()
    /** One access point that changed name or address (same boot moment from its beacon uptime). */
    data class SameApRenamed(val from: String, val to: String) : Reason()
    /** Moves together with [size] − 1 other devices (same places, same times): one person or vehicle? */
    data class TravelsInGroup(val size: Int, val groupId: String) : Reason()
    /** A drone: [remoteId] true when it broadcast Remote ID ([id] is then its serial). */
    data class Drone(val id: String?, val remoteId: Boolean) : Reason()
    /**
     * Stays in one spot: its signal fades the farther you walk from one point, and it was only heard
     * within [reachM] of it. A shop's Wi-Fi you keep circling past, not something moving with you.
     */
    data class StaysPut(val reachM: Double, val decay: Double) : Reason()
    /**
     * An access point only ever heard within [extentM] of one area: a fixed router fits that, and so
     * would a follower that never left the area with you. Not enough movement to tell: no alert.
     */
    data class OneAreaOnly(val extentM: Double) : Reason()
    /** Beacon uptime: running for [days] without a reboot. Typical of a fixed router; information only. */
    data class ApUptime(val days: Double) : Reason()
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
    /** Distinct non-familiar places where it was seen (0 = only at your routine places). */
    val unfamiliarPlaces: Int = 0,
    /** Notable-device signatures it matched. */
    val notable: List<NotableSignature> = emptyList(),
    /** Remote ID serial, when it is a drone broadcasting one. */
    val droneId: String? = null,
    val isDrone: Boolean = false,
    /** Unfamiliar places + familiarWeight × familiar ones (what the alert rule uses). */
    val effectivePlaces: Double = 0.0,
    /** 5-minute time buckets in which it was heard (for co-occurrence). */
    val buckets: Set<Long> = emptySet(),
    /**
     * The resolver ids merged into this report (rare-network or AP-uptime links). Snoozes, verdicts
     * and alert cooldowns should follow all of them: the merged id can change between analyses.
     */
    val memberIds: Set<String> = setOf(entityId),
    /** Decoded HT capabilities summary (Wi-Fi clients), for transparency only — never drives linking. */
    val htProfile: String? = null,
    /** Every address of this entity, oldest first, and how it was linked. */
    val addressLinks: List<AddressLink> = emptyList(),
    /** Access points: days since boot from the beacon timestamp, when known. */
    val apUptimeDays: Double? = null,
    /** When it was heard and at which of YOUR places, oldest first (capped to the latest [VisitBuilder.MAX]). */
    val visits: List<DeviceVisit> = emptyList(),
) {
    fun with(score: Double, alert: Boolean, reasons: List<Reason>) = EntityReport(
        entityId, kind, score, alert, reasons, placeIds, windows, firstSeenMs, lastSeenMs, sightings, activeMinutes,
        maxRssi, addresses, ssids, tracker, bleCompanyId, mobileAp, track, category, macTrust, probedSsids, probeRequests,
        wildcardProbes, joinAttempts, bleName, unfamiliarPlaces, notable, droneId, isDrone, effectivePlaces, buckets,
        memberIds, htProfile, addressLinks, apUptimeDays, visits,
    )
}

/** How an address came to belong to an entity (shown so you can judge the link yourself). */
enum class LinkVia {
    /** The address the entity was first seen with. */
    ORIGINAL,
    /** Wi-Fi: same probe fingerprint, 802.11 sequence number continued, similar signal. */
    SEQUENCE,
    /** BLE: same distinctive name and advert shape, right after the previous address went quiet. */
    BLE_NAME,
    /** Several addresses asking for the same rare networks. */
    RARE_NETWORKS,
    /** Access point with the same boot moment (beacon uptime) under a new name or address. */
    AP_UPTIME,
}

/**
 * One stretch in which a device was heard while you were at one place: when, where YOU were
 * (the place centre, not the device's position: a single receiver cannot locate it), how often and
 * how loud. [placeId] is -1 when there was no usable GPS fix.
 */
class DeviceVisit(
    val placeId: Int,
    val lat: Double?,
    val lon: Double?,
    val startMs: Long,
    val endMs: Long,
    val sightings: Int,
    val maxRssi: Int,
)

/** Groups a device's time-ordered sightings into [DeviceVisit]s: a new one on a new place or a 10-minute gap. */
class VisitBuilder {
    private val out = ArrayList<DeviceVisit>()
    private var place: Place? = null
    private var placeId = Int.MIN_VALUE
    private var start = 0L
    private var end = 0L
    private var n = 0
    private var rssi = Int.MIN_VALUE

    fun add(s: dev.retrovision.core.model.Sighting, p: Place?) {
        val id = p?.id ?: -1
        if (n > 0 && (id != placeId || s.timeMs - end > GAP_MS)) flush()
        if (n == 0) { place = p; placeId = id; start = s.timeMs }
        end = maxOf(end, s.timeMs)
        n += maxOf(1, s.mergedCount)
        if (s.rssi != 0) rssi = maxOf(rssi, s.rssi)
    }

    private fun flush() {
        out += DeviceVisit(placeId, place?.lat, place?.lon, start, end, n, if (rssi == Int.MIN_VALUE) 0 else rssi)
        n = 0; rssi = Int.MIN_VALUE; end = 0L
    }

    fun result(): List<DeviceVisit> {
        if (n > 0) flush()
        return if (out.size > MAX) out.subList(out.size - MAX, out.size).toList() else out
    }

    companion object {
        const val GAP_MS = 10 * 60_000L
        const val MAX = 100
    }
}

class AddressLink(val address: MacAddress, val via: LinkVia, val firstMs: Long, val lastMs: Long, val sightings: Int)

/** A client trying to connect to an access point (auth / (re)association request). */
data class JoinAttempt(val bssid: MacAddress, val ssid: String, val kind: WifiKind, val count: Int, val lastMs: Long)

class AnalysisResult(
    val nowMs: Long,
    val places: List<Place>,
    /** Sorted by score, descending. Ignored entities are not included. */
    val entities: List<EntityReport>,
    val ignoredEntities: Int,
    /** Your changes of direction and stops in the window (route-check context). */
    val turns: Int = 0,
    val stops: Int = 0,
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
        /** Entities learned to belong to your routine places (neighbours, colleagues): damped. */
        residents: Set<String> = emptySet(),
    ): AnalysisResult {
        val from = nowMs - config.lookbackMs
        // Only trust fixes good enough to place you (indoor/canyon drift would fake movement).
        val goodFixes = fixes.filter {
            it.timeMs in from..nowMs && it.accuracyM <= config.maxFixAccuracyM.toFloat()
        }
        val timeline = FixTimeline(goodFixes, config.fixMaxGapMs)
        val clusterer = PlaceClusterer(config.placeRadiusM)
        val placeOfFix = HashMap<GeoFix, Place>()
        for (f in timeline.fixes) placeOfFix[f] = clusterer.assign(f)
        val confirmed = familiar.filter { it.state == FamiliarPlace.State.CONFIRMED }
        val familiarIds = clusterer.places.filter { p -> confirmed.any { it.contains(p.lat, p.lon) } }.map { it.id }.toSet()

        val inWindow = sightings.filter { it.sighting.timeMs in from..nowMs }
        val links = NetworkLinker.link(inWindow, ignore.apSsids)
        val apLinks = ApUptimeLinker.link(inWindow)
        val byEntity = inWindow.groupBy { links.root[it.entityId] ?: apLinks.root[it.entityId] ?: it.entityId }

        // Your route: stops and turns, and when the receivers were hearing anything at all.
        val stops = Route.stops(timeline.fixes, { placeOfFix[it]?.id })
        val turns = Route.turns(timeline.fixes)
        val activeMinutes = HashSet<Long>()
        for (list in byEntity.values) for (es in list) activeMinutes += es.sighting.timeMs / 60_000L
        val route = RouteContext(
            stops, turns,
            elsewhere = { t, stop -> timeline.nearest(t)?.let { Geo.distanceM(it.lat, it.lon, stop.lat, stop.lon) > Route.Config().stopMergeM } ?: false },
            sensorActive = { t -> (t / 60_000L) in activeMinutes },
        )

        var ignored = 0
        val reports = ArrayList<EntityReport>(byEntity.size)
        for ((id, list) in byEntity) {
            if (isIgnored(id, list, ignore) || list.any { it.entityId != id && it.entityId in ignore.entityIds }) {
                ignored++
                continue
            }
            val via = { member: String ->
                when {
                    member == id -> null
                    links.root[member] == id -> LinkVia.RARE_NETWORKS
                    else -> LinkVia.AP_UPTIME
                }
            }
            val r0 = score(id, list.sortedBy { it.sighting.timeMs }, nowMs, timeline, placeOfFix, familiarIds, id in residents, route, ignore, via)
            val members = list.map { it.entityId }.toSet()
            val r = if (members.size > 1) EntityReport(
                r0.entityId, r0.kind, r0.score, r0.alert, r0.reasons, r0.placeIds, r0.windows, r0.firstSeenMs, r0.lastSeenMs,
                r0.sightings, r0.activeMinutes, r0.maxRssi, r0.addresses, r0.ssids, r0.tracker, r0.bleCompanyId, r0.mobileAp,
                r0.track, r0.category, r0.macTrust, r0.probedSsids, r0.probeRequests, r0.wildcardProbes, r0.joinAttempts,
                r0.bleName, r0.unfamiliarPlaces, r0.notable, r0.droneId, r0.isDrone, r0.effectivePlaces, r0.buckets, members,
                r0.htProfile, r0.addressLinks, r0.apUptimeDays, r0.visits,
            ) else r0
            reports += links.shared[id]?.let { sh ->
                val n = list.map { it.sighting.address }.toSet().size
                r.with(r.score, r.alert, r.reasons + Reason.LinkedByNetworks(n, sh))
            } ?: apLinks.renamed[id]?.let { (from, to) ->
                r.with(r.score, r.alert, r.reasons + Reason.SameApRenamed(from, to))
            } ?: r
        }
        groups(reports)
        reports.sortWith(compareByDescending<EntityReport> { it.score }.thenBy { it.entityId })
        return AnalysisResult(nowMs, clusterer.places, reports, ignored, turns.size, stops.size)
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
        isResident: Boolean,
        route: RouteContext,
        ignore: IgnoreList = IgnoreList(),
        mergedVia: (String) -> LinkVia? = { null },
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
        var htProfile: String? = null
        val joins = LinkedHashMap<MacAddress, JoinAttempt>()
        val notable = LinkedHashSet<NotableSignature>()
        val ownNetProbes = ArrayList<Long>() // times it asked for one of your networks (not your phone)
        val ownNetNames = LinkedHashSet<String>()
        var remoteId = false
        var droneId: String? = null
        val positioned = ArrayList<Triple<GeoFix, Int, String>>() // where you were, RSSI, receiver
        val visits = VisitBuilder()
        var maxTsfUs = -1L
        fun lower(t: MacTrust) { if (t.ordinal > trust.ordinal) trust = t }

        for (es in list) {
            val s = es.sighting
            addresses += s.address
            if (s.rssi != 0) maxRssi = maxOf(maxRssi, s.rssi)
            if (config.periodBucketMs != null) {
                windows += (s.timeMs / config.periodBucketMs).toInt()
            } else {
                windowIndex(nowMs - s.timeMs)?.let { windows += it }
            }

            if (s.radio == Radio.WIFI && s.address.isLocallyAdministered) lower(MacTrust.ROTATING)
            s.wifi?.let { w ->
                val text = w.ssidText
                when (w.kind) {
                    WifiKind.BEACON, WifiKind.PROBE_RESP -> {
                        isAp = true
                        if (w.tsfUs > maxTsfUs) maxTsfUs = w.tsfUs
                        if (text.isNotEmpty()) ssids += text
                        if (mobileAp == null) {
                            MobileAp.classify(text, w.bssid ?: s.address)?.let {
                                mobileAp = it
                                mobileSsid = text
                            }
                        }
                    }
                    WifiKind.PROBE_REQ -> {
                        if (text.isNotEmpty() && text in ignore.apSsids &&
                            (ignore.ownFingerprints.isEmpty() || dev.retrovision.core.identity.WifiFingerprint.of(w.ies) !in ignore.ownFingerprints)
                        ) { ownNetProbes += s.timeMs; ownNetNames += text }
                        if (htProfile == null) dev.retrovision.core.identity.HtCaps.fromIes(w.ies)?.let { htProfile = it.summary() }
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
                val facts = dev.retrovision.core.identity.FactsCache.of(s)
                val info = facts.info!!
                if (b.advType == BleAdvType.SCAN_RSP) hints.add(info)
                if (b.advType != BleAdvType.SCAN_RSP) {
                    hints.add(info)
                    if (companyId == null) companyId = info.manufacturerId
                    facts.tracker?.let { m ->
                        if (tracker == null || TrackerClassifier.isTag(m.kind)) tracker = m.kind
                        if (m.separated == true) separated = true
                        else if (m.separated == false && separated == null) separated = false
                    }
                }
            }

            // Decoded once per distinct payload across analyses (FactsCache).
            val f = dev.retrovision.core.identity.FactsCache.of(s)
            notable += f.notable
            f.remoteId?.let { r -> remoteId = true; r.uasId?.let { droneId = it } }

            val fixHere = timeline.nearest(s.timeMs)
            val placeHere = fixHere?.let { placeOfFix[it] }
            visits.add(s, placeHere)
            fixHere?.let { fix ->
                if (s.rssi != 0) positioned += Triple(fix, s.rssi, s.probeId)
                val p = placeHere ?: return@let
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
        val sWindows = if (config.periodBucketMs != null) {
            (windows.size.toDouble() / config.periodBucketTarget).coerceIn(0.0, 1.0)
        } else {
            windows.size.toDouble() / config.windowMinutes.size
        }
        val sSpan = ((last - first).toDouble() / config.spanSaturationMs).coerceIn(0.0, 1.0)
        val sTravel = (travel / config.travelSaturationM).coerceIn(0.0, 1.0)
        var score = 0.40 * sPlaces + 0.20 * sWindows + 0.15 * sSpan + 0.25 * sTravel

        val reasons = ArrayList<Reason>()
        if (nPlaces >= 2) reasons += Reason.SeenAtPlaces(nPlaces)
        if (nFamiliar > 0 && nPlaces >= 2) reasons += Reason.FamiliarDiscount(nFamiliar, nUnfamiliar)
        if (config.periodBucketMs != null) {
            if (windows.size >= 2) reasons += Reason.SeenAcrossPeriods(windows.size)
        } else if (windows.size >= 2) {
            reasons += Reason.PresentInWindows(windows.size, config.windowMinutes.size)
        }
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

        // Asked for one of your networks while you were away from your routine places.
        val awayOwnProbes = ownNetProbes.count { t ->
            timeline.nearest(t)?.let { f -> placeOfFix[f]?.id?.let { it !in familiarIds } } == true
        }
        if (awayOwnProbes > 0) {
            reasons += Reason.ProbesForYourNetwork(ownNetNames)
            // Until your own phone is identified, the most likely asker is your own phone: no bonus.
            if (ignore.ownFingerprints.isNotEmpty()) score += 0.10
        }

        // How it behaved around your stops and turns.
        val b = Route.behaviour(
            LongArray(list.size) { list[it].sighting.timeMs }, route.stops, route.turns, route.elsewhere, route.sensorActive,
        )
        if (b.joinedAfterYou > 0) {
            reasons += Reason.JoinedAfterYou(b.joinedAfterYou)
            if (b.joinedAfterYou >= 2) score += 0.15
        }
        if (b.stayedThroughTurns >= 2) {
            reasons += Reason.StayedThroughTurns(b.stayedThroughTurns, route.turns.size)
            if (b.stayedThroughTurns >= 3) score += 0.10
        }
        val drone = remoteId || notable.any { it.kind == NotableKind.DRONE }
        if (drone) {
            reasons += Reason.Drone(droneId, remoteId)
            // A drone that keeps turning up where you are is exactly what this tool is for.
            if (effPlaces >= 2) score += 0.15
        }
        for (n in notable) if (n.kind != NotableKind.DRONE) reasons += Reason.Notable(n.name, n.kind)

        if (effPlaces < 2) score = minOf(score, 0.3)
        // Geometry alone can't tell a follower from a fixed transmitter you keep walking around
        // (several "places" within its range). The signal can: see [stationary].
        val staysPut = if (nPlaces >= 2) stationary(positioned) else null
        if (staysPut != null) {
            reasons += Reason.StaysPut(staysPut.first, staysPut.second)
            score = minOf(score, STAYS_PUT_CAP)
        } else if (isAp && nPlaces >= 2) {
            // An access point heard only within one area: a fixed router fits, and nothing shows it
            // left the area with you. Proof of following needs it heard farther apart than its range.
            val extent = extentM(positioned)
            if (extent < AP_ONE_AREA_M) {
                reasons += Reason.OneAreaOnly(extent)
                score = minOf(score, ONE_AREA_CAP)
            }
        }
        val uptimeDays = if (maxTsfUs > 0) maxTsfUs / 86_400e6 else null
        if (uptimeDays != null && uptimeDays >= 1.0) reasons += Reason.ApUptime(uptimeDays)
        if (isResident) {
            reasons += Reason.KnownAtRoutine
            score = minOf(score, 0.25) // belongs to your routine environment: not a follower
        }
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
            category = if (drone) DeviceCategory.DRONE else when (kind) {
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
            unfamiliarPlaces = nUnfamiliar,
            notable = notable.toList(),
            droneId = droneId,
            isDrone = drone,
            effectivePlaces = effPlaces,
            buckets = list.map { it.sighting.timeMs / 300_000L }.toSet(),
            htProfile = htProfile,
            addressLinks = addressLinks(list, mergedVia),
            visits = visits.result(),
            apUptimeDays = uptimeDays,
        )
    }

    private fun addressLinks(list: List<EntitySighting>, mergedVia: (String) -> LinkVia?): List<AddressLink> {
        class Acc(val first: EntitySighting) { var last = first.sighting.timeMs; var n = 0 }
        val acc = LinkedHashMap<MacAddress, Acc>()
        for (es in list) {
            val a = acc.getOrPut(es.sighting.address) { Acc(es) }
            a.last = maxOf(a.last, es.sighting.timeMs)
            a.n += maxOf(1, es.sighting.mergedCount)
        }
        return acc.entries.map { (mac, a) ->
            val member = a.first.entityId
            // The address a resolver entity started with is its own id; later ones were stitched to it.
            val original = member == dev.retrovision.core.identity.EntityResolver.defaultId(a.first.sighting.radio, mac)
            val merged = mergedVia(member) // non-null: this whole resolver entity was merged in
            val via = when {
                original && merged != null -> merged
                original -> LinkVia.ORIGINAL
                a.first.sighting.radio == Radio.WIFI -> LinkVia.SEQUENCE
                else -> LinkVia.BLE_NAME
            }
            AddressLink(mac, via, a.first.sighting.timeMs, a.last, a.n)
        }.sortedBy { it.firstMs }
    }

    /** Largest distance between two of your positions where it was heard (≤ 150 evenly picked). */
    private fun extentM(samples: List<Triple<GeoFix, Int, String>>): Double {
        if (samples.size < 2) return 0.0
        val pts = if (samples.size <= 150) samples else List(150) { samples[it * samples.size / 150] }
        var best = 0.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            best = maxOf(best, Geo.distanceM(pts[i].first.lat, pts[i].first.lon, pts[j].first.lat, pts[j].first.lon))
        }
        return best
    }

    /**
     * Devices that keep turning up together (same places, same times) are probably carried by one
     * person or vehicle. A group survives one member rotating its address, so it's extra evidence.
     */
    private fun groups(reports: MutableList<EntityReport>) {
        // Pairwise comparison is quadratic: with tens of thousands of devices in a city centre it took
        // most of a run. The group bonus (+0.05) can only matter near the alert threshold, so only the
        // highest-scoring candidates there are compared.
        val cand = reports.indices.filter {
            reports[it].placeIds.size >= 3 && reports[it].score >= config.alertScore - GROUP_SCORE_MARGIN &&
                reports[it].reasons.none { r -> r is Reason.KnownAtRoutine || r is Reason.StaysPut || r is Reason.OneAreaOnly }
        }.sortedByDescending { reports[it].score }.take(GROUP_MAX_CANDIDATES)
        if (cand.size < 2) return
        val parent = IntArray(reports.size) { it }
        fun find(x: Int): Int { var y = x; while (parent[y] != y) { parent[y] = parent[parent[y]]; y = parent[y] }; return y }
        fun jac(a: Set<*>, b: Set<*>) = if (a.isEmpty() && b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
        for (x in cand.indices) for (y in x + 1 until cand.size) {
            val a = reports[cand[x]]; val b = reports[cand[y]]
            if (jac(a.placeIds, b.placeIds) >= GROUP_PLACES_J && jac(a.buckets, b.buckets) >= GROUP_TIME_J) {
                parent[find(cand[x])] = find(cand[y])
            }
        }
        val members = cand.groupBy { find(it) }.filterValues { it.size >= 2 }
        for ((_, idx) in members) {
            val gid = idx.map { reports[it].entityId }.min()
            for (i in idx) {
                val r = reports[i]
                val s = (r.score + if (r.effectivePlaces >= 2) 0.05 else 0.0).coerceIn(0.0, 1.0)
                reports[i] = r.with(s, s >= config.alertScore && r.effectivePlaces >= config.alertMinPlaces - 1e-9, r.reasons + Reason.TravelsInGroup(idx.size, gid))
            }
        }
        reports.sortWith(compareByDescending<EntityReport> { it.score }.thenBy { it.entityId })
    }

    class RouteContext(
        val stops: List<Route.Stop>,
        val turns: List<Route.Turn>,
        val elsewhere: (Long, Route.Stop) -> Boolean,
        val sensorActive: (Long) -> Boolean,
    )

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
            // Receivers differ (probe antenna vs phone): judge steadiness on one receiver only.
            val rssi = seg.groupBy { it.sighting.probeId }.values.maxBy { it.size }
                .map { it.sighting.rssi }.filter { it != 0 }
            if (rssi.size < 4) continue
            val mean = rssi.average()
            val std = Math.sqrt(rssi.sumOf { (it - mean) * (it - mean) } / rssi.size)
            if (mean < -90 || std > COMOVE_MAX_STD) continue
            if (best == null || d > best.first) best = d to std
        }
        return best
    }

    /**
     * Evidence that the transmitter is fixed: returns (reach m, Spearman ρ) or null.
     *
     * A fixed transmitter is loudest near one point and fades with your distance from it. The point
     * is estimated from the strongest readings of HALF the samples, and the fade is measured on the
     * OTHER half: estimating and testing on the same samples would show a fake fade even for pure
     * noise (the strongest samples are near the estimate by construction), and could hide a tag
     * carried on you. A device moving with you, or on you, shows no such fade.
     * One receiver only: the probe and the phone read different dBm for the same signal.
     */
    internal fun stationary(samples: List<Triple<GeoFix, Int, String>>): Pair<Double, Double>? {
        if (samples.size < STAYS_PUT_MIN_SAMPLES) return null
        val main = samples.groupBy { it.third }.values.maxBy { it.size }
        if (main.size < STAYS_PUT_MIN_SAMPLES) return null
        val fit = main.filterIndexed { i, _ -> i % 2 == 0 }
        val test = main.filterIndexed { i, _ -> i % 2 == 1 }
        val top = fit.sortedByDescending { it.second }.take(maxOf(3, fit.size / 5))
        val cLat = top.sumOf { it.first.lat } / top.size
        val cLon = top.sumOf { it.first.lon } / top.size
        val dist = test.map { Geo.distanceM(it.first.lat, it.first.lon, cLat, cLon) }
        val sorted = dist.sorted()
        val reach = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        val spread = reach - sorted[sorted.size / 10]
        if (reach > STAYS_PUT_MAX_REACH_M || spread < STAYS_PUT_MIN_SPREAD_M) return null
        val rho = spearman(dist, test.map { it.second.toDouble() })
        // Both a clear fade and a significant one: z ≈ ρ·√(n−1) under "no relation". At z ≤ −4 a
        // device carried with you is mistaken for a fixed one about 3 times in 100,000.
        val z = rho * Math.sqrt((test.size - 1).toDouble())
        return if (rho <= STAYS_PUT_MAX_RHO && z <= STAYS_PUT_MAX_Z) reach to rho else null
    }

    private fun spearman(a: List<Double>, b: List<Double>): Double {
        fun ranks(x: List<Double>): DoubleArray {
            val idx = x.indices.sortedBy { x[it] }
            val r = DoubleArray(x.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && x[idx[j + 1]] == x[idx[i]]) j++
                val avg = (i + j) / 2.0
                for (k in i..j) r[idx[k]] = avg
                i = j + 1
            }
            return r
        }
        val ra = ranks(a); val rb = ranks(b)
        val ma = ra.average(); val mb = rb.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in ra.indices) {
            num += (ra[i] - ma) * (rb[i] - mb); da += (ra[i] - ma) * (ra[i] - ma); db += (rb[i] - mb) * (rb[i] - mb)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / Math.sqrt(da * db)
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
        const val GROUP_PLACES_J = 0.75
        const val GROUP_TIME_J = 0.4
        const val GROUP_SCORE_MARGIN = 0.3
        const val GROUP_MAX_CANDIDATES = 400
        const val STAYS_PUT_MIN_SAMPLES = 16
        /** Generous: an outdoor AP can be heard 300+ m away. The fade test does the real work. */
        const val STAYS_PUT_MAX_REACH_M = 450.0
        const val STAYS_PUT_MIN_SPREAD_M = 40.0
        const val STAYS_PUT_MAX_RHO = -0.2
        const val STAYS_PUT_MAX_Z = -4.0
        const val STAYS_PUT_CAP = 0.35
        /**
         * An outdoor AP can be heard ~250-300 m away, so a fixed one can be heard up to ~600 m apart
         * as you walk past on opposite sides. Only beyond that is "it was there too" proof.
         */
        const val AP_ONE_AREA_M = 600.0
        const val ONE_AREA_CAP = 0.45
    }
}
