// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.FeedbackRow
import dev.retrovision.core.analysis.Drones
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.analysis.WifiThreats
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object AlertsNav {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

private val ATTACK = Color(0xFFFF6B6B)
private val FOLLOW = Color(0xFFFFB74D)
private val DRONE = Color(0xFF9FA8FF)

/** Every current alert as a tile: following, radio attacks, drones. Tap for evidence and a verdict. */
@Composable
fun AlertsScreen(onClose: () -> Unit) {
    BackHandler { onClose() }
    val analysis by Collector.analysis.collectAsState()
    val threats by Collector.threats.collectAsState()
    val drones by Collector.drones.collectAsState()
    val feedback by remember { RetrovisionApp.instance.db.dao().feedback() }.collectAsState(initial = emptyList())
    // Latest verdict per key (entity id, threat key, drone key).
    val verdicts = remember(feedback) { feedback.groupBy { it.entityId }.mapValues { it.value.maxBy { f -> f.timeMs }.label } }
    val alerts = analysis?.alerts.orEmpty()
    var entity by remember { mutableStateOf<EntityReport?>(null) }
    var threat by remember { mutableStateOf<WifiThreats.Threat?>(null) }
    var drone by remember { mutableStateOf<Drones.Drone?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("←") }
                Text(Texts.tr("Alerts", "Allerte"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fun header(text: String) = item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (alerts.isEmpty() && threats.isEmpty() && drones.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(Texts.tr("No alerts right now.", "Nessuna allerta al momento."), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (threats.isNotEmpty()) {
                    header(Texts.tr("Radio attacks", "Attacchi radio"))
                    items(threats, key = { "t" + it.key }) { t ->
                        Tile(
                            "⚠", Texts.threatTitle(t.kind), Texts.threat(t), "%.0f%%".format(t.severity * 100), t.lastMs,
                            ATTACK, verdicts[t.key],
                        ) { threat = t }
                    }
                }
                if (alerts.isNotEmpty()) {
                    header(Texts.tr("Possibly following you", "Forse ti segue"))
                    items(alerts, key = { "e" + it.entityId }) { r ->
                        Tile(
                            CategoryUi.icon(r.category), Texts.entityLabel(r),
                            r.reasons.take(2).joinToString(" · ") { Texts.reason(it) },
                            "%.0f%%".format(r.score * 100), r.lastSeenMs, FOLLOW,
                            r.memberIds.firstNotNullOfOrNull { verdicts[it] },
                        ) { entity = r }
                    }
                }
                if (drones.isNotEmpty()) {
                    header(Texts.tr("Drones", "Droni"))
                    items(drones, key = { "d" + it.key }) { d ->
                        Tile("🛸", d.label, Texts.drone(d), if (d.remoteId) "RID" else "", d.lastMs, DRONE, verdicts["drone:${d.key}"]) { drone = d }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        Texts.tr(
                            "Your verdicts stay on the phone. “False alarm” silences that alert for 24 h and helps tune the thresholds.",
                            "I tuoi giudizi restano sul telefono. “Falso allarme” silenzia quell'allerta per 24 h e aiuta a tarare le soglie.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
    entity?.let { DeviceDialog(it) { entity = null } }
    threat?.let { ThreatDialog(it) { threat = null } }
    drone?.let { DroneDialog(it) { drone = null } }
}

private fun verdictLabel(v: Int) = when (v) {
    0 -> Texts.tr("✓ you said: false alarm", "✓ hai detto: falso allarme")
    1 -> Texts.tr("✓ you said: mine", "✓ hai detto: è mio")
    else -> Texts.tr("✓ you said: makes sense", "✓ hai detto: sensato")
}

@Composable
private fun Tile(
    icon: String,
    title: String,
    subtitle: String,
    value: String,
    lastMs: Long,
    color: Color,
    verdict: Int?,
    onClick: () -> Unit,
) {
    val ago = ((System.currentTimeMillis() - lastMs) / 60_000).let { if (it < 1) Texts.tr("now", "ora") else Texts.tr("$it min ago", "$it min fa") }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.13f))
            .clickable { onClick() }
            .padding(10.dp)
            .heightIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(value, color = color, fontWeight = FontWeight.SemiBold)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(ago, style = MaterialTheme.typography.labelSmall, color = color)
        verdict?.let { Text(verdictLabel(it), style = MaterialTheme.typography.labelSmall) }
    }
}

/** Verdict buttons for something that isn't a device entity (attack, drone). Stored as feedback under [key]. */
@Composable
private fun Verdict(key: String, score: Double, kind: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun send(label: Int) = scope.launch {
        RetrovisionApp.instance.db.dao().addFeedback(FeedbackRow(entityId = key, label = label, score = score, reasons = kind, timeMs = System.currentTimeMillis()))
        onDone()
    }
    Text(Texts.tr("Does this make sense?", "Ti sembra sensato?"), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { send(2) }) { Text(Texts.tr("Makes sense", "Sensato")) }
        OutlinedButton(onClick = { send(0) }) { Text(Texts.tr("False alarm", "Falso allarme")) }
    }
}

@Composable
private fun ThreatDialog(t: WifiThreats.Threat, onClose: () -> Unit) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("⚠ " + Texts.threatTitle(t.kind)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Texts.threatExplain(t), style = MaterialTheme.typography.bodySmall)
                Text(Texts.tr("Evidence", "Prove"), style = MaterialTheme.typography.titleSmall)
                val lines = buildList {
                    add(Texts.tr("Severity", "Gravità") + " %.0f%%".format(t.severity * 100) + " · " + Texts.tr("frames/networks", "frame/reti") + " ${t.count}")
                    add(Texts.tr("Heard ", "Sentito ") + fmt.format(Date(t.firstMs)) + " → " + fmt.format(Date(t.lastMs)))
                    if (t.channel > 0) add(Texts.tr("Channel ", "Canale ") + t.channel)
                    if (t.rssi != 0) add(Texts.tr("Median signal ", "Segnale mediano ") + "${t.rssi} dBm")
                    t.ssid?.let { add("SSID “$it”") }
                    t.bssid?.let { add("BSSID $it") }
                    if (t.radios > 0) add(Texts.tr("Distinct radios ", "Radio distinte ") + t.radios)
                }
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (t.ssids.isNotEmpty()) {
                    Text(Texts.tr("Network names", "Nomi di rete"), style = MaterialTheme.typography.titleSmall)
                    Text(t.ssids.joinToString("\n") { "“$it”" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                if (t.bssids.isNotEmpty()) {
                    Text(Texts.tr("Transmitter addresses", "Indirizzi dei trasmettitori"), style = MaterialTheme.typography.titleSmall)
                    Text(t.bssids.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Verdict(t.key, t.severity, t.kind.name, onClose)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
    )
}

@Composable
private fun DroneDialog(d: Drones.Drone, onClose: () -> Unit) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("🛸 " + d.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Texts.drone(d), style = MaterialTheme.typography.bodySmall)
                Text(Texts.tr("Heard ", "Sentito ") + fmt.format(Date(d.firstMs)) + " → " + fmt.format(Date(d.lastMs)), style = MaterialTheme.typography.bodySmall)
                d.selfId?.let { Text("Self-ID: $it", style = MaterialTheme.typography.bodySmall) }
                Text(d.addresses.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Verdict("drone:${d.key}", 1.0, "DRONE", onClose)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
    )
}
