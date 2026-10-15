// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
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
import dev.retrovision.app.data.Catalog
import dev.retrovision.app.data.CatalogCount
import dev.retrovision.app.data.CatalogFlags
import dev.retrovision.app.data.CatalogRow
import dev.retrovision.app.data.DbHit
import dev.retrovision.core.identity.AdvertisementInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val MAX_HITS = 1000
private const val MAX_TIMES = 50_000

/** A stretch of time in which a device was heard without a gap of more than 10 minutes. */
private class Stretch(val fromMs: Long, val toMs: Long, val n: Int, val maxRssi: Int, val lat: Double?, val lon: Double?)

/**
 * Deep search: a full scan of every stored sighting for [query] in raw bytes (address, network
 * names, Bluetooth adverts). Slow on large databases; the catalog ([ArchivePane]) is the fast path,
 * this finds what the analyses never named.
 */
@Composable
fun RawSearchPane(query: String, compact: Boolean, modifier: Modifier = Modifier) {
    val app = RetrovisionApp.instance
    var hits by remember { mutableStateOf<List<DbHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tookMs by remember { mutableStateOf(0L) }
    var open by remember { mutableStateOf<DbHit?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(query) {
        kotlinx.coroutines.delay(400)
        hits = null; error = null
        val q = query.trim()
        val t0 = System.currentTimeMillis()
        runCatching {
            withContext(Dispatchers.IO) {
                val cap = q.replaceFirstChar { it.uppercaseChar() }
                app.db.dao().searchAll(
                    like = if (q.isEmpty()) "%" else "%" + q.lowercase().replace("%", "").replace("_", "\\_") + "%",
                    a = q.toByteArray(), b = q.lowercase().toByteArray(), c = cap.toByteArray(),
                    limit = MAX_HITS,
                )
            }
        }.onSuccess { hits = it }.onFailure { error = it.message ?: it.javaClass.simpleName; hits = emptyList() }
        tookMs = System.currentTimeMillis() - t0
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = hits
        if (list == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                Texts.tr(
                    "Reading the whole archive (${app.prefs.retentionDays} days of sightings). On a large database this takes a while.",
                    "Leggo tutto l'archivio (${app.prefs.retentionDays} giorni di avvistamenti). Con un database grande ci vuole un po'.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                (if (list.size >= MAX_HITS) Texts.tr("Newest $MAX_HITS devices", "I $MAX_HITS dispositivi più recenti") else Texts.tr("${list.size} devices", "${list.size} dispositivi")) +
                    " · ${"%.1f".format(tookMs / 1000.0)} s" + if (list.size >= MAX_HITS) Texts.tr(" · search to narrow down", " · cerca per restringere") else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
                items(list, key = { it.entityId }) { hit ->
                    if (compact) {
                        Row(
                            Modifier.fillMaxWidth().clickable { open = hit }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(if (hit.radio == 0) "📶" else "ᛒ", modifier = Modifier.padding(start = 4.dp))
                            Text(hitLabel(hit), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${hit.days} " + Texts.tr("d", "g"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
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
    open?.let { h -> ArchiveDetailDialog(ArchiveItem.of(h)) { open = null } }
}

/** One archived device: every stretch it was heard, with where you were. */
@Composable
internal fun ArchiveDetailDialog(h: ArchiveItem, onClose: () -> Unit) {
    var report by remember { mutableStateOf<dev.retrovision.core.analysis.EntityReport?>(null) }
    FullScreenDialog(onDismiss = onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClose)
                Text(Texts.tr("Archive", "Archivio"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            HitDetail(h, onOpenReport = { report = it })
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
private fun HitDetail(h: ArchiveItem, onOpenReport: (dev.retrovision.core.analysis.EntityReport) -> Unit) {
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
            val times = if (h.ids.size <= 1) dao.timesFor(h.entityId, MAX_TIMES) else dao.timesForAny(h.ids, MAX_TIMES)
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
        var watching by remember { mutableStateOf(false) }
        Text(h.title, style = MaterialTheme.typography.titleMedium)
        h.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (h.targets.isNotEmpty()) OutlinedButton(onClick = { watching = true }) { Text(Texts.tr("Tell me when it comes back", "Avvisami quando torna")) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val pdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val list = stretches ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val live = Collector.analysis.value?.entities?.firstOrNull { it.entityId == h.entityId || it.memberIds.any { m -> m in h.ids } }
                val signal = runCatching { dev.retrovision.app.data.ChartsData.signal(app.db.dao(), h.ids, h.lastMs - 6 * 3_600_000L, h.lastMs + 5 * 60_000L) }.getOrNull()
                runCatching {
                    dev.retrovision.app.data.EvidencePdf.write(
                        ctx, uri,
                        dev.retrovision.app.data.EvidencePdf.Input(
                            h.title, h.ids, h.lines, live?.reasons?.map { Texts.reason(it) }.orEmpty(),
                            list.map { dev.retrovision.app.data.EvidencePdf.Stretch(it.fromMs, it.toMs, it.n, it.maxRssi, it.lat, it.lon) },
                            signal,
                        ),
                        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
                    )
                }.onFailure { dev.retrovision.app.Diag.e("pdf", "evidence export failed", it) }
            }
        }
        OutlinedButton(enabled = stretches != null, onClick = {
            val day = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(Date())
            pdf.launch("retrovision-evidence-$day.pdf")
        }) { Text(Texts.tr("Evidence file (PDF)", "Fascicolo di prova (PDF)")) }
        var kept by remember(h.entityId) { mutableStateOf(false) }
        OutlinedButton(enabled = !kept, onClick = {
            kept = true
            scope.launch {
                val dao = app.db.dao()
                val now = System.currentTimeMillis()
                for (id in h.ids) {
                    dao.addFeedback(dev.retrovision.app.data.FeedbackRow(entityId = id, label = 2, score = 0.0, reasons = "archive", timeMs = now))
                    Catalog.markSuspicious(dao, id)
                }
                Catalog.keep(dao, h.ids, h.title, app.prefs.pinKeepDays)
            }
        }) { Text(if (kept) Texts.tr("Suspicious · kept ✓", "Sospetto · conservato ✓") else Texts.tr("Suspicious: keep it", "Sospetto: conservalo")) }
        if (watching) WatchDialog(h.title, h.targets, h.lastMs) { watching = false }
        // In the current analysis window: the full report (evidence, verdict, lookups) is one tap away.
        val live = Collector.analysis.value?.entities?.firstOrNull { it.entityId == h.entityId || h.entityId in it.memberIds || it.memberIds.any { m -> m in h.ids } }
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
        Expandable(
            Texts.tr("Charts", "Grafici"),
            summary = Texts.tr("Signal and your movement · when it is around", "Segnale e tuoi spostamenti · quando c'è"),
        ) { DeviceChartsSection(h.ids, h.lastMs) }
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

/** One device as the archive detail shows it, from the catalog or from a deep search hit. */
internal class ArchiveItem(
    val entityId: String,
    /** Entity ids its sightings are stored under. */
    val ids: List<String>,
    val title: String,
    val lines: List<String>,
    val lastMs: Long,
    /** Ways to watch it ("tell me when it comes back"); empty = not offered. */
    val targets: List<WatchTarget>,
) {
    companion object {
        fun of(h: DbHit) = ArchiveItem(
            h.entityId, listOf(h.entityId), hitLabel(h),
            listOf("${h.entityId} · ${h.days} " + Texts.tr("day(s)", "giorni") + " · ${h.n} " + Texts.tr("sightings", "rilevazioni")),
            h.lastMs,
            watchTargets(h.entityId, listOf(h.entityId.substringAfter(':')), stable = false, linked = false, signature = null, name = null),
        )

        fun of(c: CatalogRow): ArchiveItem {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val addrs = c.addresses.split('\n').filter { it.isNotBlank() }
            val members = c.members.split('\n').filter { it.isNotBlank() }.ifEmpty { listOf(c.entityId) }
            val ssid = c.ssids.substringBefore('\n').takeIf { c.kind == "WIFI_AP" && it.isNotBlank() }
            return ArchiveItem(
                c.entityId, (members + c.entityId).distinct(), catalogTitle(c),
                listOfNotNull(
                    catalogCategory(c) + (if (c.vendor.isNotBlank()) " · " + c.vendor else "") + flagText(c.flags),
                    "${c.days} " + Texts.tr("day(s)", "giorni") + " · max ${c.maxRssi} dBm" +
                        (if (c.maxPlaces > 0) " · " + Texts.tr("places ", "luoghi ") + c.maxPlaces else "") +
                        (if (c.maxScore >= 0.3) " · score %.2f".format(c.maxScore) else ""),
                    fmt.format(Date(c.firstMs)) + " → " + fmt.format(Date(c.lastMs)),
                    addrs.joinToString(" · ").takeIf { it.isNotBlank() },
                    c.probed.takeIf { it.isNotBlank() }?.let { Texts.tr("Asks for: ", "Cerca: ") + it.replace("\n", ", ") },
                    c.together.takeIf { it.isNotBlank() }?.let { t ->
                        "👥 " + Texts.tr("Moved together with: ", "Si è mosso insieme a: ") + t.split('\n').joinToString(", ") { anyLabel(it) }
                    },
                ),
                c.lastMs,
                watchTargets(c.entityId, addrs, c.macTrust == "STABLE", addrs.size > 1 || members.size > 1, c.signature.ifBlank { null }, c.bleName.ifBlank { null } ?: ssid),
            )
        }
    }
}

private fun catalogCategory(c: CatalogRow): String =
    runCatching { dev.retrovision.core.identity.DeviceCategory.valueOf(c.category) }.getOrNull()?.let { CategoryUi.icon(it) + " " + CategoryUi.label(it) } ?: c.category

private fun catalogTitle(c: CatalogRow): String {
    val addr = c.addresses.substringBefore('\n')
    val name = when {
        c.bleName.isNotBlank() -> "“${c.bleName}”"
        c.kind == "WIFI_AP" -> c.ssids.substringBefore('\n').ifBlank { Texts.tr("(hidden network)", "(rete nascosta)") }
        c.kind == "WIFI_CLIENT" -> Texts.tr("Wi-Fi device", "Dispositivo Wi-Fi")
        else -> Texts.tr("Bluetooth device", "Dispositivo Bluetooth")
    }
    return "$name · $addr"
}

private fun flagText(f: Int): String = buildString {
    if ((f and CatalogFlags.ALERTED) != 0) append(" · ⚠ " + Texts.tr("alerted", "ha dato allerta"))
    if ((f and CatalogFlags.SUSPICIOUS) != 0) append(" · " + Texts.tr("suspicious", "sospetto"))
    if ((f and CatalogFlags.NOTABLE) != 0) append(" · 👁")
    if ((f and CatalogFlags.TRACKER) != 0) append(" · 🏷")
    if ((f and CatalogFlags.DRONE) != 0) append(" · 🛸")
    if ((f and CatalogFlags.GROUPED) != 0) append(" · 👥")
    if ((f and CatalogFlags.KNOWS_MY_NETWORK) != 0) append(" · 🏠")
}

/** Archive filter chips: the live window's, plus two that only make sense over time. */
internal enum class ArchiveFilter(val emoji: String, val en: String, val itText: String, val cats: List<String> = emptyList(), val flags: Int = 0, val unreliable: Boolean = false) {
    ALL("", "All", "Tutti"),
    ALERTED("⚠", "Alerted", "Hanno dato allerta", flags = CatalogFlags.ALERTED),
    SUSPICIOUS("❗", "Marked suspicious", "Segnati sospetti", flags = CatalogFlags.SUSPICIOUS),
    SEARCHING("🔍", "Looking for a network", "Cercano una rete", flags = CatalogFlags.SEARCHING),
    TRACKERS("🏷", "Trackers", "Tracker", flags = CatalogFlags.TRACKER),
    DRONES("🛸", "Drones", "Droni", flags = CatalogFlags.DRONE),
    NOTABLE("👁", "Notable", "Notevoli", flags = CatalogFlags.NOTABLE),
    PHONES("📱", "Phones & PCs", "Telefoni e PC", listOf("PHONE", "COMPUTER", "WIFI_CLIENT")),
    NETWORKS("📶", "Networks & hotspots", "Reti e hotspot", listOf("ROUTER", "HOTSPOT", "WIFI_DIRECT")),
    VEHICLES("🚗", "Vehicles & cameras", "Veicoli e camere", listOf("VEHICLE", "CAMERA")),
    WEARABLES("⌚", "Wearables & audio", "Indossabili e audio", listOf("WATCH", "AUDIO", "HEALTH")),
    OTHER("ᛒ", "Other Bluetooth", "Altro Bluetooth", listOf("TV", "INPUT", "HOME", "BEACON", "BLE_OTHER")),
    GROUPED("👥", "Moving together", "Si muovono insieme", flags = CatalogFlags.GROUPED),
    KNOWS_NET("🏠", "Asked for my network", "Hanno cercato la mia rete", flags = CatalogFlags.KNOWS_MY_NETWORK),
    LINKED("🔗", "Linked addresses", "Indirizzi collegati", flags = CatalogFlags.LINKED),
    RANDOM_MAC("⚠", "Unreliable MAC", "MAC non affidabile", unreliable = true),
    ;

    val label: String get() = Texts.tr(en, itText)

    fun matches(c: CatalogCount): Boolean =
        (cats.isEmpty() || c.category in cats) && (c.flags and flags) == flags && (!unreliable || c.macTrust != "STABLE")
}

private const val PAGE = 200

/**
 * The archive: every device stored (up to the retention period), from the catalog. Listed,
 * filtered and counted like the live window; a deep search over raw sightings is one tap away for
 * what the analyses never named.
 */
@Composable
fun ArchivePane(query: String, compact: Boolean, modifier: Modifier = Modifier) {
    val app = RetrovisionApp.instance
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ArchiveFilter.ALL) }
    var limit by remember(query, filter) { mutableStateOf(if (filter == ArchiveFilter.GROUPED) 5000 else PAGE) }
    var rows by remember { mutableStateOf<List<CatalogRow>?>(null) }
    var counts by remember { mutableStateOf<Map<ArchiveFilter, Int>>(emptyMap()) }
    var size by remember { mutableStateOf(0) }
    var raw by remember(query) { mutableStateOf(false) }
    var open by remember { mutableStateOf<ArchiveItem?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val filledUntil = app.prefs.catalogFilledUntil

    LaunchedEffect(query, filter, limit) {
        kotlinx.coroutines.delay(250)
        val q = Catalog.likeQuery(query)
        runCatching {
            withContext(Dispatchers.IO) {
                val dao = app.db.dao()
                val page = dao.catalogPage(
                    anyCat = if (filter.cats.isEmpty()) 1 else 0, cats = filter.cats, flagsAll = filter.flags,
                    unreliable = if (filter.unreliable) 1 else 0, q = q, limit = limit, offset = 0,
                )
                val c = dao.catalogCounts(q)
                Triple(page, ArchiveFilter.entries.associateWith { f -> c.filter { f.matches(it) }.sumOf { it.n } }, dao.catalogSize())
            }
        }.onSuccess { (p, c, n) -> rows = p; counts = c; size = n }.onFailure { rows = emptyList() }
    }

    if (raw) {
        Column(modifier) {
            TextButton(onClick = { raw = false }) { Text(Texts.tr("‹ Back to the catalog", "‹ Torna al catalogo")) }
            RawSearchPane(query, compact, Modifier.fillMaxSize())
        }
        return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArchiveFilter.entries.forEach { f ->
                val n = counts[f] ?: 0
                if (n == 0 && f != ArchiveFilter.ALL && f != filter) return@forEach
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text((if (f.emoji.isNotEmpty()) f.emoji + " " else "") + f.label + " $n") })
            }
        }
        val list = rows
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            val total = counts[filter] ?: list.size
            Text(
                Texts.tr("$total of $size devices in ${app.prefs.retentionDays} days", "$total di $size dispositivi in ${app.prefs.retentionDays} giorni") +
                    if (filledUntil == 0L || System.currentTimeMillis() - filledUntil > app.prefs.lookbackMin * 60_000L + 3_600_000L) Texts.tr(" · still cataloguing older data", " · sto ancora catalogando i dati più vecchi") else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
                if (filter == ArchiveFilter.GROUPED) {
                    val groups = archiveGroups(list)
                    item(key = "groups-h") {
                        Text(
                            Texts.tr("${groups.size} groups, kept across days.", "${groups.size} gruppi, conservati nei giorni."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(groups, key = { "g" + it.key }) { g ->
                        GroupCard(g) { id -> list.firstOrNull { it.entityId == id }?.let { open = ArchiveItem.of(it) } }
                    }
                } else items(list, key = { it.entityId }) { c ->
                    val cat = runCatching { dev.retrovision.core.identity.DeviceCategory.valueOf(c.category) }.getOrNull()
                    if (compact) {
                        Row(
                            Modifier.fillMaxWidth().clickable { open = ArchiveItem.of(c) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(cat?.let { CategoryUi.icon(it) } ?: "•", modifier = Modifier.padding(start = 4.dp))
                            Text(catalogTitle(c), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if ((c.flags and (CatalogFlags.ALERTED or CatalogFlags.SUSPICIOUS)) != 0) Text("⚠", color = MaterialTheme.colorScheme.error)
                            Text("${c.days} " + Texts.tr("d", "g"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Panel(onClick = { open = ArchiveItem.of(c) }) {
                            Text(catalogTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                catalogCategory(c) + (if (c.vendor.isNotBlank()) " · " + c.vendor else "") + flagText(c.flags),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            )
                            Text(
                                "${c.days} " + Texts.tr("day(s)", "giorni") + " · max ${c.maxRssi} dBm · " + fmt.format(Date(c.firstMs)) + " → " + fmt.format(Date(c.lastMs)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (list.size >= limit) item(key = "more") {
                    OutlinedButton(onClick = { limit += PAGE }, modifier = Modifier.fillMaxWidth()) { Text(Texts.tr("Show more", "Mostra altri")) }
                }
                if (query.isNotBlank()) item(key = "raw") {
                    TextButton(onClick = { raw = true }) {
                        Text(Texts.tr("Search the raw sightings too (slow)", "Cerca anche negli avvistamenti grezzi (lento)"))
                    }
                }
            }
        }
    }
    open?.let { h -> ArchiveDetailDialog(h) { open = null } }
}
