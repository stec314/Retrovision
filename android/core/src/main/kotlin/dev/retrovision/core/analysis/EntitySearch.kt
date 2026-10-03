// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

import java.text.Normalizer

/**
 * Free-text search over analysed devices: names, network names (own, searched for, joined),
 * addresses in any notation (aa:bb:cc, aa-bb-cc, aabbcc, a fragment), vendor and category text
 * the UI passes in. Several words must all match (AND), in any field.
 */
object EntitySearch {
    /** Lower case, no accents, so "citta" finds "Città". */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun compactHex(s: String) = s.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    /** Text fields of one device, already normalised. [extra] = UI text (label, vendor, category). */
    fun haystack(r: EntityReport, extra: List<String> = emptyList()): List<String> = buildList {
        addAll(r.ssids)
        addAll(r.probedSsids)
        r.joinAttempts.forEach { add(it.ssid); add(it.bssid.toString()) }
        r.bleName?.let { add(it) }
        r.droneId?.let { add(it) }
        r.notable.forEach { add(it.name) }
        r.addresses.forEach { add(it.toString()) }
        r.htProfile?.let { add(it) }
        addAll(extra)
    }.map { norm(it) }

    fun matches(r: EntityReport, query: String, extra: List<String> = emptyList()): Boolean {
        val words = norm(query).split(' ', '\t').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = haystack(r, extra)
        val macs = (r.addresses.map { it.toString() } + r.joinAttempts.map { it.bssid.toString() }).map { compactHex(it) }
        return words.all { w ->
            hay.any { it.contains(w) } || run {
                // An address fragment typed without separators, or with other separators.
                val hex = compactHex(w)
                hex.length >= 4 && hex.length == w.count { it.isLetterOrDigit() } && macs.any { it.contains(hex) }
            }
        }
    }

    /**
     * Networks being searched for by name in [entities], with how many devices ask for each:
     * a network many unrelated devices know is a public one; one only a single device knows says
     * more about that device.
     */
    fun searchedNetworks(entities: List<EntityReport>): List<Pair<String, Int>> =
        entities.flatMap { r -> r.probedSsids.map { it to r.entityId } }
            .groupBy({ it.first }, { it.second })
            .map { (ssid, ids) -> ssid to ids.toSet().size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
}
