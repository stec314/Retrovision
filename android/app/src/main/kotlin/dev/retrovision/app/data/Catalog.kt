// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import dev.retrovision.app.enrich.Vendors
import dev.retrovision.core.analysis.AnalysisResult
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.analysis.EntityStub
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.model.MacAddress

/**
 * Keeps the device catalog ([CatalogRow]) up to date from the analyses: every device the app has
 * analysed, with what it is (category, flags, names, vendor) and when it was heard, so the archive
 * lists, filters and counts in milliseconds instead of scanning every stored sighting.
 */
object Catalog {
    /** What one analysis says about one device, from a full report or a trimmed stub. */
    private class Snap(
        val entityId: String,
        val kind: String,
        val category: DeviceCategory,
        val macTrust: String,
        val addresses: List<MacAddress>,
        val ssids: List<String>,
        val probed: List<String>,
        val bleName: String?,
        val companyId: Int?,
        val members: Collection<String>,
        val firstMs: Long,
        val lastMs: Long,
        val maxRssi: Int,
        val score: Double,
        val places: Int,
        val flags: Int,
        val signature: String?,
    )

    private fun snap(r: EntityReport): Snap {
        var f = 0
        if (r.category == DeviceCategory.TRACKER || r.tracker != null) f = f or CatalogFlags.TRACKER
        if (r.isDrone || r.category == DeviceCategory.DRONE) f = f or CatalogFlags.DRONE
        if (r.notable.any { it.kind.attention }) f = f or CatalogFlags.NOTABLE
        if (r.addresses.size > 1) f = f or CatalogFlags.LINKED
        if (r.alert) f = f or CatalogFlags.ALERTED
        if (r.probedSsids.isNotEmpty() || r.joinAttempts.isNotEmpty()) f = f or CatalogFlags.SEARCHING
        return Snap(
            r.entityId, r.kind.name, r.category, r.macTrust.name, r.addresses.toList(), r.ssids.toList(), r.probedSsids.toList(),
            r.bleName ?: r.model, r.bleCompanyId, r.memberIds, r.firstSeenMs, r.lastSeenMs, r.maxRssi, r.score, r.placeIds.size, f, r.bleSignature,
        )
    }

    private fun snap(s: EntityStub): Snap {
        var f = 0
        if (s.category == DeviceCategory.TRACKER) f = f or CatalogFlags.TRACKER
        if (s.category == DeviceCategory.DRONE) f = f or CatalogFlags.DRONE
        if (s.addresses.size > 1) f = f or CatalogFlags.LINKED
        if (s.probedSsids.isNotEmpty()) f = f or CatalogFlags.SEARCHING
        return Snap(
            s.entityId, s.kind.name, s.category, s.macTrust.name, s.addresses, s.ssids, s.probedSsids, s.bleName, s.bleCompanyId,
            listOf(s.entityId), s.firstSeenMs, s.lastSeenMs, s.maxRssi, s.score, s.places, f, null,
        )
    }

    /** Local calendar day (days since the epoch, phone time zone). */
    fun localDay(ms: Long): Long = (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86_400_000L

    private fun lines(xs: Collection<String>, max: Int): String = xs.asSequence().filter { it.isNotBlank() }.distinct().take(max).joinToString("\n")

    private fun mergeLines(old: String, new: Collection<String>, max: Int): String =
        lines(new + old.split('\n'), max)

    private fun merge(old: CatalogRow?, s: Snap, suspicious: Boolean): CatalogRow {
        val vendor = (s.companyId?.let { Vendors.forCompany(it) } ?: s.addresses.firstOrNull()?.let { Vendors.forMac(it) }).orEmpty()
        val day = localDay(s.lastMs)
        val firstDay = localDay(s.firstMs)
        val days = when {
            old == null -> if (day != firstDay) 2 else 1
            day > old.lastDay -> old.days + 1
            else -> old.days
        }
        val addresses = mergeLines(old?.addresses.orEmpty(), s.addresses.map { it.toString() }, 8)
        val ssids = mergeLines(old?.ssids.orEmpty(), s.ssids, 6)
        val probed = mergeLines(old?.probed.orEmpty(), s.probed, 16)
        val bleName = s.bleName?.takeIf { it.isNotBlank() } ?: old?.bleName.orEmpty()
        val v = vendor.ifEmpty { old?.vendor.orEmpty() }
        var flags = (old?.flags ?: 0) or s.flags
        if (suspicious) flags = flags or CatalogFlags.SUSPICIOUS
        // A full report knows the category best; a stub (trimmed device) does not downgrade it.
        val category = if (old != null && s.category == DeviceCategory.BLE_OTHER) old.category else s.category.name
        val search = buildString {
            append(s.entityId).append(' ').append(bleName).append(' ').append(v).append(' ')
            append(ssids.replace('\n', ' ')).append(' ').append(probed.replace('\n', ' ')).append(' ')
            for (a in addresses.split('\n')) append(a).append(' ').append(a.replace(":", "")).append(' ')
            append(category).append(' ').append(s.kind)
        }.lowercase()
        return CatalogRow(
            entityId = s.entityId, kind = s.kind, category = category, macTrust = s.macTrust, flags = flags,
            bleName = bleName, ssids = ssids, probed = probed, addresses = addresses,
            members = mergeLines(old?.members.orEmpty(), s.members, 32), vendor = v, search = search,
            firstMs = minOf(old?.firstMs ?: s.firstMs, s.firstMs), lastMs = maxOf(old?.lastMs ?: 0L, s.lastMs),
            days = days, lastDay = maxOf(old?.lastDay ?: 0L, day), maxRssi = maxOf(old?.maxRssi ?: -127, s.maxRssi),
            maxScore = maxOf(old?.maxScore ?: 0.0, s.score), maxPlaces = maxOf(old?.maxPlaces ?: 0, s.places),
            signature = s.signature ?: old?.signature.orEmpty(),
        )
    }

    /**
     * Records every device of [result] heard at or after [sinceMs] (devices not heard since the
     * last update have nothing new to say). Returns the number of rows written.
     */
    suspend fun update(dao: AppDao, result: AnalysisResult, sinceMs: Long, suspicious: Set<String>): Int {
        val snaps = ArrayList<Snap>()
        for (r in result.entities) if (r.lastSeenMs >= sinceMs) snaps += snap(r)
        for (s in result.others) if (s.lastSeenMs >= sinceMs) snaps += snap(s)
        var n = 0
        for (chunk in snaps.chunked(400)) {
            val old = dao.catalogRows(chunk.map { it.entityId }).associateBy { it.entityId }
            val rows = chunk.map { s -> merge(old[s.entityId], s, s.entityId in suspicious || s.members.any { it in suspicious }) }
            dao.putCatalog(rows)
            n += rows.size
        }
        return n
    }

    /** Marks a device suspicious in the catalog right away (your verdict), if it is there. */
    suspend fun markSuspicious(dao: AppDao, entityId: String) {
        val r = dao.catalogRow(entityId) ?: return
        dao.putCatalog(listOf(r.copyFlags(r.flags or CatalogFlags.SUSPICIOUS)))
    }

    private fun CatalogRow.copyFlags(f: Int) = CatalogRow(
        entityId, kind, category, macTrust, f, bleName, ssids, probed, addresses, members, vendor, search,
        firstMs, lastMs, days, lastDay, maxRssi, maxScore, maxPlaces, signature,
    )

    /** Search text as the catalog stores it: lower case; addresses also without separators. */
    fun likeQuery(q: String): String {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return "%"
        // "AA-BB-CC" / "aa:bb:cc" / "aabbcc": match the address in any notation.
        val hexOnly = t.matches(Regex("[0-9a-f:\\-. ]+")) && t.count { it.isLetterOrDigit() } >= 4
        val core = if (hexOnly) t.filter { it.isLetterOrDigit() } else t
        return "%" + core.replace("\\", "").replace("%", "").replace("_", "") + "%"
    }
}
