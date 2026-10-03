// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.map.OfflineMaps
import dev.retrovision.core.map.TileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Import and choose offline basemaps (.pmtiles / .mbtiles). */
@Composable
fun OfflineMapsSection() {
    val scope = rememberCoroutineScope()
    val maps by OfflineMaps.maps.collectAsState()
    val active by OfflineMaps.active.collectAsState()
    val progress by OfflineMaps.importProgress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = OfflineMaps.import(uri).exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }

    // Inside the collapsible section on Places: no card of its own.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        run {
            Text(
                Texts.tr(
                    "Streets come from a file on the phone: the map never asks a server where you are.",
                    "Le strade arrivano da un file sul telefono: la mappa non chiede mai a un server dove sei.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = active == null, onClick = { scope.launch { withContext(Dispatchers.IO) { OfflineMaps.activate(null) } } })
                Text(Texts.tr("Grid only", "Solo griglia"))
            }
            maps.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = active?.first?.id == m.id,
                        onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { OfflineMaps.activate(m) } } } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(m.file.nameWithoutExtension, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (m.info.type == TileType.MVT) Texts.tr("vector", "vettoriale") else Texts.tr("raster (dark filter)", "raster (filtro scuro)")) +
                                " · z${m.info.minZoom}–${m.info.maxZoom} · ${m.sizeMb} MB",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { OfflineMaps.delete(m) } } }) {
                        Text(Texts.tr("Delete", "Elimina"))
                    }
                }
            }
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Text(Texts.tr("Importing…", "Importazione…") + " ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = progress == null, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(Texts.tr("Import .pmtiles / .mbtiles", "Importa .pmtiles / .mbtiles"))
                }
                TextButton(onClick = { help = !help }) { Text(Texts.tr("How?", "Come?")) }
            }
            if (help) {
                Text(
                    Texts.tr(
                        "Easiest: open the map ☰ menu and choose \"Download the map of this area\". " +
                            "Or on GitHub open Actions → \"offline map\" → Run workflow, pick a region (or type a bounding box). " +
                            "When it finishes, download the .pmtiles from the \"maps\" release and import it here.\n" +
                            "Alternatives: pmtiles extract https://build.protomaps.com/YYYYMMDD.pmtiles out.pmtiles --bbox=W,S,E,N --maxzoom=15, " +
                            "or any raster .mbtiles made with MOBAC or QGIS.",
                        "Il modo più semplice: apri il menu ☰ della mappa e scegli \"Scarica la mappa di quest'area\". " +
                            "Oppure su GitHub apri Actions → \"offline map\" → Run workflow, scegli una regione (o scrivi un riquadro). " +
                            "Quando finisce, scarica il .pmtiles dalla release \"maps\" e importalo qui.\n" +
                            "In alternativa: pmtiles extract https://build.protomaps.com/AAAAMMGG.pmtiles out.pmtiles --bbox=O,S,E,N --maxzoom=15, " +
                            "oppure un .mbtiles raster fatto con MOBAC o QGIS.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
