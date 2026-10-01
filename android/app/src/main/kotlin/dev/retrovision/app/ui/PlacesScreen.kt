package dev.retrovision.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.max

private val app get() = RetrovisionApp.instance

/** Your own movements and the places that count as routine. Nothing here concerns other devices. */
@Composable
fun PlacesScreen(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val rows by remember { app.db.dao().familiarPlaces() }.collectAsState(initial = emptyList())
    val here by Collector.location.collectAsState()
    var fixes by remember { mutableStateOf<List<GeoFix>>(emptyList()) }
    val places = rows.map { it.toModel() }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            fixes = app.db.dao().fixesSince(now - 24 * 3600_000L).map { it.toFix() }
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

        Text(Texts.tr("Your last 24 hours", "Le tue ultime 24 ore"), style = MaterialTheme.typography.titleMedium)
        TrackMap(fixes, places.filter { it.state == FamiliarPlace.State.CONFIRMED }, here)

        OutlinedButton(
            enabled = here != null,
            onClick = {
                val h = here ?: return@OutlinedButton
                scope.launch {
                    app.db.dao().addFamiliar(
                        FamiliarRow(
                            lat = h.lat, lon = h.lon, radiusM = 150.0, label = "",
                            state = FamiliarPlace.State.CONFIRMED.ordinal,
                            kind = FamiliarPlace.Kind.FREQUENT.ordinal, createdMs = System.currentTimeMillis(),
                        ),
                    )
                    Collector.analyzeNow.value = System.nanoTime()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(Texts.tr("Mark where I am now as routine", "Segna dove sono ora come luogo di routine")) }

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
                        }
                    }
                }
            }
        }

        val confirmed = rows.filter { it.state == FamiliarPlace.State.CONFIRMED.ordinal }
        Text(Texts.tr("Routine places", "Luoghi di routine") + " (${confirmed.size})", style = MaterialTheme.typography.titleMedium)
        confirmed.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(kindText(r) + "  %.4f, %.4f".format(r.lat, r.lon), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
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
            )
        }
    }
}

private fun kindText(r: FamiliarRow) =
    if (r.kind == FamiliarPlace.Kind.HOME_LIKE.ordinal) Texts.tr("Home-like", "Tipo casa") else Texts.tr("Frequent", "Frequente")

private fun setState(scope: kotlinx.coroutines.CoroutineScope, r: FamiliarRow, s: FamiliarPlace.State) {
    scope.launch {
        app.db.dao().setFamiliarState(r.id, s.ordinal)
        Collector.analyzeNow.value = System.nanoTime()
    }
}

/** Tile-less map: your own track, routine places and current position. No network, no other devices. */
@Composable
private fun TrackMap(fixes: List<GeoFix>, routine: List<FamiliarPlace>, here: GeoFix?) {
    val track = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outline
    val me = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        val all = fixes.map { it.lat to it.lon } + routine.map { it.lat to it.lon } + listOfNotNull(here?.let { it.lat to it.lon })
        if (all.isEmpty()) return@Canvas
        val lat0 = all.map { it.first }.average()
        val lon0 = all.map { it.second }.average()
        val k = 111_320.0
        val cosL = cos(Math.toRadians(lat0))
        fun x(lon: Double) = (lon - lon0) * cosL * k
        fun y(lat: Double) = (lat - lat0) * k
        val xs = all.map { x(it.second) }
        val ys = all.map { y(it.first) }
        val span = max(max(xs.max() - xs.min(), ys.max() - ys.min()), 300.0) * 1.25
        val scale = (minOf(size.width, size.height) / span).toFloat()
        val cx = (xs.max() + xs.min()) / 2
        val cy = (ys.max() + ys.min()) / 2
        fun px(lon: Double) = size.width / 2 + ((x(lon) - cx) * scale).toFloat()
        fun py(lat: Double) = size.height / 2 - ((y(lat) - cy) * scale).toFloat()

        drawRect(grid, style = Stroke(1f))
        routine.forEach {
            drawCircle(ring, radius = (it.radiusM * scale).toFloat(), center = Offset(px(it.lon), py(it.lat)), style = Stroke(3f))
        }
        val step = max(1, fixes.size / 800)
        val path = Path()
        fixes.filterIndexed { i, _ -> i % step == 0 }.forEachIndexed { i, f ->
            if (i == 0) path.moveTo(px(f.lon), py(f.lat)) else path.lineTo(px(f.lon), py(f.lat))
        }
        drawPath(path, track, style = Stroke(3f))
        here?.let { drawCircle(me, radius = 9f, center = Offset(px(it.lon), py(it.lat))) }
        // 100 m scale bar
        val bar = (100 * scale)
        drawLine(Color.Gray, Offset(16f, size.height - 16f), Offset(16f + bar, size.height - 16f), strokeWidth = 4f)
    }
}
