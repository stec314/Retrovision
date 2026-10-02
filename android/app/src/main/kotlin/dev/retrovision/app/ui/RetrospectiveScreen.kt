// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.retrovision.app.data.Retrospective
import dev.retrovision.core.analysis.EntityReport
import kotlinx.coroutines.launch

/** "Analyse saved data": offline review that flags devices that may have followed you over a span. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetrospectiveCard() {
    val scope = rememberCoroutineScope()
    var spanH by remember { mutableStateOf(24) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Retrospective.Result?>(null) }
    var selected by remember { mutableStateOf<EntityReport?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Texts.tr("Review saved data", "Rivedi i dati salvati"), style = MaterialTheme.typography.titleMedium)
            Text(
                Texts.tr(
                    "Looks over everything recorded in the chosen span and flags devices that kept reappearing at different places and times — a follower you might miss live.",
                    "Esamina tutto ciò che è stato registrato nel periodo scelto e segnala i dispositivi che ricompaiono in luoghi e orari diversi — un inseguitore che dal vivo potresti non notare.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(24 to "24 h", 72 to "3 g", 168 to "7 g").forEach { (h, label) ->
                    FilterChip(selected = spanH == h, onClick = { spanH = h }, label = { Text(label) }, enabled = !running)
                }
            }
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        result = runCatching { Retrospective.run(spanH * 3_600_000L) }.getOrNull()
                        running = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(Texts.tr("Analysing…", "Analisi in corso…"))
                } else {
                    Text(Texts.tr("Analyse saved data", "Analizza i dati salvati"))
                }
            }

            result?.let { res ->
                val an = res.analysis
                if (res.error != null || an == null) {
                    Text(
                        when (res.error) {
                            "out_of_memory" -> Texts.tr("Too much data to review at once. Try a shorter span.", "Troppi dati da esaminare in una volta. Prova un periodo più breve.")
                            else -> Texts.tr("Analysis failed: ", "Analisi fallita: ") + (res.error ?: "?")
                        },
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val suspects = an.entities.filter { it.score >= 0.5 }.take(20)
                    Text(
                        Texts.tr(
                            "${res.sightings} sightings reviewed · ${an.entities.size} devices · ${suspects.size} worth a look",
                            "${res.sightings} avvistamenti esaminati · ${an.entities.size} dispositivi · ${suspects.size} da guardare",
                        ),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    )
                    if (res.sampledFrom > 0) {
                        Text(
                            Texts.tr(
                                "Sampled from ${res.sampledFrom} sightings to stay fast.",
                                "Campionati da ${res.sampledFrom} avvistamenti per restare veloce.",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (suspects.isEmpty()) {
                        Text(Texts.tr("Nothing stands out across this span.", "Niente spicca in questo periodo."))
                    } else {
                        Text(
                            Texts.tr("Most likely to have followed you (tap for details):", "Più probabile ti abbiano seguito (tocca per i dettagli):"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        suspects.forEach { EntityCard(it) { selected = it } }
                    }
                }
            }
        }
    }
    selected?.let { DeviceDialog(it) { selected = null } }
}
