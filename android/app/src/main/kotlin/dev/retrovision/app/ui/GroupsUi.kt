// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.data.CatalogFlags
import dev.retrovision.app.data.CatalogRow
import dev.retrovision.core.analysis.AnalysisResult
import java.text.DateFormat
import java.util.Date

/** One member of a group, as a row of its card. */
class GroupMemberUi(val id: String, val icon: String, val label: String, val detail: String, val flagged: Boolean)

/** One group of devices that move together. [sharedPlaces] is 0 when unknown (archive). */
class GroupUi(val key: String, val members: List<GroupMemberUi>, val sharedPlaces: Int, val firstMs: Long, val lastMs: Long) {
    val flagged get() = members.any { it.flagged }
}

/** Groups of the current analysis, flagged ones first. */
fun liveGroups(a: AnalysisResult?): List<GroupUi> {
    a ?: return emptyList()
    val reports = a.entities.associateBy { it.entityId }
    val stubs = a.others.associateBy { it.entityId }
    return a.coTravel.map { g ->
        GroupUi(
            g.id,
            g.members.map { id ->
                val r = reports[id]
                val st = stubs[id]
                val cat = r?.category ?: st?.category
                val vendor = (r?.bleCompanyId ?: st?.bleCompanyId)?.let { dev.retrovision.app.enrich.Vendors.forCompany(it) }
                    ?: (r?.addresses?.firstOrNull() ?: st?.addresses?.firstOrNull())?.let { dev.retrovision.app.enrich.Vendors.forMac(it) }
                GroupMemberUi(
                    id, cat?.let { CategoryUi.icon(it) } ?: "•", anyLabel(id),
                    listOfNotNull(cat?.let { CategoryUi.label(it) }, vendor).joinToString(" · "),
                    r?.alert == true,
                )
            },
            g.sharedPlaces, g.firstMs, g.lastMs,
        )
    }.sortedWith(compareByDescending<GroupUi> { it.flagged }.thenByDescending { it.sharedPlaces }.thenByDescending { it.members.size })
}

/** Groups rebuilt from the archive: every device marked as moving with others, joined by who it moved with. */
fun archiveGroups(rows: List<CatalogRow>): List<GroupUi> {
    val byId = rows.associateBy { it.entityId }
    val parent = HashMap<String, String>()
    fun find(x: String): String { var y = x; while (parent[y] != null && parent[y] != y) y = parent[y]!!; return y }
    fun union(a: String, b: String) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
    for (r in rows) {
        parent.putIfAbsent(r.entityId, r.entityId)
        for (o in r.together.split('\n')) if (o.isNotBlank()) { parent.putIfAbsent(o, o); union(r.entityId, o) }
    }
    return parent.keys.groupBy { find(it) }.values.filter { it.size >= 2 }.map { ids ->
        val members = ids.sorted().map { id ->
            val c = byId[id]
            if (c == null) GroupMemberUi(id, "•", id, Texts.tr("not in the archive any more", "non più in archivio"), false)
            else {
                val cat = runCatching { dev.retrovision.core.identity.DeviceCategory.valueOf(c.category) }.getOrNull()
                GroupMemberUi(
                    id, cat?.let { CategoryUi.icon(it) } ?: "•", ArchiveItem.of(c).title,
                    listOfNotNull(cat?.let { CategoryUi.label(it) }, c.vendor.ifBlank { null }, "${c.days} " + Texts.tr("d", "g")).joinToString(" · "),
                    (c.flags and (CatalogFlags.ALERTED or CatalogFlags.SUSPICIOUS)) != 0,
                )
            }
        }
        val known = ids.mapNotNull { byId[it] }
        GroupUi(ids.min(), members, 0, known.minOfOrNull { it.firstMs } ?: 0L, known.maxOfOrNull { it.lastMs } ?: 0L)
    }.sortedWith(compareByDescending<GroupUi> { it.flagged }.thenByDescending { it.lastMs })
}

/** One group as a card: summary line, then one tappable row per member. */
@Composable
fun GroupCard(g: GroupUi, onOpen: (String) -> Unit) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Panel(tint = if (g.flagged) MaterialTheme.colorScheme.errorContainer else null) {
        Text(
            (if (g.flagged) "⚠ " else "👥 ") + Texts.tr("${g.members.size} devices together", "${g.members.size} dispositivi insieme"),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            listOfNotNull(
                if (g.sharedPlaces > 0) Texts.tr("${g.sharedPlaces} places at the same times", "${g.sharedPlaces} luoghi negli stessi momenti") else null,
                if (g.firstMs > 0) fmt.format(Date(g.firstMs)) + " → " + fmt.format(Date(g.lastMs)) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        g.members.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(m.icon)
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (m.detail.isNotBlank()) Text(m.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (m.flagged) Text("⚠", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
