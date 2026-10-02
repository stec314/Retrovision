// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * Decoded facts about one advertisement / frame payload, computed once.
 *
 * Every analysis cycle (once a minute) re-reads the whole window, and the same few thousand
 * payloads repeat over and over (a device sends the same advert every second). Parsing AD
 * structures, matching trackers, Remote ID and ~300 notable rules for each copy is pure waste
 * on a battery. Keyed by the exact bytes (not a hash), so a collision can never mix up devices.
 */
class Facts(
    val info: AdvertisementInfo?,
    val tracker: TrackerMatch?,
    val remoteId: RemoteId.Data?,
    val notable: List<NotableSignature>,
)

object FactsCache {
    private const val MAX = 30_000

    private class Key(val radio: Radio, val address: Long, val kind: Int, val a: ByteArray, val b: ByteArray) {
        private val h = ((radio.ordinal * 31 + address.hashCode()) * 31 + kind) * 31 + a.contentHashCode() * 17 + b.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) = other is Key && other.h == h && other.radio == radio &&
            other.address == address && other.kind == kind && other.a.contentEquals(a) && other.b.contentEquals(b)
    }

    private val map = object : LinkedHashMap<Key, Facts>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Facts>?) = size > MAX
    }

    var hits = 0L
        private set
    var misses = 0L
        private set

    private val EMPTY = ByteArray(0)

    fun of(s: Sighting): Facts {
        val w = s.wifi
        val b = s.ble
        val key = Key(
            s.radio, s.address.bits, w?.kind?.ordinal ?: b?.advType ?: 0,
            b?.advData ?: w?.ies ?: EMPTY, w?.ssid ?: EMPTY,
        )
        synchronized(map) {
            map[key]?.let { hits++; return it }
        }
        misses++
        val info = b?.let { AdvertisementInfo.of(it.advData) }
        val facts = Facts(
            info = info,
            tracker = info?.let { TrackerClassifier.classify(it) },
            remoteId = if (b != null || w?.kind == WifiKind.BEACON || w?.kind == WifiKind.PROBE_RESP) RemoteId.decode(s) else null,
            notable = NotableCatalog.match(s),
        )
        synchronized(map) { map[key] = facts }
        return facts
    }
}
