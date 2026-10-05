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
 *     4. the signal is comparable: |ΔRSSI| ≤ [wifiRssiTolDb] (a real rotation keeps a similar
 *        signal; two same-model phones at different distances would jump). This guards the
 *        case the fingerprint cannot: two devices of the same model share a fingerprint, so a
 *        chance sequence-number line-up would otherwise merge them into a false "follower";
 *     5. exactly one candidate matches (ambiguity -> no link).
 *   Some OSes reset the sequence counter on rotation; those rotations are
 *   missed, never mis-linked. Recent devices increasingly reset it per burst
 *   (Puig et al., arXiv:2606.25788, 2026), so this link is conservative and
 *   misses more than it used to — a deliberate trade: a missed link is safer
 *   than a false one.
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
 * - Randomised BLE addresses whose advert is byte-for-byte identical across the rotation
 *   ("handover"): a new address is linked to the previous one when the advert (Flags aside) is
 *   exactly the same, the old address went quiet [handoverMinGapMs]..[handoverMaxGapMs] before,
 *   the signal is close (|ΔRSSI| ≤ [handoverRssiTolDb]) and exactly one trail matches. Only adverts
 *   that carry a local name, or ≥ 6 bytes of manufacturer/service data with real variety in them,
 *   qualify ([handoverKey]); Apple and Microsoft continuity without a name never do. Field data
 *   (one day, ~6.500 rotating addresses) showed own devices like a phone advertising its name
 *   rotating every 8–9 min with a 2–10 s handover: 199 links, 2 of them doubtful. An all-zero
 *   payload is shared by many devices, hence the variety rule. Same scope as above: within one
 *   movement, never across days.
 *
 * Not thread-safe. State is in memory; [seed] restores address mappings from storage.
 */
class EntityResolver(
    private val linkWindowMs: Long = 120_000,
    private val maxSeqGap: Int = 64,
    private val forgetAfterMs: Long = 24 * 3600_000L,
    private val bleLinkWindowMs: Long = 300_000,
    private val bleRssiTolDb: Int = 12,
    /**
     * Max RSSI change (dB) across a Wi-Fi MAC rotation. Looser than BLE: probe RSSI is noisier
     * and a rotation can span up to [linkWindowMs], during which the person may move. It only
     * rejects gross mismatches (a device clearly at another distance), not normal fading.
     */
    private val wifiRssiTolDb: Int = 20,
    private val handoverMinGapMs: Long = 1_000,
    private val handoverMaxGapMs: Long = 30_000,
    private val handoverRssiTolDb: Int = 8,
) {
    class Resolution(val entityId: String, val linkedToExisting: Boolean)

    private data class Key(val radio: Radio, val mac: MacAddress)

    private class AddrState(val entityId: String, var lastSeenMs: Long)

    private class Trail(val entityId: String, var mac: MacAddress, var lastMs: Long, var lastSeq: Int, var lastRssi: Int)

    private class BleTrail(val entityId: String, var mac: MacAddress, var lastMs: Long, var lastRssi: Int)

    private val byAddress = HashMap<Key, AddrState>()
    private val trailsByFingerprint = HashMap<String, MutableList<Trail>>()
    private val bleTrailsByShape = HashMap<String, MutableList<BleTrail>>()
    private val handoverTrails = HashMap<String, MutableList<BleTrail>>()

    var linksMade: Long = 0
        private set

    /** Rotation carry-overs stitched on the BLE side (for diagnostics). */
    var bleLinksMade: Long = 0
        private set

    /** Rotations linked by an identical advert right after the old address went quiet. */
    var handoverLinksMade: Long = 0
        private set

    fun seed(radio: Radio, mac: MacAddress, entityId: String, lastSeenMs: Long) {
        byAddress[Key(radio, mac)] = AddrState(entityId, lastSeenMs)
    }

    fun resolve(s: Sighting, fingerprint: String? = null): Resolution {
        val key = Key(s.radio, s.address)
        val known = byAddress[key]
        val fp = fingerprint ?: s.wifi?.takeIf { it.kind == WifiKind.PROBE_REQ }?.let { WifiFingerprint.of(it.ies) }

        val bleShape = if (s.radio == Radio.BLE) bleShape(s) else null
        val handover = if (s.radio == Radio.BLE && bleShape == null && rotatingBle(s)) handoverKeyOf(s) else null

        if (known != null) {
            known.lastSeenMs = maxOf(known.lastSeenMs, s.timeMs)
            updateTrail(fp, known.entityId, s)
            if (bleShape != null) updateBleTrail(bleTrailsByShape, bleShape, known.entityId, s)
            if (handover != null) updateBleTrail(handoverTrails, handover, known.entityId, s)
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
                val rssiOk = t.lastRssi == 0 || s.rssi == 0 || abs(s.rssi - t.lastRssi) <= wifiRssiTolDb
                t.mac != s.address && dt in 0..linkWindowMs && dseq in 1..maxSeqGap && rssiOk
            }
            if (candidates.size == 1) {
                entityId = candidates[0].entityId
                linked = true
                linksMade++
            }
        } else if (bleShape != null && rotatingBle(s)) {
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
        } else if (handover != null) {
            // Handover: the same advert, from a new address, just after the old one went quiet.
            val candidates = handoverTrails[handover].orEmpty().filter { t ->
                val gap = s.timeMs - t.lastMs
                val rssiOk = t.lastRssi == 0 || s.rssi == 0 || abs(s.rssi - t.lastRssi) <= handoverRssiTolDb
                t.mac != s.address && gap in handoverMinGapMs..handoverMaxGapMs && rssiOk
            }
            if (candidates.size == 1) {
                entityId = candidates[0].entityId
                linked = true
                handoverLinksMade++
            }
        }
        byAddress[key] = AddrState(entityId, s.timeMs)
        updateTrail(fp, entityId, s)
        if (bleShape != null) updateBleTrail(bleTrailsByShape, bleShape, entityId, s)
        if (handover != null) updateBleTrail(handoverTrails, handover, entityId, s)
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
        if (!isDistinctiveName(name)) return null
        val uuids = (info.serviceUuids16 + info.serviceData16.keys).toSortedSet()
        val sb = StringBuilder(48)
        sb.append('n').append(name)
        sb.append("|m").append(info.manufacturerId ?: -1)
        sb.append("|u").append(uuids.joinToString(","))
        info.appearance?.let { sb.append("|a").append(it) }
        return sb.toString()
    }

    /**
     * A BLE address that rotates. The 802.11 U/L bit means nothing for BLE (a random address's top
     * bits carry its sub-type), so the advertised address kind decides; only an unknown kind falls
     * back to the U/L bit. Before this, about half of real rotations were never considered.
     */
    private fun rotatingBle(s: Sighting): Boolean = when (s.ble?.addressKind) {
        BleAddressKind.RANDOM_RESOLVABLE, BleAddressKind.RANDOM_NON_RESOLVABLE -> true
        BleAddressKind.PUBLIC, BleAddressKind.RANDOM_STATIC -> false
        else -> s.address.isLocallyAdministered
    }

    private fun handoverKeyOf(s: Sighting): String? {
        val b = s.ble ?: return null
        if (b.advType == BleAdvType.SCAN_RSP) return null
        return handoverKey(b.advData)
    }

    private fun updateBleTrail(map: HashMap<String, MutableList<BleTrail>>, shape: String, entityId: String, s: Sighting) {
        val list = map.getOrPut(shape) { ArrayList(2) }
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
            list += Trail(entityId, s.address, s.timeMs, wifi.seq, s.rssi)
        } else if (s.timeMs >= t.lastMs) {
            t.mac = s.address
            t.lastMs = s.timeMs
            t.lastSeq = wifi.seq
            if (s.rssi != 0) t.lastRssi = s.rssi
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
        val ht = handoverTrails.values.iterator()
        while (ht.hasNext()) {
            val list = ht.next()
            list.removeAll { nowMs - it.lastMs > handoverMaxGapMs }
            if (list.isEmpty()) ht.remove()
        }
    }

    companion object {
        fun defaultId(radio: Radio, mac: MacAddress): String =
            (if (radio == Radio.WIFI) "wifi:" else "ble:") + mac

        /** A serial-like local name: long enough, or short with digits ("5AM0452823", "TAG12345"). */
        fun isDistinctiveName(name: String): Boolean {
            val n = name.trim()
            return n.length >= 10 || (n.length >= 4 && n.any { it.isDigit() })
        }

        private const val APPLE = 0x004C
        private const val MICROSOFT = 0x0006

        /**
         * Key for a handover link: the advert without its Flags, as hex, or null when the advert is
         * too generic to tell two devices apart. Qualifies: a local name that is not serial-like
         * (those use the name path), or ≥ 6 bytes of manufacturer/service data with at least 4
         * different byte values. Apple/Microsoft continuity without a name never qualifies.
         */
        fun handoverKey(advData: ByteArray): String? {
            val parts = AdParser.parse(advData)
            if (parts.isEmpty()) return null
            var name: String? = null
            var manufacturer: Int? = null
            var payload = 0
            for (p in parts) {
                val d = p.data
                when (p.type) {
                    AdParser.SHORT_NAME, AdParser.COMPLETE_NAME -> name = String(d, Charsets.UTF_8).trim()
                    AdParser.MANUFACTURER -> if (d.size >= 2) {
                        manufacturer = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                        if (variety(d, 2) >= 4) payload = maxOf(payload, d.size - 2)
                    }
                    AdParser.SERVICE_DATA_16 -> if (d.size >= 2 && variety(d, 2) >= 4) payload = maxOf(payload, d.size - 2)
                }
            }
            val named = !name.isNullOrEmpty()
            if (named && isDistinctiveName(name!!)) return null
            if (!named && (manufacturer == APPLE || manufacturer == MICROSOFT)) return null
            if (!named && payload < 6) return null
            val sb = StringBuilder(advData.size * 2)
            for (p in parts) {
                if (p.type == AdParser.FLAGS) continue
                sb.append("%02x%02x".format(p.data.size + 1, p.type))
                for (x in p.data) sb.append("%02x".format(x.toInt() and 0xFF))
            }
            return sb.toString()
        }

        private fun variety(d: ByteArray, from: Int): Int {
            val seen = HashSet<Byte>()
            for (i in from until d.size) seen += d[i]
            return seen.size
        }
    }
}
