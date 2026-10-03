// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.probe.Phase
import dev.retrovision.core.analysis.Levels
import dev.retrovision.core.analysis.Verdict

/** Colours that read on both the dark and the light theme, always paired with an icon and words. */
@Composable
internal fun verdictColor(s: Verdict.State): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (s) {
        Verdict.State.STRONG -> MaterialTheme.colorScheme.error
        Verdict.State.WORTH_A_LOOK -> if (dark) Color(0xFFFFB74D) else Color(0xFF8A4B00)
        Verdict.State.CLEAR -> if (dark) Color(0xFF7CF29A) else Color(0xFF1B6E37)
        Verdict.State.CLEAR_PARTIAL, Verdict.State.CANT_TELL -> if (dark) Color(0xFFFFD166) else Color(0xFF7A5D00)
        Verdict.State.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Builds the current verdict from the live state. Recomputed every few seconds (GPS age, coverage). */
@Composable
internal fun rememberVerdict(): Verdict.Result {
    val running by Collector.running.collectAsState()
    // The connection state changes on every probe counter update; only the phase matters here.
    val connState = Collector.connection.collectAsState()
    val streaming by remember { derivedStateOf { connState.value.session?.phase == Phase.STREAMING } }
    val bleOn by Collector.phoneBleActive.collectAsState()
    val fix by Collector.location.collectAsState()
    val analysis by Collector.analysis.collectAsState()
    val threats by Collector.threats.collectAsState()
    val load by Collector.analysisLoad.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); now = System.currentTimeMillis() } }
    val prefs = RetrovisionApp.instance.prefs
    val a = analysis
    val lookbackMs = prefs.lookbackMin * 60_000L
    val entityLevels = remember(a) { a?.alerts.orEmpty().map { Levels.of(it) } }
    val threatLevels = remember(threats) { threats.map { Levels.of(it) } }
    return Verdict.of(
        Verdict.Inputs(
            running = running,
            probeStreaming = streaming,
            phoneBleListening = bleOn,
            gpsAgeMs = fix?.let { now - it.timeMs },
            gpsAccuracyM = fix?.accuracyM,
            maxGpsAccuracyM = prefs.maxFixAccuracyM.toFloat(),
            coveredMs = if (load.oldestMs > 0) minOf(lookbackMs, now - load.oldestMs) else 0L,
            analysisAgeMs = a?.let { now - it.nowMs },
            truncated = load.truncated,
            entityLevels = entityLevels,
            threatLevels = threatLevels,
        ),
    )
}

/** The first thing on Status: one answer, what it is based on, and what is missing. */
@Composable
fun VerdictCard() {
    val v = rememberVerdict()
    val color = verdictColor(v.state)
    val analysis by Collector.analysis.collectAsState()
    val title = Texts.verdictTitle(v.state)
    Panel(
        modifier = Modifier.semantics { contentDescription = title + ". " + Texts.verdictLine(v) },
        tint = color,
        onClick = { AlertsNav.open.value = true },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Icon(verdictIcon(v.state), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Overline(Texts.tr("Right now", "Adesso"))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
            }
        }
        Text(Texts.verdictLine(v), style = MaterialTheme.typography.bodyMedium)

        // The findings, in words (no percentages here).
        val alerts = remember(analysis) { analysis?.alerts.orEmpty().sortedByDescending { Levels.of(it).ordinal } }
        if (alerts.isNotEmpty()) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            alerts.take(4).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelPill(Levels.of(r))
                    Text(Texts.entityLabel(r), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (alerts.size > 4) Text(Texts.tr("+${alerts.size - 4} more", "+${alerts.size - 4} altri"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (v.gaps.isNotEmpty() && v.state != Verdict.State.STOPPED) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Overline(Texts.tr("Not visible right now", "Ora non visibile"))
            v.gaps.forEach { g -> Text(Texts.gap(g), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        CoverageLine()
    }
}

private fun verdictIcon(s: Verdict.State) = when (s) {
    Verdict.State.CLEAR -> androidx.compose.material.icons.Icons.Filled.CheckCircle
    Verdict.State.STRONG, Verdict.State.WORTH_A_LOOK -> androidx.compose.material.icons.Icons.Filled.Warning
    Verdict.State.STOPPED -> androidx.compose.material.icons.Icons.Filled.PlayArrow
    else -> androidx.compose.material.icons.Icons.Filled.Info
}

/** "Probe ✓ · GPS ±4 m ✓ · 47 min analysed": the basis of the verdict in one line. */
@Composable
private fun CoverageLine() {
    val connState = Collector.connection.collectAsState()
    val bleOn by Collector.phoneBleActive.collectAsState()
    val fix by Collector.location.collectAsState()
    val load by Collector.analysisLoad.collectAsState()
    val running by Collector.running.collectAsState()
    if (!running) return
    val probe by remember { derivedStateOf { connState.value.session?.phase == Phase.STREAMING } }
    val parts = buildList {
        add(Texts.tr("Probe", "Sonda") + if (probe) " ✓" else " ✗")
        if (!probe) add(Texts.tr("Phone BT", "BT telefono") + if (bleOn) " ✓" else " ✗")
        add(fix?.let { "GPS ±${it.accuracyM.toInt()} m" } ?: "GPS ✗")
        if (load.oldestMs > 0) {
            val lookback = RetrovisionApp.instance.prefs.lookbackMin
            val min = minOf(lookback.toLong(), (System.currentTimeMillis() - load.oldestMs) / 60_000)
            add(Texts.tr("$min min analysed", "$min min analizzati"))
        }
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
