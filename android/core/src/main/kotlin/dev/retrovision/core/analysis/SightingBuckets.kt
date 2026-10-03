// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting

/**
 * Collapses repeats: one slot per (entity, radio, frame kind or advert type, SSID) per time bucket.
 * The newest sighting of a slot is kept, and `mergedCount` is the sum of all of them, so frame
 * counts stay right. A phone that advertises every second says the same thing 60 times a minute;
 * only the slot matters for following, places and timing.
 *
 * Used twice: as the in-memory analysis window (no database scan per run) and before storage
 * (the database keeps one row per slot instead of one per frame). Thread-safe.
 *
 * @param maxSlots beyond this the oldest slots are dropped first, so memory stays bounded.
 */
class SightingBuckets(val bucketMs: Long, private val maxSlots: Int = Int.MAX_VALUE) {
    private data class Key(val entityId: String, val radio: Int, val kind: Int, val ssid: String, val bucket: Long)

    private class Slot(var latest: EntitySighting, var merged: Int, var raw: Int)

    // Insertion order ≈ time order for live data; [snapshot] still sorts, so late or warm-up rows are fine.
    private val map = LinkedHashMap<Key, Slot>()
    private var evicted = 0L

    /** Slots dropped because [maxSlots] was reached (the window lost its oldest part). */
    val evictedSlots: Long get() = synchronized(map) { evicted }

    val size: Int get() = synchronized(map) { map.size }

    private fun keyOf(es: EntitySighting): Key {
        val s = es.sighting
        val w = s.wifi
        val kind = w?.kind?.ordinal ?: (100 + (s.ble?.advType ?: 0))
        val ssid = if (w != null && w.ssid.isNotEmpty()) String(w.ssid, Charsets.ISO_8859_1) else ""
        return Key(es.entityId, if (s.radio == Radio.WIFI) 0 else 1, kind, ssid, Math.floorDiv(s.timeMs, bucketMs))
    }

    fun add(es: EntitySighting) {
        val k = keyOf(es)
        val n = maxOf(1, es.sighting.mergedCount)
        synchronized(map) {
            val slot = map[k]
            if (slot == null) {
                map[k] = Slot(es, n, 1)
                if (map.size > maxSlots) {
                    val it = map.entries.iterator()
                    it.next(); it.remove()
                    evicted++
                }
            } else {
                if (es.sighting.timeMs >= slot.latest.sighting.timeMs) slot.latest = es
                slot.merged += n
                slot.raw++
            }
        }
    }

    /** Frames (not slots) added and still held. */
    fun rawCount(): Long = synchronized(map) { map.values.sumOf { it.raw.toLong() } }

    /** Drops slots whose bucket ended before [fromMs]. */
    fun prune(fromMs: Long) {
        val first = Math.floorDiv(fromMs, bucketMs)
        synchronized(map) { map.keys.removeAll { it.bucket < first } }
    }

    /** Everything held from [fromMs] on, oldest first, each slot as one sighting with summed counts. */
    fun snapshot(fromMs: Long): List<EntitySighting> {
        prune(fromMs)
        val out = synchronized(map) { map.values.map { merge(it) } }
        return out.sortedBy { it.sighting.timeMs }
    }

    /**
     * Removes and returns the slots whose bucket closed before [nowMs] (for storage): nothing more
     * can be added to them by live data, so their counts are final.
     */
    fun drainClosed(nowMs: Long): List<EntitySighting> {
        val open = Math.floorDiv(nowMs, bucketMs)
        val out = ArrayList<EntitySighting>()
        synchronized(map) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.bucket < open) { out += merge(e.value); it.remove() }
            }
        }
        return out
    }

    /** Removes and returns everything (on shutdown). */
    fun drainAll(): List<EntitySighting> = synchronized(map) {
        val out = map.values.map { merge(it) }
        map.clear()
        out
    }

    fun clear() = synchronized(map) { map.clear() }

    private fun merge(slot: Slot): EntitySighting {
        val s = slot.latest.sighting
        if (slot.merged == maxOf(1, s.mergedCount)) return slot.latest
        return EntitySighting(slot.latest.entityId, Sighting(s.timeMs, s.radio, s.address, s.rssi, slot.merged, s.wifi, s.ble, s.probeId))
    }
}
