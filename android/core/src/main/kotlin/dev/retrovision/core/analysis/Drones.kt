// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.identity.NotableCatalog
import dev.retrovision.core.identity.NotableKind
import dev.retrovision.core.identity.RemoteId
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting

/**
 * Drones heard nearby, from two sources of very different quality:
 *  - Remote ID: the drone states its serial, position, height and its pilot's position.
 *    Reliable as far as the drone is honest (the broadcast is unauthenticated).
 *  - Signature only (DJI/Autel/Parrot/Skydio/HOVERAir names and IDs, no Remote ID): we know
 *    something drone-related is transmitting (often the drone's or controller's Wi-Fi), not where.
 */
object Drones {
    class Drone(
        /** Remote ID serial when known, otherwise the radio address. */
        val key: String,
        val remoteId: Boolean,
        val label: String,
        val uaType: Int?,
        val addresses: Set<MacAddress>,
        val radios: Set<Radio>,
        val firstMs: Long,
        val lastMs: Long,
        val maxRssi: Int,
        val lat: Double?, val lon: Double?,
        val heightM: Double?, val altM: Double?,
        val speedMps: Double?, val headingDeg: Double?,
        val operatorLat: Double?, val operatorLon: Double?,
        /** Distance and compass bearing from you, when both positions are known. */
        val distanceM: Double?, val bearingDeg: Double?,
        val operatorDistanceM: Double?, val operatorBearingDeg: Double?,
        val selfId: String?,
    )

    private class Acc(val key: String) {
        var remoteId = false
        var label: String? = null
        val d = RemoteId.Data()
        val addresses = LinkedHashSet<MacAddress>()
        val radios = HashSet<Radio>()
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var lastPos = Long.MIN_VALUE
        var maxRssi = Int.MIN_VALUE
    }

    /**
     * @param sightings recent sightings, any order.
     * @param here your current position (for distance/bearing), if known.
     */
    fun summarize(sightings: List<Sighting>, here: GeoFix?): List<Drone> {
        val byAddr = HashMap<MacAddress, Acc>()
        val sigOnly = HashMap<MacAddress, Acc>()
        val checked = HashSet<Any>()
        for (s in sightings.sortedBy { it.timeMs }) {
            val rid = dev.retrovision.core.identity.FactsCache.of(s).remoteId
            val addr = s.wifi?.bssid?.takeIf { s.wifi.kind == dev.retrovision.core.model.WifiKind.BEACON } ?: s.address
            if (rid != null) {
                val a = byAddr.getOrPut(addr) { Acc(addr.toString()) }
                a.remoteId = true
                merge(a, rid, s.timeMs)
                touch(a, s, addr)
                continue
            }
            // Signature-only: match each distinct payload once.
            val key = Triple(s.address, s.wifi?.ssidText, s.ble?.advData?.contentHashCode())
            val sig = if (checked.add(key)) dev.retrovision.core.identity.FactsCache.of(s).notable.firstOrNull { it.kind == NotableKind.DRONE } else null
            val known = sigOnly[s.address]
            if (sig != null) {
                val a = known ?: Acc(s.address.toString()).also { sigOnly[s.address] = it }
                if (a.label == null) a.label = sig.name + (s.wifi?.ssidText?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                touch(a, s, s.address)
            } else if (known != null) {
                touch(known, s, s.address)
            }
        }
        // One drone can use several radios/addresses: merge Remote ID trails by serial.
        val merged = LinkedHashMap<String, MutableList<Acc>>()
        for (a in byAddr.values) merged.getOrPut(a.d.uasId ?: a.key) { ArrayList() } += a
        val out = ArrayList<Drone>()
        for ((key, list) in merged) {
            val freshest = list.maxBy { it.lastPos }
            val d = RemoteId.Data().apply {
                for (a in list.sortedBy { it.lastPos }) {
                    a.d.uasId?.let { uasId = it }; a.d.uaType?.let { uaType = it }
                    a.d.lat?.let { lat = it; lon = a.d.lon }
                    a.d.heightM?.let { heightM = it }; a.d.altM?.let { altM = it }
                    a.d.speedMps?.let { speedMps = it }; a.d.headingDeg?.let { headingDeg = it }
                    a.d.operatorLat?.let { operatorLat = it; operatorLon = a.d.operatorLon }
                    a.d.selfId?.let { selfId = it }
                }
            }
            out += build(key, true, d.uasId ?: "Remote ID ${freshest.key}", d, list, here)
        }
        for (a in sigOnly.values) out += build(a.key, false, a.label ?: "Drone radio", RemoteId.Data(), listOf(a), here)
        return out.sortedByDescending { it.lastMs }
    }

    private fun merge(a: Acc, r: RemoteId.Data, t: Long) {
        r.uasId?.let { a.d.uasId = it }
        r.uaType?.let { a.d.uaType = it }
        if (r.lat != null) { a.d.lat = r.lat; a.d.lon = r.lon; a.lastPos = t }
        r.heightM?.let { a.d.heightM = it }
        r.altM?.let { a.d.altM = it }
        r.speedMps?.let { a.d.speedMps = it }
        r.headingDeg?.let { a.d.headingDeg = it }
        if (r.operatorLat != null) { a.d.operatorLat = r.operatorLat; a.d.operatorLon = r.operatorLon }
        r.selfId?.let { a.d.selfId = it }
    }

    private fun touch(a: Acc, s: Sighting, addr: MacAddress) {
        a.addresses += addr
        a.radios += s.radio
        a.first = minOf(a.first, s.timeMs)
        a.last = maxOf(a.last, s.timeMs)
        if (s.rssi != 0) a.maxRssi = maxOf(a.maxRssi, s.rssi)
    }

    private fun build(key: String, rid: Boolean, label: String, d: RemoteId.Data, list: List<Acc>, here: GeoFix?): Drone {
        fun dist(lat: Double?, lon: Double?) = if (here != null && lat != null && lon != null) Geo.distanceM(here.lat, here.lon, lat, lon) else null
        fun brg(lat: Double?, lon: Double?) = if (here != null && lat != null && lon != null) bearing(here.lat, here.lon, lat, lon) else null
        return Drone(
            key = key, remoteId = rid, label = label, uaType = d.uaType,
            addresses = list.flatMap { it.addresses }.toSet(), radios = list.flatMap { it.radios }.toSet(),
            firstMs = list.minOf { it.first }, lastMs = list.maxOf { it.last },
            maxRssi = list.maxOf { it.maxRssi }.let { if (it == Int.MIN_VALUE) 0 else it },
            lat = d.lat, lon = d.lon, heightM = d.heightM, altM = d.altM, speedMps = d.speedMps, headingDeg = d.headingDeg,
            operatorLat = d.operatorLat, operatorLon = d.operatorLon,
            distanceM = dist(d.lat, d.lon), bearingDeg = brg(d.lat, d.lon),
            operatorDistanceM = dist(d.operatorLat, d.operatorLon), operatorBearingDeg = brg(d.operatorLat, d.operatorLon),
            selfId = d.selfId,
        )
    }

    /** Initial great-circle bearing, degrees from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /** "N", "NE"… for a bearing. */
    fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]
}
