// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.CatalogRow
import dev.retrovision.app.data.DbHit
import dev.retrovision.app.data.WhoWasThere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The two slots of the comparison, set from the timeline and the routine places. */
object WhoSlots {
    val a = MutableStateFlow<WhoWasThere.Slot?>(null)
    val b = MutableStateFlow<WhoWasThere.Slot?>(null)
}

/** Small "A" / "B" buttons that put a stay or a place into a comparison slot. */
@Composable
fun SlotButtons(make: () -> WhoWasThere.Slot) {
    val a by WhoSlots.a.collectAsState()
    val b by WhoSlots.b.collectAsState()
    Row {
        TextButton(onClick = { WhoSlots.a.value = make() }) { Text(if (a != null && a?.label == make().label) "A ✓" else "A") }
        TextButton(onClick = { WhoSlots.b.value = make() }) { Text(if (b != null && b?.label == make().label) "B ✓" else "B") }
    }
}

private class WhoRow(val hit: WhoWasThere.Hit, val cat: CatalogRow?)

/**
 * Who was there: the devices heard in slot A, in slot B, or in both. A slot is a stay of the
 * timeline, or a routine place over the last days.
 */
@Composable
fun WhoWasThereSection() {
    val app = RetrovisionApp.instance
    val a by WhoSlots.a.collectAsState()
    val b by WhoSlots.b.collectAsState()
    var days by remember { mutableIntStateOf(7) }
    var busy by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<WhoRow>?>(null) }
    var open by remember { mutableStateOf<ArchiveItem?>(null) }
    val scope = rememberCoroutineScope()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun withDays(s: WhoWasThere.Slot?): WhoWasThere.Slot? =
        if (s is WhoWasThere.Slot.Area) WhoWasThere.Slot.Area(s.lat, s.lon, s.radiusM, days, s.label) else s

    fun run(label: String, q: suspend () -> WhoWasThere.Result) {
        busy = true; title = label; rows = null
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val res = q()
                    val top = res.hits.take(300)
                    val cats = top.map { it.entityId }.chunked(400).flatMap { app.db.dao().catalogRows(it) }.associateBy { it.entityId }
                    top.map { WhoRow(it, cats[it.entityId]) }
                }
            }.getOrDefault(emptyList())
            rows = r; busy = false
        }
    }

    Text(
        Texts.tr(
            "Pick A and B with the buttons on a stay (Timeline) or a routine place, then ask who was there. In both: the devices heard at A and at B. Your own devices are left out.",
            "Scegli A e B con i pulsanti di una sosta (Timeline) o di un luogo di routine, poi chiedi chi c'era. In entrambi: i dispositivi sentiti sia in A sia in B. I tuoi dispositivi sono esclusi.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text("A: " + (a?.label ?: "—"), style = MaterialTheme.typography.bodyMedium)
    Text("B: " + (b?.label ?: "—"), style = MaterialTheme.typography.bodyMedium)
    if (a is WhoWasThere.Slot.Area || b is WhoWasThere.Slot.Area) {
        Text(Texts.tr("Places: every time I was there in the last", "Luoghi: ogni volta che c'ero negli ultimi"), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 3, 7, 14, 30).forEach { d -> FilterChip(days == d, { days = d }, label = { Text("$d " + Texts.tr("d", "g")) }) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = a != null && !busy, onClick = { val s = withDays(a)!!; run("A") { WhoWasThere.devices(app.db.dao(), s) } }) { Text(Texts.tr("Who was at A", "Chi c'era in A")) }
        OutlinedButton(enabled = b != null && !busy, onClick = { val s = withDays(b)!!; run("B") { WhoWasThere.devices(app.db.dao(), s) } }) { Text(Texts.tr("At B", "In B")) }
        Button(enabled = a != null && b != null && !busy, onClick = {
            val sa = withDays(a)!!; val sb = withDays(b)!!
            run("A ∩ B") { WhoWasThere.both(app.db.dao(), sa, sb) }
        }) { Text(Texts.tr("In both", "In entrambi")) }
    }
    if (a != null || b != null) TextButton(onClick = { WhoSlots.a.value = null; WhoSlots.b.value = null; rows = null }) { Text(Texts.tr("Clear", "Azzera")) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    rows?.let { list ->
        Overline("$title · ${list.size} " + Texts.tr("devices", "dispositivi"))
        if (list.isEmpty()) Text(Texts.tr("Nothing heard there and then (or no GPS track for that place).", "Niente sentito lì in quel momento (o nessuna traccia GPS per quel luogo)."), style = MaterialTheme.typography.bodySmall)
        list.forEach { w ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    open = w.cat?.let { ArchiveItem.of(it) } ?: ArchiveItem.of(
                        DbHit(w.hit.entityId, if (w.hit.entityId.startsWith("ble")) 1 else 0, w.hit.firstMs, w.hit.lastMs, w.hit.n, w.hit.maxRssi, 0, null, null),
                    )
                }.padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.cat?.let { ArchiveItem.of(it).title } ?: anyLabel(w.hit.entityId), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    if (w.cat != null && (w.cat.flags and (dev.retrovision.app.data.CatalogFlags.ALERTED or dev.retrovision.app.data.CatalogFlags.SUSPICIOUS)) != 0) Text("⚠", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "${w.hit.n}× · max ${w.hit.maxRssi} dBm · " + fmt.format(Date(w.hit.firstMs)) + " → " + fmt.format(Date(w.hit.lastMs)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    open?.let { h -> ArchiveDetailDialog(h) { open = null } }
}
