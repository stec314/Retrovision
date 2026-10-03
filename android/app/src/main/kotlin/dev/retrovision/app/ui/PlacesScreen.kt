// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.FamiliarRow
import dev.retrovision.app.data.FixRow
import dev.retrovision.app.data.toFix
import dev.retrovision.app.data.toModel
import dev.retrovision.core.analysis.DeviceVisit
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.Level
import dev.retrovision.core.analysis.Levels
import dev.retrovision.core.analysis.Geo
import dev.retrovision.core.analysis.VisitTimeline
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val app get() = RetrovisionApp.instance

/** Opens Places centred on one flagged device's places (from the device detail or the alerts). */
object MapNav {
    val device = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Opens Places on one of your past positions (e.g. where a device was heard, from the database search). */
    val point = kotlinx.coroutines.flow.MutableStateFlow<Pair<Double, Double>?>(null)
}

/**
 * Your own movements and the places that count as routine. The only other devices shown are the
 * ones the analysis flagged, at the points of your track where they were heard.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val rows by remember { app.db.dao().familiarPlaces() }.collectAsState(initial = emptyList())
    val here by Collector.location.collectAsState()
    val analysis by Collector.analysis.collectAsState()
    val navDevice by MapNav.device.collectAsState()
    val navPoint by MapNav.point.collectAsState()
    var fixes by remember { mutableStateOf<List<GeoFix>>(emptyList()) }
    val mapState = remember { MapUiState() }
    val places = rows.map { it.toModel() }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dayFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LaunchedEffect(mapState.windowH) {
        while (true) {
            val now = System.currentTimeMillis()
            fixes = app.db.dao().fixesSince(now - mapState.windowH * 3600_000L).map { it.toFix() }
            delay(30_000)
        }
    }
    val visits = remember(fixes) { VisitTimeline.build(fixes) }

    // Devices that may be following you (an alert, or a score of "some signs" or more), the most
    // relevant first, plus the one opened from its detail if it is not among them.
    var pinned by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(navDevice) { navDevice?.let { pinned = it } }
    val flagged = remember(analysis, pinned) {
        val all = analysis?.entities.orEmpty()
            .filter { it.alert || it.score >= 0.5 }
            .map { it to Levels.of(it) }
            .sortedByDescending { (r, l) -> l.ordinal * 10.0 + r.score }
        val top = all.take(DEVICE_COLORS.size - 1).toMutableList()
        pinned?.let { id ->
            if (top.none { it.first.entityId == id }) all.firstOrNull { it.first.entityId == id || id in it.first.memberIds }?.let { top += it }
        }
        if (top.size < DEVICE_COLORS.size) all.drop(top.size).firstOrNull { f -> top.none { it.first.entityId == f.first.entityId } }?.let { top += it }
        top
    }
    // Every place each one was heard, over ALL stored data (not only the analysis window): your GPS
    // position in each minute it was heard, merged when you did not move. Reloaded every 2 minutes.
    var history by remember { mutableStateOf<Map<String, List<DeviceVisit>>>(emptyMap()) }
    val flaggedKey = flagged.joinToString { it.first.entityId }
    LaunchedEffect(flaggedKey) {
        while (true) {
            val ids = flagged.map { it.first }
            history = withContext(Dispatchers.IO) {
                ids.associate { r ->
                    r.entityId to runCatching { heardPlaces(r.memberIds.toList()) }.getOrDefault(emptyList())
                }
            }
            delay(120_000)
        }
    }
    val devices = remember(flagged, history) {
        flagged.mapIndexed { i, (r, l) ->
            DeviceMarks(
                r.entityId, Texts.entityLabel(r), l, DEVICE_COLORS[i % DEVICE_COLORS.size],
                history[r.entityId]?.takeIf { it.isNotEmpty() } ?: r.visits,
            )
        }.filter { d -> d.visits.any { it.lat != null } }
    }

    // Opens like a maps app: centred on you, at street level, following you until you drag.
    var centred by remember { mutableStateOf(false) }
    LaunchedEffect(navPoint) {
        val (lat, lon) = navPoint ?: return@LaunchedEffect
        centred = true
        mapState.follow = false
        // An older position may be outside the shown period: widen it to the last 7 days.
        mapState.windowH = 168
        mapState.selection = MapSel.Point(lat, lon)
        mapState.focusOn(lat, lon, spanM = 500.0)
        MapNav.point.value = null
    }
    LaunchedEffect(here != null, navDevice) {
        val h = here ?: return@LaunchedEffect
        if (centred || navDevice != null || navPoint != null) return@LaunchedEffect
        centred = true
        mapState.follow = true
        mapState.focusOn(h.lat, h.lon, spanM = 600.0)
    }
    LaunchedEffect(navDevice, devices) {
        val id = navDevice ?: return@LaunchedEffect
        val d = devices.firstOrNull { it.entityId == id }
        if (d == null) {
            // Not (or no longer) flagged: nothing to show; fall back to the normal view.
            if (analysis != null) MapNav.device.value = null
            return@LaunchedEffect
        }
        centred = true
        mapState.showDevices = true
        mapState.deviceFocus = id
        fitDevice(mapState, d)
        MapNav.device.value = null
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Texts.tr("Places", "Luoghi"), style = MaterialTheme.typography.headlineSmall)

        val routinePlaces = places.filter { it.state == FamiliarPlace.State.CONFIRMED }
        val map: @Composable (Modifier) -> Unit = { m ->
            TrackMap(
                fixes = fixes,
                visits = visits,
                routine = routinePlaces,
                here = here,
                state = mapState,
                onSaveRoutine = { e -> saveRoutine(scope, e) },
                onDeleteRoutine = { id -> scope.launch { app.db.dao().setFamiliarState(id, FamiliarPlace.State.REJECTED.ordinal); Collector.analyzeNow.value = System.nanoTime() } },
                modifier = m,
                devices = devices,
            )
        }
        if (mapState.fullscreen) {
            FullscreenMap(content = { map(Modifier.fillMaxSize()) }, onClose = { mapState.fullscreen = false })
            Box(Modifier.fillMaxWidth().height(MAP_HEIGHT))
        } else {
            map(Modifier.fillMaxWidth().height(MAP_HEIGHT))
        }
        Text(
            Texts.tr(
                "◎ follows you · ⏵ replay · chips show or hide each device · tap a ◆ for when it was heard",
                "◎ ti segue · ⏵ ripercorri · i chip mostrano o nascondono ogni dispositivo · tocca un ◆ per sapere quando è stato sentito",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The devices that may be following you: show or hide each one, open one to list every place.
        if (devices.isNotEmpty()) {
            Panel(
                title = Texts.tr("Devices that may follow you", "Dispositivi che potrebbero seguirti"),
                trailing = {
                    val allShown = devices.none { it.entityId in mapState.hiddenDevices }
                    Text(
                        if (allShown) Texts.tr("Hide all", "Nascondi tutti") else Texts.tr("Show all", "Mostra tutti"),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            mapState.showDevices = true
                            mapState.hiddenDevices = if (allShown) devices.map { it.entityId }.toSet() else emptySet()
                        },
                    )
                },
            ) {
                devices.forEach { d ->
                    val pts = d.visits.filter { it.lat != null }
                    val open = mapState.deviceFocus == d.entityId
                    val shown = d.entityId !in mapState.hiddenDevices
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (open) { mapState.deviceFocus = null } else {
                                mapState.deviceFocus = d.entityId; mapState.showDevices = true
                                mapState.hiddenDevices = mapState.hiddenDevices - d.entityId
                                fitDevice(mapState, d)
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = shown,
                            onCheckedChange = { on ->
                                mapState.showDevices = true
                                mapState.hiddenDevices = if (on) mapState.hiddenDevices - d.entityId else mapState.hiddenDevices + d.entityId
                            },
                        )
                        Text("◆", color = d.color, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                Texts.level(d.level) + " · ${pts.size} " + Texts.tr("places", "luoghi") +
                                    (pts.minOfOrNull { it.startMs }?.let { " · " + Texts.tr("since ", "dal ") + dayFmt.format(Date(it)) } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = levelColor(d.level),
                            )
                        }
                        Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) {
                        val recent = pts.sortedByDescending { it.startMs }
                        recent.take(100).forEach { v ->
                            Text(
                                dayFmt.format(Date(v.startMs)) + " · " + timeFmt.format(Date(v.startMs)) + "–" + timeFmt.format(Date(v.endMs)) +
                                    " · ${v.sightings}× · ${v.maxRssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    mapState.follow = false
                                    mapState.selection = MapSel.DevicePlace(d, v)
                                    mapState.focusOn(v.lat!!, v.lon!!, spanM = 400.0)
                                }.padding(start = 48.dp, top = 6.dp, bottom = 6.dp),
                            )
                        }
                        if (recent.size > 100) Text(
                            Texts.tr("…and ${recent.size - 100} older ones, all on the map.", "…e altri ${recent.size - 100} più vecchi, tutti sulla mappa."),
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                }
                Text(
                    Texts.tr(
                        "Each ◆ is where you were when it was heard, from all stored data. If one of these is yours, mark it as yours: it is a false alarm.",
                        "Ogni ◆ è dove eri tu quando è stato sentito, da tutti i dati salvati. Se uno di questi è tuo, segnalo come tuo: è un falso allarme.",
                    ),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val suggestions = rows.filter { it.state == FamiliarPlace.State.SUGGESTED.ordinal }
        if (suggestions.isNotEmpty()) {
            Panel(title = Texts.tr("Suggested", "Suggeriti") + " (${suggestions.size})") {
                suggestions.forEach { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(kindText(r) + "  %.4f, %.4f".format(r.lat, r.lon))
                        Text(
                            Texts.tr("You keep coming back here. Is it a place you are at all the time?", "Ci torni spesso. È un posto dove sei sempre?"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { setState(scope, r, FamiliarPlace.State.CONFIRMED) }) { Text(Texts.tr("Yes", "Sì")) }
                            OutlinedButton(onClick = { setState(scope, r, FamiliarPlace.State.REJECTED) }) { Text(Texts.tr("No", "No")) }
                            TextButton(onClick = { mapState.startEdit(RoutineEdit(r.id, r.lat, r.lon, r.radiusM, r.label)) }) { Text(Texts.tr("Adjust area", "Regola area")) }
                        }
                    }
                }
            }
        }

        val confirmed = rows.filter { it.state == FamiliarPlace.State.CONFIRMED.ordinal }
        Expandable(
            title = Texts.tr("Routine places", "Luoghi di routine") + " (${confirmed.size})",
            summary = Texts.tr(
                "Devices seen only here (home, work) count for less: usually neighbours and colleagues.",
                "I dispositivi visti solo qui (casa, lavoro) pesano meno: di solito vicini e colleghi.",
            ),
            initiallyOpen = confirmed.isEmpty(),
        ) {
            OutlinedButton(
                enabled = here != null,
                onClick = {
                    val h = here ?: return@OutlinedButton
                    mapState.startEdit(RoutineEdit(null, h.lat, h.lon, 150.0, ""))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(Texts.tr("Mark where I am now as routine…", "Segna dove sono ora come luogo di routine…")) }
            confirmed.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        (r.label.ifEmpty { kindText(r) }) + " · " + Texts.tr("radius", "raggio") + " ${r.radiusM.toInt()} m",
                        modifier = Modifier.weight(1f).clickable { mapState.follow = false; mapState.focusOn(r.lat, r.lon) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { mapState.startEdit(RoutineEdit(r.id, r.lat, r.lon, r.radiusM, r.label)) }) { Text(Texts.tr("Area", "Area")) }
                    // Marked "rejected" rather than deleted, so the learner does not suggest it again.
                    TextButton(onClick = { setState(scope, r, FamiliarPlace.State.REJECTED) }) { Text(Texts.tr("Remove", "Rimuovi")) }
                }
            }
        }

        Expandable(
            title = Texts.tr("Timeline", "Timeline") + " (${visits.size})",
            summary = visits.lastOrNull()?.let { v ->
                Texts.tr("Last stay ", "Ultima sosta ") + "${timeFmt.format(Date(v.startMs))}–${timeFmt.format(Date(v.endMs))}"
            } ?: Texts.tr("No stays recorded yet.", "Nessuna sosta registrata."),
        ) {
            if (visits.isEmpty()) Text(Texts.tr("No stays recorded yet.", "Nessuna sosta registrata."), style = MaterialTheme.typography.bodySmall)
            var lastDay = ""
            visits.asReversed().forEach { v ->
                val day = dayFmt.format(Date(v.startMs))
                if (day != lastDay) { lastDay = day; Overline(day, Modifier.padding(top = 6.dp)) }
                val routine = places.any { it.state == FamiliarPlace.State.CONFIRMED && it.contains(v.lat, v.lon) }
                Text(
                    "${timeFmt.format(Date(v.startMs))}–${timeFmt.format(Date(v.endMs))} · ${v.durationMs / 60_000} min · " +
                        if (routine) Texts.tr("routine place", "luogo di routine") else Texts.tr("other place", "altro luogo"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { mapState.follow = false; mapState.selection = MapSel.Stay(v); mapState.focusOn(v.lat, v.lon) }
                        .padding(vertical = 6.dp),
                )
            }
        }

        Expandable(
            title = Texts.tr("Offline map", "Mappa offline"),
            summary = Texts.tr("Import or choose the street map stored on the phone", "Importa o scegli la mappa stradale salvata sul telefono"),
        ) { OfflineMapsSection() }
    }
}

private val MAP_HEIGHT = 540.dp

/** Your positions while one device was heard; consecutive minutes within 30 m become one point. */
private suspend fun heardPlaces(ids: List<String>): List<DeviceVisit> {
    val dao = app.db.dao()
    val rows = dao.heardBuckets(ids, 60_000L, 20_000)
    if (rows.isEmpty()) return emptyList()
    // Fixes for the whole span, one per 15 s; each bucket takes the nearest one within a minute.
    val fixes = dao.fixesThinned(rows.first().t0 - 60_000L, rows.last().t1 + 60_000L, 15_000L)
    val times = LongArray(fixes.size) { fixes[it].timeMs }
    fun nearest(t: Long): FixRow? {
        if (times.isEmpty()) return null
        var k = java.util.Arrays.binarySearch(times, t)
        if (k < 0) k = -k - 1
        val cand = listOfNotNull(fixes.getOrNull(k - 1), fixes.getOrNull(k))
        return cand.minByOrNull { kotlin.math.abs(it.timeMs - t) }?.takeIf { kotlin.math.abs(it.timeMs - t) <= 60_000L }
    }
    val out = ArrayList<DeviceVisit>()
    for (r in rows) {
        val f = nearest(r.t0) ?: continue
        val lat = f.lat
        val lon = f.lon
        val last = out.lastOrNull()
        if (last != null && r.t0 - last.endMs <= 10 * 60_000L &&
            dev.retrovision.core.analysis.Geo.distanceM(last.lat!!, last.lon!!, lat, lon) < 30.0
        ) {
            out[out.size - 1] = DeviceVisit(-1, last.lat, last.lon, last.startMs, r.t1, last.sightings + r.n, maxOf(last.maxRssi, r.rssi))
        } else {
            out += DeviceVisit(-1, lat, lon, r.t0, r.t1, r.n, r.rssi)
        }
    }
    return out
}

private fun kindText(r: FamiliarRow) =
    if (r.kind == FamiliarPlace.Kind.HOME_LIKE.ordinal) Texts.tr("Home-like", "Tipo casa") else Texts.tr("Frequent", "Frequente")

private fun saveRoutine(scope: kotlinx.coroutines.CoroutineScope, e: RoutineEdit) {
    scope.launch {
        val dao = app.db.dao()
        if (e.id == null) {
            dao.addFamiliar(
                FamiliarRow(
                    lat = e.lat, lon = e.lon, radiusM = e.radiusM, label = e.label.trim(),
                    state = FamiliarPlace.State.CONFIRMED.ordinal,
                    kind = FamiliarPlace.Kind.FREQUENT.ordinal, createdMs = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.updateFamiliarArea(e.id, e.lat, e.lon, e.radiusM, e.label.trim(), FamiliarPlace.State.CONFIRMED.ordinal)
        }
        Collector.analyzeNow.value = System.nanoTime()
    }
}

private fun setState(scope: kotlinx.coroutines.CoroutineScope, r: FamiliarRow, s: FamiliarPlace.State) {
    scope.launch {
        app.db.dao().setFamiliarState(r.id, s.ordinal)
        Collector.analyzeNow.value = System.nanoTime()
    }
}
