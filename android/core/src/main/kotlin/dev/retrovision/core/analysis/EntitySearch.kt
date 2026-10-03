// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import java.text.Normalizer

/**
 * Free-text search over analysed devices: names, network names (own, searched for, joined),
 * addresses in any notation (aa:bb:cc, aa-bb-cc, aabbcc, a fragment), vendor and category text
 * the UI passes in. Several words must all match (AND), in any field.
 *
 * A busy place yields tens of thousands of devices, so searching goes through an [Index] built
 * once per analysis (off the UI thread): one prepared string per device, then plain `contains`.
 */
object EntitySearch {
    private val MARKS = Regex("\\p{M}+")

    /** Lower case, no accents, so "citta" finds "Città". Fast path for plain ASCII. */
    fun norm(s: String): String {
        if (s.all { it.code < 128 }) return s.lowercase()
        return MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
    }

    private fun compactHex(s: String) = s.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    private fun words(query: String) = norm(query).split(' ', '\t').filter { it.isNotBlank() }

    /** Text fields of one device. [extra] = UI text (label, vendor, category). */
    private fun fields(r: EntityReport, extra: List<String>): List<String> = buildList {
        addAll(r.ssids)
        addAll(r.probedSsids)
        r.joinAttempts.forEach { add(it.ssid); add(it.bssid.toString()) }
        r.bleName?.let { add(it) }
        r.droneId?.let { add(it) }
        r.notable.forEach { add(it.name) }
        r.addresses.forEach { add(it.toString()) }
        r.htProfile?.let { add(it) }
        addAll(extra)
    }

    /** Prepared search text for a list of devices. Build it off the UI thread. */
    class Index(val entities: List<EntityReport>, extra: (EntityReport) -> List<String> = { emptyList() }) {
        private val text = Array(entities.size) { norm(fields(entities[it], extra(entities[it])).joinToString("\n")) }
        private val hex = Array(entities.size) { i ->
            val r = entities[i]
            (r.addresses.map { it.toString() } + r.joinAttempts.map { it.bssid.toString() }).joinToString("|") { compactHex(it) }
        }

        fun search(query: String): List<EntityReport> {
            val ws = words(query)
            if (ws.isEmpty()) return entities
            val hexWords = ws.map { w -> compactHex(w).takeIf { it.length >= 4 && it.length == w.count { c -> c.isLetterOrDigit() } } }
            val out = ArrayList<EntityReport>()
            for (i in entities.indices) {
                var ok = true
                for (k in ws.indices) {
                    val hw = hexWords[k]
                    if (!text[i].contains(ws[k]) && (hw == null || !hex[i].contains(hw))) { ok = false; break }
                }
                if (ok) out += entities[i]
            }
            return out
        }
    }

    fun matches(r: EntityReport, query: String, extra: List<String> = emptyList()): Boolean =
        Index(listOf(r)) { extra }.search(query).isNotEmpty()

    /**
     * Networks being searched for by name in [entities], with how many devices ask for each:
     * a network many unrelated devices know is a public one; one only a single device knows says
     * more about that device.
     */
    fun searchedNetworks(entities: List<EntityReport>): List<Pair<String, Int>> {
        val count = HashMap<String, Int>()
        for (r in entities) for (s in r.probedSsids) count[s] = (count[s] ?: 0) + 1
        return count.entries.map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }
}
