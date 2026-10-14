// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.watch

import dev.retrovision.core.identity.AdvertisementInfo
import dev.retrovision.core.identity.BleSignature
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * "Tell me when this comes back" (and "tell me when this is gone"). Pure logic, tested without a
 * phone: the app feeds every sighting through [Watchlist.onSighting] and calls [Watchlist.tick]
 * every few seconds; both return the events to notify.
 *
 * What a rule can follow, from most to least precise:
 *  - [Kind.ADDRESS]: one MAC/BLE address. Reliable only for addresses that do not rotate (cars,
 *    hotspots, routers, tags away from their owner, many wearables).
 *  - [Kind.ENTITY]: what the app already linked into one device (an address and the ones it
 *    rotated to, when the link was found).
 *  - [Kind.SIGNATURE]: a BLE advert signature (local name + coarse shape, never the payload):
 *    survives address rotation, but every device with the same name and shape matches.
 *  - [Kind.NAME]: a text in the Bluetooth name, the network an access point announces, or a
 *    network a phone asks for. Case and accents ignored; `*` matches anything.
 */
object Watchlist {
    enum class Kind { ADDRESS, ENTITY, SIGNATURE, NAME }

    class Rule(
        val id: Long,
        val label: String,
        val kind: Kind,
        /** Address (any notation), entity id, signature, or name pattern. */
        val pattern: String,
        /** Alert when it is heard again after at least [minAbsenceMs] of silence. */
        val onReturn: Boolean = true,
        val minAbsenceMs: Long = 30 * 60_000L,
        /** Only count it as "here" at or above this signal (0 = any). A rough proxy for distance. */
        val minRssi: Int = 0,
        /** Alert when it has not been heard for [absenceMs] after being heard (your own things). */
        val onAbsence: Boolean = false,
        val absenceMs: Long = 5 * 60_000L,
        val enabled: Boolean = true,
        /** When it was last heard, persisted across restarts (0 = never). */
        val lastHeardMs: Long = 0,
    )

    enum class EventKind { RETURNED, FIRST_SEEN, GONE }

    class Event(val rule: Rule, val kind: EventKind, val timeMs: Long, val rssi: Int, val absentMs: Long, val what: String)

    /** Normalised text for name matching: lower case, no accents, trimmed. */
    fun norm(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().trim()

    /** Glob with `*`; without one, a plain "contains". */
    fun nameMatches(pattern: String, text: String): Boolean {
        val p = norm(pattern)
        if (p.isEmpty()) return false
        val t = norm(text)
        if ('*' !in p) return t.contains(p)
        val rx = p.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$rx$").matches(t)
    }

    fun parseAddress(s: String): MacAddress? = runCatching { MacAddress.parse(s.trim()) }.getOrNull()

    /** The names one sighting carries, for [Kind.NAME] rules. */
    fun namesOf(s: Sighting): List<String> {
        val out = ArrayList<String>(2)
        s.ble?.let { b -> runCatching { AdvertisementInfo.of(b.advData).name }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out += it } }
        s.wifi?.let { w ->
            if (w.ssid.isNotEmpty() && (w.kind == WifiKind.BEACON || w.kind == WifiKind.PROBE_RESP || w.kind == WifiKind.PROBE_REQ ||
                    w.kind == WifiKind.ASSOC_REQ || w.kind == WifiKind.REASSOC_REQ)
            ) out += w.ssidText
        }
        return out
    }

    /** Matching state for a set of rules. Not thread-safe: the caller serialises calls. */
    class Engine(rules: List<Rule>) {
        private class State(val rule: Rule) {
            var lastHeardMs = rule.lastHeardMs
            var lastRssi = 0
            var goneSent = false
            /** Heard at least once since the app started (an absence needs a presence first). */
            var heardThisRun = false
        }

        private var states: List<State> = rules.filter { it.enabled }.map { State(it) }
        private var anyName = states.any { it.rule.kind == Kind.NAME }
        private var anySig = states.any { it.rule.kind == Kind.SIGNATURE }
        private var addrs: Map<Long, List<State>> = index()

        private fun index(): Map<Long, List<State>> =
            states.filter { it.rule.kind == Kind.ADDRESS }.mapNotNull { st -> parseAddress(st.rule.pattern)?.let { it.bits to st } }
                .groupBy({ it.first }, { it.second })

        /** Replaces the rules, keeping what is known about the ones that stay. */
        fun update(rules: List<Rule>) {
            val old = states.associateBy { it.rule.id }
            states = rules.filter { it.enabled }.map { r ->
                State(r).also { n ->
                    old[r.id]?.let { o ->
                        n.lastHeardMs = maxOf(o.lastHeardMs, r.lastHeardMs); n.lastRssi = o.lastRssi
                        n.goneSent = o.goneSent; n.heardThisRun = o.heardThisRun
                    }
                }
            }
            anyName = states.any { it.rule.kind == Kind.NAME }
            anySig = states.any { it.rule.kind == Kind.SIGNATURE }
            addrs = index()
        }

        val isEmpty get() = states.isEmpty()

        /** Last time each rule was heard (rule id -> ms), for persisting. */
        fun lastHeard(): Map<Long, Long> = states.associate { it.rule.id to it.lastHeardMs }

        /** Feeds one sighting; returns the "it came back" events it triggers. */
        fun onSighting(s: Sighting, entityId: String): List<Event> {
            if (states.isEmpty()) return emptyList()
            val out = ArrayList<Event>(0)
            val names = if (anyName) namesOf(s) else emptyList()
            val sig = if (anySig) s.ble?.let { BleSignature.of(it.advData) } else null
            fun consider(st: State, what: String) {
                val r = st.rule
                if (r.minRssi != 0 && (s.rssi == 0 || s.rssi < r.minRssi)) return
                val t = s.timeMs
                if (t < st.lastHeardMs) return // late frame from a buffer: never moves time back
                val absent = if (st.lastHeardMs == 0L) Long.MAX_VALUE else t - st.lastHeardMs
                if (r.onReturn && absent >= r.minAbsenceMs) {
                    val kind = if (st.lastHeardMs == 0L) EventKind.FIRST_SEEN else EventKind.RETURNED
                    out.add(Event(r, kind, t, s.rssi, if (st.lastHeardMs == 0L) 0 else absent, what))
                }
                st.lastHeardMs = t
                st.lastRssi = s.rssi
                st.goneSent = false
                st.heardThisRun = true
            }
            addrs[s.address.bits]?.forEach { consider(it, s.address.toString()) }
            for (st in states) {
                when (st.rule.kind) {
                    Kind.ADDRESS -> Unit
                    Kind.ENTITY -> if (st.rule.pattern == entityId) consider(st, entityId)
                    Kind.SIGNATURE -> if (sig != null && sig == st.rule.pattern) consider(st, BleSignature.displayName(sig))
                    Kind.NAME -> names.firstOrNull { nameMatches(st.rule.pattern, it) }?.let { consider(st, it) }
                }
            }
            return out
        }

        /**
         * Absence check: a rule with [Rule.onAbsence] that was heard since the app started and has
         * now been silent for [Rule.absenceMs] fires once, until it is heard again.
         */
        fun tick(nowMs: Long): List<Event> {
            val out = ArrayList<Event>(0)
            for (st in states) {
                val r = st.rule
                if (!r.onAbsence || st.goneSent || !st.heardThisRun) continue
                val silent = nowMs - st.lastHeardMs
                if (silent >= r.absenceMs) {
                    st.goneSent = true
                    out.add(Event(r, EventKind.GONE, nowMs, st.lastRssi, silent, r.label))
                }
            }
            return out
        }
    }
}
