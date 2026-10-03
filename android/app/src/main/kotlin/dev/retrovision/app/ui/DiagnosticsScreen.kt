// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.CrashLog
import dev.retrovision.app.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DiagNav {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Live pipeline counters, the event log and a shareable report, for figuring out what went wrong. */
@Composable
fun DiagnosticsScreen(onClose: () -> Unit) {
    BackHandler { onClose() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m by Diag.metrics.collectAsState()
    val ver by Diag.version.collectAsState()
    val conn by Collector.connection.collectAsState()
    val load by Collector.analysisLoad.collectAsState()
    var withLogcat by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    val events = remember(ver) { Diag.events().asReversed() }
    val crash = remember(ver) { CrashLog.read(ctx) }

    fun buildReport(then: (String) -> Unit) {
        status = Texts.tr("Building report…", "Preparo il report…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { Diag.report(ctx, withLogcat) }
            status = ""
            then(r)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClose)
                Text(Texts.tr("Diagnostics", "Diagnostica"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        Texts.tr(
                            "Everything here stays on the phone. A report leaves only if you copy or share it. It contains counters and errors, no device addresses or network names.",
                            "Tutto resta sul telefono. Il report esce solo se lo copi o lo condividi. Contiene contatori ed errori, nessun indirizzo di dispositivo né nome di rete.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val heap = remember(tick) { Diag.heapLine() } // refreshed once a second
                            val s = conn.session
                            Line(Texts.tr("Memory", "Memoria"), heap, warn = heapFraction() > 0.8)
                            Line("USB", "${conn.link} · ${Texts.tr("connections", "connessioni")} ${m.connections} · ${Texts.tr("errors", "errori")} ${m.usbErrors}", warn = m.usbErrors > 0)
                            Line(
                                Texts.tr("Decoder", "Decoder"),
                                "backlog ${m.inboxBacklog} · ${Texts.tr("dropped", "scartati")} ${m.inboxDrops}",
                                warn = m.inboxDrops > 0 || m.inboxBacklog > 100,
                            )
                            if (s != null) {
                                Line(
                                    Texts.tr("Probe", "Sonda"),
                                    "seq gaps ${s.lostFrames} · ${Texts.tr("queue drops", "scarti coda")} ${s.probeDropped} · CRC ${s.badFrames} · ${"%.0f".format(s.chipTempC)} °C",
                                    warn = s.lostFrames + s.probeDropped > 0 || s.chipTempC >= 75f,
                                )
                            }
                            Line(
                                "Database",
                                "${m.writerLastRows} ${Texts.tr("rows in", "righe in")} ${m.writerLastMs} ms (max ${m.writerMaxMs}) · ${Texts.tr("queue drops", "scarti coda")} ${m.queueDrops} · ${Texts.tr("errors", "errori")} ${m.writerErrors}",
                                warn = m.queueDrops > 0 || m.writerErrors > 0 || m.writerMaxMs > 3_000,
                            )
                            Line(
                                Texts.tr("Analysis", "Analisi"),
                                "${m.analysisRuns} runs · ${m.analysisLastMs} ms (max ${m.analysisMaxMs}) · ${load.analysedRows}/${load.rawRows} ${Texts.tr("rows", "righe")} · ${Texts.tr("errors", "errori")} ${m.analysisErrors}",
                                warn = m.analysisErrors > 0 || load.truncated || m.analysisMaxMs > 15_000,
                            )
                            Line("UI", "${Texts.tr("freezes", "blocchi")} ≥2 s: ${m.uiStalls} · max ${m.uiStallMaxMs} ms", warn = m.uiStalls > 0)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withLogcat, onCheckedChange = { withLogcat = it })
                        Text(Texts.tr("Include the app's own system log (warnings and errors)", "Includi il log di sistema dell'app (avvisi ed errori)"), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            buildReport { r ->
                                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Retrovision diagnostics", r))
                                status = Texts.tr("Report copied", "Report copiato")
                            }
                        }) { Text(Texts.tr("Copy report", "Copia report")) }
                        OutlinedButton(onClick = {
                            buildReport { r ->
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "Retrovision diagnostics")
                                    .putExtra(Intent.EXTRA_TEXT, r.takeLast(90_000))
                                ctx.startActivity(Intent.createChooser(send, Texts.tr("Share report", "Condividi report")))
                            }
                        }) { Text(Texts.tr("Share", "Condividi")) }
                        TextButton(onClick = { Diag.clear(); CrashLog.clear(ctx) }) { Text(Texts.tr("Clear", "Azzera")) }
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (crash != null) {
                    item {
                        Text(Texts.tr("Last crash", "Ultimo crash"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(crash.lines().take(30).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                item { Text(Texts.tr("Events (newest first)", "Eventi (più recenti prima)"), style = MaterialTheme.typography.titleSmall) }
                if (events.isEmpty()) item { Text(Texts.tr("No events yet.", "Ancora nessun evento."), style = MaterialTheme.typography.bodySmall) }
                items(events) { e ->
                    Text(
                        Diag.format(e),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = when (e.level) {
                            'E' -> MaterialTheme.colorScheme.error
                            'W' -> Color(0xFFE0B040)
                            else -> Color.Unspecified
                        },
                    )
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
            }
        }
    }
}

private fun heapFraction(): Double {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory()
}

@Composable
private fun Line(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp).fillMaxWidth(0.28f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = if (warn) Color(0xFFE0B040) else Color.Unspecified)
    }
}
