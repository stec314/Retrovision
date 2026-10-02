// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleAdvType
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind
import kotlin.math.abs

/**
 * Groups sightings into *entities* (physical devices, as far as we can tell).
 *
 * - Globally administered MACs and BLE public/random-static addresses: one entity per address.
 * - Randomised Wi-Fi MACs in probe requests: a new MAC is linked to an existing
 *   entity only when ALL of these hold (conservative on purpose: a wrong link
 *   merges two strangers and can create a false "follower"):
 *     1. same IE fingerprint ([WifiFingerprint]);
 *     2. the entity's last probe was ≤ [linkWindowMs] ago;
 *     3. 802.11 sequence number continues: 1 ≤ (seq − lastSeq) mod 4096 ≤ [maxSeqGap];
 *     4. exactly one candidate matches (ambiguity -> no link).
 *   Some OSes reset the sequence counter on rotation; those rotations are
 *   missed, never mis-linked.
 * - Randomised BLE addresses (resolvable / non-resolvable private): a new address
 *   is stitched to the previous one across a MAC rotation ("carry-over") ONLY when the
 *   advertisement carries a *distinctive, serial-like local name* (e.g. a fitness band
 *   or earbuds broadcasting a serial). That, plus the coarse shape (company id, service
 *   UUIDs, appearance), must match a single recent trail within [bleLinkWindowMs] and at a
 *   comparable signal (|ΔRSSI| ≤ [bleRssiTolDb]).
 *   Why only named devices: a phone's advertising shape (Apple/Google/Microsoft
 *   continuity) is shared by millions, and BLE carries no per-device counter like
 *   Wi-Fi's sequence number, so stitching anonymous phones by shape alone would merge
 *   different people. A well-randomised phone with no distinctive advert is therefore
 *   not linked — a deliberate, honest limit. This stitches fragments *within one
 *   movement*; trails are forgotten after [bleLinkWindowMs], so nothing persists across
 *   sessions and no cross-day identity is built.
 *
 * Not thread-safe. State is in memory; [seed] restores address mappings from storage.
 */
class EntityResolver(
    private val linkWindowMs: Long = 120_000,
    private val maxSeqGap: Int = 64,
    private val forgetAfterMs: Long = 24 * 3600_000L,
    private val bleLinkWindowMs: Long = 300_000,
    private val bleRssiTolDb: Int = 12,
) {
    class Resolution(val entityId: String, val linkedToExisting: Boolean)

    private data class Key(val radio: Radio, val mac: MacAddress)

    private class AddrState(val entityId: String, var lastSeenMs: Long)

    private class Trail(val entityId: String, var mac: MacAddress, var lastMs: Long, var lastSeq: Int)

    private class BleTrail(val entityId: String, var mac: MacAddress, var lastMs: Long, var lastRssi: Int)

    private val byAddress = HashMap<Key, AddrState>()
    private val trailsByFingerprint = HashMap<String, MutableList<Trail>>()
    private val bleTrailsByShape = HashMap<String, MutableList<BleTrail>>()

    var linksMade: Long = 0
        private set

    /** Rotation carry-overs stitched on the BLE side (for diagnostics). */
    var bleLinksMade: Long = 0
        private set

    fun seed(radio: Radio, mac: MacAddress, entityId: String, lastSeenMs: Long) {
        byAddress[Key(radio, mac)] = AddrState(entityId, lastSeenMs)
    }

    fun resolve(s: Sighting, fingerprint: String? = null): Resolution {
        val key = Key(s.radio, s.address)
        val known = byAddress[key]
        val fp = fingerprint ?: s.wifi?.takeIf { it.kind == WifiKind.PROBE_REQ }?.let { WifiFingerprint.of(it.ies) }

        val bleShape = if (s.radio == Radio.BLE) bleShape(s) else null

        if (known != null) {
            known.lastSeenMs = maxOf(known.lastSeenMs, s.timeMs)
            updateTrail(fp, known.entityId, s)
            if (bleShape != null) updateBleTrail(bleShape, known.entityId, s)
            return Resolution(known.entityId, linkedToExisting = false)
        }

        var entityId = defaultId(s.radio, s.address)
        var linked = false
        val wifi = s.wifi
        if (s.radio == Radio.WIFI && s.address.isLocallyAdministered && fp != null &&
            wifi != null && wifi.kind == WifiKind.PROBE_REQ
        ) {
            val candidates = trailsByFingerprint[fp].orEmpty().filter { t ->
                val dt = s.timeMs - t.lastMs
                val dseq = Math.floorMod(wifi.seq - t.lastSeq, 4096)
                t.mac != s.address && dt in 0..linkWindowMs && dseq in 1..maxSeqGap
            }
            if (candidates.size == 1) {
                entityId = candidates[0].entityId
                linked = true
                linksMade++
            }
        } else if (bleShape != null && s.address.isLocallyAdministered) {
            // Carry-over across a BLE MAC rotation, within one movement.
            val candidates = bleTrailsByShape[bleShape].orEmpty().filter { t ->
                val dt = s.timeMs - t.lastMs
                val rssiOk = t.lastRssi == 0 || s.rssi == 0 || abs(s.rssi - t.lastRssi) <= bleRssiTolDb
                t.mac != s.address && dt in 0..bleLinkWindowMs && rssiOk
            }
            if (candidates.size == 1) {
                entityId = candidates[0].entityId
                linked = true
                bleLinksMade++
            }
        }
        byAddress[key] = AddrState(entityId, s.timeMs)
        updateTrail(fp, entityId, s)
        if (bleShape != null) updateBleTrail(bleShape, entityId, s)
        return Resolution(entityId, linked)
    }

    /**
     * A carry-over key for one advertisement, or null when it is not safely linkable.
     * Only adverts with a distinctive, serial-like local name qualify (see the class note):
     * the key is that name plus the coarse shape, so two identical-model devices seen at
     * once fall into separate trails and the single-candidate rule then declines to link.
     * Scan responses are ignored as anchors.
     */
    private fun bleShape(s: Sighting): String? {
        val b = s.ble ?: return null
        if (b.advType == BleAdvType.SCAN_RSP) return null
        val info = AdvertisementInfo.of(b.advData)
        val name = info.name?.trim().orEmpty()
        val distinctive = name.length >= 10 || (name.length >= 4 && name.any { it.isDigit() })
        if (!distinctive) return null
        val uuids = (info.serviceUuids16 + info.serviceData16.keys).toSortedSet()
        val sb = StringBuilder(48)
        sb.append('n').append(name)
        sb.append("|m").append(info.manufacturerId ?: -1)
        sb.append("|u").append(uuids.joinToString(","))
        info.appearance?.let { sb.append("|a").append(it) }
        return sb.toString()
    }

    private fun updateBleTrail(shape: String, entityId: String, s: Sighting) {
        val list = bleTrailsByShape.getOrPut(shape) { ArrayList(2) }
        val t = list.firstOrNull { it.entityId == entityId }
        if (t == null) {
            list += BleTrail(entityId, s.address, s.timeMs, s.rssi)
        } else if (s.timeMs >= t.lastMs) {
            t.mac = s.address
            t.lastMs = s.timeMs
            t.lastRssi = s.rssi
        }
    }

    private fun updateTrail(fp: String?, entityId: String, s: Sighting) {
        val wifi = s.wifi ?: return
        if (fp == null || wifi.kind != WifiKind.PROBE_REQ) return
        val list = trailsByFingerprint.getOrPut(fp) { ArrayList(2) }
        val t = list.firstOrNull { it.entityId == entityId }
        if (t == null) {
            list += Trail(entityId, s.address, s.timeMs, wifi.seq)
        } else if (s.timeMs >= t.lastMs) {
            t.mac = s.address
            t.lastMs = s.timeMs
            t.lastSeq = wifi.seq
        }
    }

    /** Drop state that can no longer influence linking or lookups. */
    fun prune(nowMs: Long) {
        byAddress.values.removeAll { nowMs - it.lastSeenMs > forgetAfterMs }
        val it = trailsByFingerprint.values.iterator()
        while (it.hasNext()) {
            val list = it.next()
            list.removeAll { nowMs - it.lastMs > linkWindowMs }
            if (list.isEmpty()) it.remove()
        }
        val bt = bleTrailsByShape.values.iterator()
        while (bt.hasNext()) {
            val list = bt.next()
            list.removeAll { nowMs - it.lastMs > bleLinkWindowMs }
            if (list.isEmpty()) bt.remove()
        }
    }

    companion object {
        fun defaultId(radio: Radio, mac: MacAddress): String =
            (if (radio == Radio.WIFI) "wifi:" else "ble:") + mac
    }
}
