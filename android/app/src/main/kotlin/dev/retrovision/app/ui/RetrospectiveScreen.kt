// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.material3.TextButton

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

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
    // A custom period (from, to) chosen on the calendar; null = the last [spanH] hours.
    var custom by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val dayFmt = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(6 to "6 h", 24 to "24 h", 72 to Texts.tr("3 days", "3 giorni"), 168 to Texts.tr("7 days", "7 giorni")).forEach { (h, label) ->
                    FilterChip(selected = custom == null && spanH == h, onClick = { spanH = h; custom = null }, label = { Text(label) }, enabled = !running)
                }
                FilterChip(
                    selected = custom != null, onClick = { picking = true }, enabled = !running,
                    label = { Text(custom?.let { (a, b) -> dayFmt.format(java.util.Date(a)) + " – " + dayFmt.format(java.util.Date(b - 1)) } ?: Texts.tr("Choose dates…", "Scegli le date…")) },
                )
            }
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        result = runCatching {
                            val c = custom
                            if (c != null) Retrospective.run(c.first, c.second) else Retrospective.run(spanH * 3_600_000L)
                        }.getOrNull()
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

    if (picking) {
        val today = remember { System.currentTimeMillis() }
        val st = androidx.compose.material3.rememberDateRangePickerState(
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = st.selectedStartDateMillis != null,
                    onClick = {
                        // The picker returns UTC midnights: turn them into local whole days, end exclusive.
                        val zone = java.time.ZoneId.systemDefault()
                        fun day(utc: Long) = java.time.Instant.ofEpochMilli(utc).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val a = day(st.selectedStartDateMillis!!)
                        val b = day(st.selectedEndDateMillis ?: st.selectedStartDateMillis!!)
                        custom = a.atStartOfDay(zone).toInstant().toEpochMilli() to b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        picking = false
                    },
                ) { Text(Texts.tr("Use these days", "Usa questi giorni")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        ) {
            androidx.compose.material3.DateRangePicker(state = st, modifier = Modifier.weight(1f))
        }
    }
}
