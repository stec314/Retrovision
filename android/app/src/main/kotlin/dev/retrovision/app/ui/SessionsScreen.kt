package dev.retrovision.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.Replay
import dev.retrovision.app.data.SessionRecorder
import dev.retrovision.app.data.toModel
import kotlinx.coroutines.launch
import java.io.File

private val app get() = RetrovisionApp.instance

/** Record a session, keep it, replay it later with different settings. */
@Composable
fun SessionsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by SessionRecorder.recording.collectAsState()
    val running by Collector.running.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val files = remember(tick, recording) {
        SessionRecorder.dir(ctx).listFiles { f -> f.name.endsWith(".rvsl") }?.sortedByDescending { it.name }.orEmpty()
    }
    var outcome by remember { mutableStateOf<Pair<String, Replay.Outcome>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    File(SessionRecorder.dir(ctx), "import-${System.currentTimeMillis()}.rvsl").outputStream().use { o -> i.copyTo(o) }
                }
            }.onFailure { error = it.message }
            tick++
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = exportTarget
        if (uri != null && f != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
                .onFailure { error = it.message }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Texts.tr("Recorded sessions", "Sessioni registrate"), style = MaterialTheme.typography.titleMedium)
        Text(
            Texts.tr(
                "A recording holds the observations and your GPS fixes, so you can replay it with other settings or newer analysis code. It stays on this phone unless you export it, and it contains other people's device addresses: treat it like the database.",
                "Una registrazione contiene osservazioni e i tuoi fix GPS: puoi rianalizzarla con altre impostazioni o con codice più recente. Resta su questo telefono finché non la esporti e contiene indirizzi di dispositivi altrui: trattala come il database.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording == null) {
                Button(enabled = running, onClick = { SessionRecorder.start(ctx) }) { Text(Texts.tr("Start recording", "Avvia registrazione")) }
            } else {
                Button(onClick = { SessionRecorder.stop(); tick++ }) { Text(Texts.tr("Stop recording", "Ferma registrazione")) }
            }
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(Texts.tr("Import", "Importa")) }
        }
        if (!running && recording == null) Text(Texts.tr("Start collecting first.", "Avvia prima la raccolta."), style = MaterialTheme.typography.bodySmall)
        recording?.let { Text("● $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        files.forEach { f ->
            Column {
                Text("${f.name}  (${f.length() / 1024} KiB)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = f.name != recording, onClick = {
                        scope.launch {
                            error = null
                            try {
                                val fam = app.db.dao().familiarNow().map { it.toModel() }
                                outcome = f.name to Replay.run(f.inputStream(), app.prefs, fam)
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }) { Text(Texts.tr("Replay", "Riproduci")) }
                    TextButton(enabled = f.name != recording, onClick = { exportTarget = f; exporter.launch(f.name) }) { Text(Texts.tr("Export", "Esporta")) }
                    TextButton(enabled = f.name != recording, onClick = { f.delete(); tick++ }) { Text(Texts.tr("Delete", "Elimina")) }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    outcome?.let { (name, o) ->
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${o.sightings} ${Texts.tr("observations", "osservazioni")} · ${o.fixes} GPS · ${o.durationMs / 60_000} min · " +
                            "${o.result.entities.size} ${Texts.tr("devices", "dispositivi")} · ${o.result.alerts.size} ${Texts.tr("alerts", "allerte")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    o.result.entities.take(30).forEach { EntityCard(it) }
                }
            },
            confirmButton = { TextButton(onClick = { outcome = null }) { Text(Texts.tr("Close", "Chiudi")) } },
        )
    }
}

/** Shows how well a device you carry on purpose is being detected and scored. */
@Composable
fun FieldTestSection() {
    val analysis by Collector.analysis.collectAsState()
    val targets = app.prefs.targets
    val rows = analysis?.entities.orEmpty().filter { e ->
        e.entityId in targets || e.ssids.any { it.startsWith("RV-TARGET") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(Texts.tr("Field test", "Test sul campo"), style = MaterialTheme.typography.titleMedium)
        Text(
            Texts.tr(
                "Carry a device you control (the retrovision-target firmware, or any phone/tag), mark it from the Devices tab, then go about your day. Detection % is the share of minutes in which it was heard; the score should cross the alert threshold once you have been to enough different places. If it does not, the weights need tuning.",
                "Porta con te un dispositivo che controlli (il firmware retrovision-target, o un telefono/tag), segnalo dalla scheda Dispositivi e vai in giro. Rilevamento % è la quota di minuti in cui è stato sentito; il punteggio dovrebbe superare la soglia dopo abbastanza luoghi diversi. Se non succede, i pesi vanno tarati.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (rows.isEmpty()) Text(Texts.tr("No target seen in the analysis window.", "Nessun bersaglio nella finestra di analisi."), style = MaterialTheme.typography.bodySmall)
        rows.forEach { e ->
            val span = (e.lastSeenMs - e.firstSeenMs) / 60_000 + 1
            val pct = 100.0 * e.activeMinutes / span
            Text(
                "${Texts.entityLabel(e)}\n" +
                    Texts.tr("detection", "rilevamento") + " %.0f%% (%d/%d min) · ".format(pct, e.activeMinutes, span) +
                    "${e.placeIds.size} ${Texts.tr("places", "luoghi")} · " +
                    Texts.tr("score", "punteggio") + " %.0f%%".format(e.score * 100) +
                    if (e.alert) " · ${Texts.tr("ALERT", "ALLERTA")}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
