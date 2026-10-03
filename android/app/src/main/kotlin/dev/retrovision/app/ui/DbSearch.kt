// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.DbHit
import dev.retrovision.core.identity.AdvertisementInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val MAX_HITS = 300
private const val MAX_TIMES = 50_000

/** A stretch of time in which a device was heard without a gap of more than 10 minutes. */
private class Stretch(val fromMs: Long, val toMs: Long, val n: Int, val maxRssi: Int, val lat: Double?, val lon: Double?)

/**
 * Searches everything still stored (up to the retention period), not only the analysis window.
 * A full scan of an encrypted table: it takes seconds to minutes, so it runs only when asked.
 */
@Composable
fun DbSearchDialog(query: String, onClose: () -> Unit) {
    val app = RetrovisionApp.instance
    var hits by remember { mutableStateOf<List<DbHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tookMs by remember { mutableStateOf(0L) }
    var open by remember { mutableStateOf<DbHit?>(null) }
    var report by remember { mutableStateOf<dev.retrovision.core.analysis.EntityReport?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        val t0 = System.currentTimeMillis()
        runCatching {
            withContext(Dispatchers.IO) {
                val cap = q.replaceFirstChar { it.uppercaseChar() }
                app.db.dao().searchAll(
                    like = "%" + q.lowercase().replace("%", "").replace("_", "\\_") + "%",
                    a = q.toByteArray(), b = q.lowercase().toByteArray(), c = cap.toByteArray(),
                    limit = MAX_HITS,
                )
            }
        }.onSuccess { hits = it }.onFailure { error = it.message ?: it.javaClass.simpleName; hits = emptyList() }
        tookMs = System.currentTimeMillis() - t0
    }

    FullScreenDialog(onDismiss = onClose) {
        run {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (open != null) open = null else onClose() }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, Texts.tr("Back", "Indietro"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(Texts.tr("All saved data", "Tutti i dati salvati"), style = MaterialTheme.typography.titleLarge)
                        Text("“$query”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                val h = open
                if (h != null) HitDetail(h, onOpenReport = { report = it })
                else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val list = hits
                    if (list == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            Texts.tr(
                                "Searching every stored sighting (addresses, network names, Bluetooth names). On a large database this takes a while.",
                                "Cerco in tutti gli avvistamenti salvati (indirizzi, nomi di rete, nomi Bluetooth). Con un database grande ci vuole un po'.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            (if (list.size >= MAX_HITS) Texts.tr("First $MAX_HITS devices", "Primi $MAX_HITS dispositivi") else Texts.tr("${list.size} devices", "${list.size} dispositivi")) +
                                " · ${tookMs / 1000.0} s · " + Texts.tr("newest first", "più recenti prima"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.entityId }) { hit ->
                                Panel(onClick = { open = hit }) {
                                    Text(hitLabel(hit), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        (if (hit.radio == 0) "Wi-Fi" else "Bluetooth") + " · ${hit.days} " + Texts.tr("day(s)", "giorni") +
                                            " · ${hit.n} " + Texts.tr("sightings", "rilevazioni") + " · max ${hit.maxRssi} dBm",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        fmt.format(Date(hit.firstMs)) + " → " + fmt.format(Date(hit.lastMs)),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    report?.let { DeviceDialog(it) { report = null } }
}

/** What matched, in words: the Bluetooth name, the network name, or the address. */
private fun hitLabel(h: DbHit): String {
    val addr = h.entityId.substringAfter(':')
    val name = h.hitAdv?.let { runCatching { AdvertisementInfo.of(it).name }.getOrNull() }
    val ssid = h.hitSsid?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }
    return listOfNotNull(name?.let { "“$it”" }, ssid?.let { "📶 $it" }, addr).joinToString(" · ")
}

/** Every stretch in which one device was heard, over all stored data, with where you were. */
@Composable
private fun HitDetail(h: DbHit, onOpenReport: (dev.retrovision.core.analysis.EntityReport) -> Unit) {
    val app = RetrovisionApp.instance
    var stretches by remember(h.entityId) { mutableStateOf<List<Stretch>?>(null) }
    var cut by remember(h.entityId) { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var opening by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(h.entityId) {
        stretches = withContext(Dispatchers.IO) {
            val dao = app.db.dao()
            val times = dao.timesFor(h.entityId, MAX_TIMES)
            cut = times.size >= MAX_TIMES
            val out = ArrayList<Stretch>()
            var i = 0
            while (i < times.size) {
                var j = i
                var best = times[i].rssi
                while (j + 1 < times.size && times[j + 1].timeMs - times[j].timeMs <= 10 * 60_000L) { j++; best = maxOf(best, times[j].rssi) }
                val a = times[i].timeMs; val b = times[j].timeMs
                val mid = (a + b) / 2
                val fix = dao.fixNear(a - 5 * 60_000L, b + 5 * 60_000L, mid)
                out += Stretch(a, b, j - i + 1, best, fix?.lat, fix?.lon)
                i = j + 1
            }
            out.asReversed()
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hitLabel(h), style = MaterialTheme.typography.titleMedium)
        Text(
            "${h.entityId} · ${h.days} " + Texts.tr("day(s)", "giorni") + " · ${h.n} " + Texts.tr("sightings", "rilevazioni"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // In the current analysis window: the full report (evidence, verdict, lookups) is one tap away.
        val live = Collector.analysis.value?.entities?.firstOrNull { it.entityId == h.entityId || h.entityId in it.memberIds }
        if (live != null) OutlinedButton(onClick = { onOpenReport(live) }) { Text(Texts.tr("Open full details", "Apri i dettagli completi")) }
        else if (System.currentTimeMillis() - h.lastMs < app.prefs.lookbackMin * 60_000L) {
            OutlinedButton(enabled = !opening, onClick = {
                val f = Collector.analyzeOne ?: return@OutlinedButton
                opening = true
                scope.launch {
                    val r = runCatching { f(h.entityId) }.getOrNull()
                    opening = false
                    if (r != null) onOpenReport(r)
                }
            }) { Text(Texts.tr("Analyse", "Analizza")) }
        }
        Overline(Texts.tr("When and where", "Quando e dove"))
        val list = stretches
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            if (cut) Text(Texts.tr("Only the first $MAX_TIMES sightings are shown.", "Mostrate solo le prime $MAX_TIMES rilevazioni."), style = MaterialTheme.typography.labelSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.fromMs }) { s ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = s.lat != null) {
                            MapNav.point.value = s.lat!! to s.lon!!
                        }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            fmt.format(Date(s.fromMs)) + "–" + timeFmt.format(Date(s.toMs)) + " · ${s.n}× · max ${s.maxRssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            s.lat?.let { "%.5f, %.5f".format(it, s.lon) + " · " + Texts.tr("tap to show on the map", "tocca per vedere sulla mappa") }
                                ?: Texts.tr("no GPS fix at that time", "nessuna posizione GPS in quel momento"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
