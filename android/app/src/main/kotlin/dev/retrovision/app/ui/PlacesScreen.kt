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
import dev.retrovision.core.analysis.Geo
import dev.retrovision.core.analysis.VisitTimeline
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val app get() = RetrovisionApp.instance

/** Your own movements and the places that count as routine. Nothing here concerns other devices. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val rows by remember { app.db.dao().familiarPlaces() }.collectAsState(initial = emptyList())
    val here by Collector.location.collectAsState()
    var fixes by remember { mutableStateOf<List<GeoFix>>(emptyList()) }
    val mapState = remember { MapUiState() }
    val places = rows.map { it.toModel() }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    LaunchedEffect(mapState.windowH) {
        while (true) {
            val now = System.currentTimeMillis()
            fixes = app.db.dao().fixesSince(now - mapState.windowH * 3600_000L).map { it.toFix() }
            delay(30_000)
        }
    }
    val visits = remember(fixes) { VisitTimeline.build(fixes) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Texts.tr("Places", "Luoghi"), style = MaterialTheme.typography.headlineSmall)
        Text(
            Texts.tr(
                "Devices seen only at routine places (home, work) count for less in the score: they are usually neighbours and colleagues.",
                "I dispositivi visti solo nei luoghi di routine (casa, lavoro) pesano meno nel punteggio: di solito sono vicini e colleghi.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )

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
            )
        }
        if (mapState.fullscreen) {
            FullscreenMap(content = { map(Modifier.fillMaxSize()) }, onClose = { mapState.fullscreen = false })
            Box(Modifier.fillMaxWidth().height(380.dp))
        } else {
            map(Modifier.fillMaxWidth().height(380.dp))
        }
        Text(
            Texts.tr(
                "Pinch to zoom · tap a point or stay · long press to pick a spot · ☰ for period, layers and maps",
                "Pizzica per lo zoom · tocca un punto o una sosta · tieni premuto per scegliere un punto · ☰ per periodo, livelli e mappe",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            enabled = here != null,
            onClick = {
                val h = here ?: return@OutlinedButton
                mapState.startEdit(RoutineEdit(null, h.lat, h.lon, 150.0, ""))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(Texts.tr("Mark where I am now as routine…", "Segna dove sono ora come luogo di routine…")) }
        OfflineMapsCard()

        val suggestions = rows.filter { it.state == FamiliarPlace.State.SUGGESTED.ordinal }
        if (suggestions.isNotEmpty()) {
            Text(Texts.tr("Suggested", "Suggeriti") + " (${suggestions.size})", style = MaterialTheme.typography.titleMedium)
            suggestions.forEach { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Text(Texts.tr("Routine places", "Luoghi di routine") + " (${confirmed.size})", style = MaterialTheme.typography.titleMedium)
        confirmed.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    (r.label.ifEmpty { kindText(r) }) + " · " + Texts.tr("radius", "raggio") + " ${r.radiusM.toInt()} m",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { mapState.startEdit(RoutineEdit(r.id, r.lat, r.lon, r.radiusM, r.label)) }) { Text(Texts.tr("Area", "Area")) }
                // Marked "rejected" rather than deleted, so the learner does not suggest it again.
                TextButton(onClick = { setState(scope, r, FamiliarPlace.State.REJECTED) }) { Text(Texts.tr("Remove", "Rimuovi")) }
            }
        }

        Text(Texts.tr("Timeline", "Timeline"), style = MaterialTheme.typography.titleMedium)
        if (visits.isEmpty()) Text(Texts.tr("No stays recorded yet.", "Nessuna sosta registrata."), style = MaterialTheme.typography.bodySmall)
        visits.asReversed().forEach { v ->
            val routine = places.any { it.state == FamiliarPlace.State.CONFIRMED && it.contains(v.lat, v.lon) }
            Text(
                "${timeFmt.format(Date(v.startMs))}–${timeFmt.format(Date(v.endMs))} · ${v.durationMs / 60_000} min · " +
                    if (routine) Texts.tr("routine place", "luogo di routine") else Texts.tr("other place", "altro luogo"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
                    .clickable { mapState.selection = MapSel.Stay(v); mapState.focusOn(v.lat, v.lon) }
                    .padding(vertical = 6.dp),
            )
        }
    }
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
