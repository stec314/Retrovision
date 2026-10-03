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
import dev.retrovision.app.data.toFix
import dev.retrovision.app.data.toModel
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.Level
import dev.retrovision.core.analysis.Levels
import dev.retrovision.core.analysis.Geo
import dev.retrovision.core.analysis.VisitTimeline
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.delay
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

    // Flagged devices (worth a look or strong) with the places where they were heard: the six most
    // relevant, plus the one opened from its detail if it is not among them.
    var pinned by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(navDevice) { navDevice?.let { pinned = it } }
    val devices = remember(analysis, pinned) {
        val flagged = analysis?.alerts.orEmpty()
            .map { it to Levels.of(it) }
            .filter { (r, l) -> l >= Level.WORTH_A_LOOK && r.visits.any { v -> v.lat != null } }
            .sortedByDescending { (r, l) -> l.ordinal * 10.0 + r.score }
        val top = flagged.take(DEVICE_COLORS.size - 1).toMutableList()
        pinned?.let { id ->
            if (top.none { it.first.entityId == id }) flagged.firstOrNull { it.first.entityId == id || id in it.first.memberIds }?.let { top += it }
        }
        if (top.size < DEVICE_COLORS.size) flagged.drop(top.size).firstOrNull { f -> top.none { it.first.entityId == f.first.entityId } }?.let { top += it }
        top.mapIndexed { i, (r, l) -> DeviceMarks(r.entityId, Texts.entityLabel(r), l, DEVICE_COLORS[i % DEVICE_COLORS.size], r.visits) }
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
                "Pinch to zoom · ◎ follows you · tap a point, stay or ◆ device · long press to pick a spot",
                "Pizzica per lo zoom · ◎ ti segue · tocca un punto, una sosta o un ◆ dispositivo · tieni premuto per scegliere un punto",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The devices that may have followed you, each with every place it was heard.
        if (devices.isNotEmpty()) {
            Panel(title = Texts.tr("Flagged devices on the map", "Dispositivi segnalati sulla mappa")) {
                devices.forEach { d ->
                    val pts = d.visits.filter { it.lat != null }
                    val open = mapState.deviceFocus == d.entityId
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (open) { mapState.deviceFocus = null } else { mapState.deviceFocus = d.entityId; mapState.showDevices = true; fitDevice(mapState, d) }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("◆", color = d.color, style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                Texts.level(d.level) + " · ${pts.size} " + Texts.tr("places", "luoghi"),
                                style = MaterialTheme.typography.labelSmall, color = levelColor(d.level),
                            )
                        }
                        Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) {
                        pts.sortedByDescending { it.startMs }.forEach { v ->
                            Text(
                                dayFmt.format(Date(v.startMs)) + " · " + timeFmt.format(Date(v.startMs)) + "–" + timeFmt.format(Date(v.endMs)) +
                                    " · ${v.sightings}× · ${v.maxRssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    mapState.follow = false
                                    mapState.selection = MapSel.DevicePlace(d, v)
                                    mapState.focusOn(v.lat!!, v.lon!!, spanM = 500.0)
                                }.padding(start = 28.dp, top = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                Text(
                    Texts.tr(
                        "Each ◆ is where you were when it was heard (analysis window only). Only devices the analysis flagged are shown.",
                        "Ogni ◆ è dove eri tu quando è stato sentito (solo finestra di analisi). Sono mostrati solo i dispositivi segnalati dall'analisi.",
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
