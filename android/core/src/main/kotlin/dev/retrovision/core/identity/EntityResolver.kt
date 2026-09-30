package dev.retrovision.core.identity

import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * Groups sightings into *entities* (physical devices, as far as we can tell).
 *
 * - Globally administered MACs and BLE addresses: one entity per address.
 * - Randomised Wi-Fi MACs in probe requests: a new MAC is linked to an existing
 *   entity only when ALL of these hold (conservative on purpose: a wrong link
 *   merges two strangers and can create a false "follower"):
 *     1. same IE fingerprint ([WifiFingerprint]);
 *     2. the entity's last probe was ≤ [linkWindowMs] ago;
 *     3. 802.11 sequence number continues: 1 ≤ (seq − lastSeq) mod 4096 ≤ [maxSeqGap];
 *     4. exactly one candidate matches (ambiguity -> no link).
 *   Some OSes reset the sequence counter on rotation; those rotations are
 *   missed, never mis-linked.
 *
 * Not thread-safe. State is in memory; [seed] restores address mappings from storage.
 */
class EntityResolver(
    private val linkWindowMs: Long = 120_000,
    private val maxSeqGap: Int = 64,
    private val forgetAfterMs: Long = 24 * 3600_000L,
) {
    class Resolution(val entityId: String, val linkedToExisting: Boolean)

    private data class Key(val radio: Radio, val mac: MacAddress)

    private class AddrState(val entityId: String, var lastSeenMs: Long)

    private class Trail(val entityId: String, var mac: MacAddress, var lastMs: Long, var lastSeq: Int)

    private val byAddress = HashMap<Key, AddrState>()
    private val trailsByFingerprint = HashMap<String, MutableList<Trail>>()

    var linksMade: Long = 0
        private set

    fun seed(radio: Radio, mac: MacAddress, entityId: String, lastSeenMs: Long) {
        byAddress[Key(radio, mac)] = AddrState(entityId, lastSeenMs)
    }

    fun resolve(s: Sighting, fingerprint: String? = null): Resolution {
        val key = Key(s.radio, s.address)
        val known = byAddress[key]
        val fp = fingerprint ?: s.wifi?.takeIf { it.kind == WifiKind.PROBE_REQ }?.let { WifiFingerprint.of(it.ies) }

        if (known != null) {
            known.lastSeenMs = maxOf(known.lastSeenMs, s.timeMs)
            updateTrail(fp, known.entityId, s)
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
        }
        byAddress[key] = AddrState(entityId, s.timeMs)
        updateTrail(fp, entityId, s)
        return Resolution(entityId, linked)
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
    }

    companion object {
        fun defaultId(radio: Radio, mac: MacAddress): String =
            (if (radio == Radio.WIFI) "wifi:" else "ble:") + mac
    }
}
