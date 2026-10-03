// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.retrovision.app.Collector
import kotlinx.coroutines.delay

private const val HISTORY_MS = 60_000L
private const val LOST_MS = 8_000L

/**
 * "Find it": a hotter/colder meter to physically locate a transmitter (a planted tracker, say).
 * It reads every raw frame of the device (no GPS needed, no smoothing on the graph), so you see
 * the signal move as you move. It cannot point a direction: walk, turn, and follow the peak.
 */
@Composable
fun FindItDialog(entityId: String, label: String, ids: Set<String> = setOf(entityId), onClose: () -> Unit) {
    val ctx = LocalContext.current
    val samples = remember { mutableStateListOf<Pair<Long, Int>>() }
    var smooth by remember { mutableStateOf<Double?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var beep by remember { mutableStateOf(true) }
    var vibrate by remember { mutableStateOf(true) }

    // Listen to this device only, for as long as the screen is open.
    DisposableEffect(ids) {
        Collector.findTarget = ids
        onDispose { Collector.findTarget = emptySet() }
    }
    LaunchedEffect(ids) {
        Collector.findSamples.collect { (t, r) ->
            samples += t to r
            // Light smoothing for the meter only: phones and probes jump ±6 dB frame to frame.
            smooth = smooth?.let { it * 0.6 + r * 0.4 } ?: r.toDouble()
            val cut = t - HISTORY_MS
            while (samples.isNotEmpty() && samples.first().first < cut) samples.removeAt(0)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }

    val last = samples.lastOrNull()
    val lost = last == null || now - last.first > LOST_MS
    val level = if (lost) null else smooth
    // -35 dBm (on top of it) -> 1.0, -95 dBm (barely heard) -> 0.0
    val heat = level?.let { ((it + 95.0) / 60.0).coerceIn(0.0, 1.0) } ?: 0.0
    val anim by animateFloatAsState(heat.toFloat(), label = "heat")
    val color by animateColorAsState(heatColor(if (lost) null else heat), label = "color")

    // Trend: the last 3 s against the 3 s before them.
    val recent = samples.filter { now - it.first <= 3_000L }.map { it.second }
    val before = samples.filter { now - it.first in 3_001L..6_000L }.map { it.second }
    val trend = if (recent.size >= 2 && before.size >= 2) recent.average() - before.average() else 0.0
    val peak = samples.maxByOrNull { it.second }
    val rate = samples.count { now - it.first <= 5_000L } / 5.0

    // Sound and vibration pulse faster as it gets closer (Geiger-counter style).
    val heatNow by rememberUpdatedState(heat)
    val lostNow by rememberUpdatedState(lost)
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    val vib = remember { vibrator(ctx) }
    DisposableEffect(Unit) { onDispose { tone?.release(); vib?.cancel() } }
    LaunchedEffect(beep, vibrate) {
        while (beep || vibrate) {
            if (lostNow) { delay(500); continue }
            val h = heatNow
            if (beep) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            if (vibrate) vib?.let { v ->
                val ms = (25 + 45 * h).toLong()
                if (Build.VERSION.SDK_INT >= 26) {
                    val amp = (60 + 195 * h).toInt().coerceIn(1, 255)
                    runCatching { v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE)) }
                } else {
                    @Suppress("DEPRECATION") runCatching { v.vibrate(ms) }
                }
            }
            // ~1 s apart when far, ~0.1 s when on top of it.
            delay((1_000 - 900 * h).toLong().coerceIn(100, 1_000))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, Texts.tr("Back", "Indietro"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(Texts.tr("Find it", "Trovalo"), style = MaterialTheme.typography.titleLarge)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Meter: arcs that light up with heat; the reading sits below, never on top.
                    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                        val c = Offset(size.width / 2, size.height)
                        val maxR = minOf(size.width / 2, size.height) - 6.dp.toPx()
                        for (k in 1..6) {
                            val on = anim >= (k - 0.5f) / 6f
                            val r = maxR * k / 6f
                            drawArc(
                                if (on) color else Color(0xFF223040),
                                startAngle = 180f, sweepAngle = 180f, useCenter = false,
                                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                style = Stroke(width = if (on) 7.dp.toPx() else 2.dp.toPx()),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                        Text(
                            level?.let { "%.0f".format(it) } ?: "—",
                            color = color, fontFamily = FontFamily.Monospace, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(" dBm", color = color, fontFamily = FontFamily.Monospace, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
                        Text(
                            when {
                                lost -> ""
                                trend > 2.5 -> "  ▲"
                                trend < -2.5 -> "  ▼"
                                else -> "  ●"
                            },
                            color = color, fontSize = 28.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        when {
                            lost -> Texts.tr("Not heard right now. Move around slowly.", "Non lo sento ora. Muoviti lentamente.")
                            heat > 0.8 -> Texts.tr("Very close: within a metre or two. Search here.", "Vicinissimo: entro un metro o due. Cerca qui.")
                            heat > 0.55 -> Texts.tr("Close: a few metres.", "Vicino: pochi metri.")
                            heat > 0.3 -> Texts.tr("Nearby: keep going where it rises.", "Nei paraggi: continua dove sale.")
                            else -> Texts.tr("Far or behind a wall.", "Lontano o dietro un muro.")
                        } + if (!lost) " " + when {
                            trend > 2.5 -> Texts.tr("Getting warmer.", "Ti avvicini.")
                            trend < -2.5 -> Texts.tr("Getting colder: turn back.", "Ti allontani: torna indietro.")
                            else -> Texts.tr("Steady.", "Stabile.")
                        } else "",
                        style = MaterialTheme.typography.titleMedium, color = color,
                    )

                    // Every raw reading of the last minute (dots) with the meter's smoothed line.
                    Overline(Texts.tr("Signal, last 60 s", "Segnale, ultimi 60 s"))
                    SignalGraph(samples.toList(), now, color)
                    Text(
                        listOfNotNull(
                            peak?.let { Texts.tr("peak ", "picco ") + "${it.second} dBm " + Texts.tr("${(now - it.first) / 1000} s ago", "${(now - it.first) / 1000} s fa") },
                            "%.1f ".format(rate) + Texts.tr("readings/s", "letture/s"),
                            last?.let { Texts.tr("last ", "ultima ") + "${(now - it.first) / 1000} s" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Panel {
                        SwitchRow(Texts.tr("Beep", "Suono"), beep) { beep = it }
                        SwitchRow(Texts.tr("Vibrate", "Vibrazione"), vibrate) { vibrate = it }
                    }
                    Text(
                        Texts.tr(
                            "Signal strength only: it cannot point a direction. Walk slowly, turn around (your body blocks the signal), and follow ▲. Where it peaks, check bags, pockets, car seats and wheel arches. Few readings per second = the device transmits rarely: move more slowly.",
                            "Solo intensità del segnale: non indica una direzione. Cammina lentamente, girati (il corpo blocca il segnale) e segui ▲. Dove è al massimo, controlla borse, tasche, sedili e passaruota. Poche letture al secondo = il dispositivo trasmette di rado: muoviti più piano.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun SignalGraph(samples: List<Pair<Long, Int>>, now: Long, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val lo = -100f; val hi = -30f
            fun x(t: Long) = size.width * (1f - (now - t).toFloat() / HISTORY_MS)
            fun y(r: Float) = size.height * (1f - ((r - lo) / (hi - lo)).coerceIn(0f, 1f))
            for (db in listOf(-90, -70, -50)) drawLine(grid, Offset(0f, y(db.toFloat())), Offset(size.width, y(db.toFloat())), 1f)
            if (samples.isEmpty()) return@Canvas
            var s: Float? = null
            val path = Path()
            samples.forEachIndexed { i, (t, r) ->
                drawCircle(color.copy(alpha = 0.45f), 2.5.dp.toPx(), Offset(x(t), y(r.toFloat())))
                s = s?.let { it * 0.6f + r * 0.4f } ?: r.toFloat()
                if (i == 0) path.moveTo(x(t), y(s!!)) else path.lineTo(x(t), y(s!!))
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx()))
        }
        Text("-50", style = MaterialTheme.typography.labelSmall, color = grid, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        Text("-90", style = MaterialTheme.typography.labelSmall, color = grid, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

private fun heatColor(h: Double?): Color = when {
    h == null -> Color(0xFF5A6B7D)
    h > 0.8 -> Color(0xFFFF4D4D)
    h > 0.55 -> Color(0xFFFF9E3D)
    h > 0.3 -> Color(0xFFFFC857)
    else -> Color(0xFF3DDCFF)
}

private fun vibrator(ctx: Context): Vibrator? = runCatching {
    if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
}.getOrNull()
