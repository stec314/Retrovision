package dev.retrovision.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.RadarBlip
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Restrained cyberpunk palette: one cyan, one magenta accent, near-black field.
private object Neon {
    val bg = Color(0xFF05080C)
    val grid = Color(0xFF123A3A)
    val gridWeak = Color(0xFF0C2626)
    val cyan = Color(0xFF32E6FF)
    val magenta = Color(0xFFFF4FD8)
    val alert = Color(0xFFFF5C7A)
    val dim = Color(0xFF5A8CA0)
}

/**
 * Interactive radar. Radius = signal strength (centre = closest); angle is a real geographic
 * bearing only for blips with a pointer (estimated from movement), otherwise a stable placeholder.
 * Tap a blip to open its full details. A live board strip sits on top.
 */
@Composable
fun RadarView() {
    val frame by Collector.liveRadar.collectAsState()
    val conn by Collector.connection.collectAsState()
    val analysis by Collector.analysis.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val tr = rememberInfiniteTransition(label = "radar")
    val sweep by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val pulse by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.dim.toArgb(); textSize = 24f; typeface = Typeface.MONOSPACE }
    }

    // Screen position of every blip (shared by drawing and hit-testing).
    val placed: List<Pair<RadarBlip, Offset>> = remember(frame, sizePx) {
        if (sizePx.width == 0) emptyList()
        else {
            val cx = sizePx.width / 2f; val cy = sizePx.height / 2f
            val rMax = minOf(cx, cy) - 14f
            frame.blips.map { b ->
                val ang = Math.toRadians((b.bearingDeg ?: placeholderAngle(b.entityId)) - 90.0)
                val t = ((-35.0 - b.rssi) / (100.0 - 35.0)).coerceIn(0.0, 1.0)
                val r = (rMax * t).toFloat().coerceAtLeast(7f)
                b to Offset(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat())
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardStrip(conn)

        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF0A1A1A), Neon.bg)))
                .border(1.dp, Neon.grid.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .onSizeChanged { sizePx = it }
                .pointerInput(placed) {
                    detectTapGesturesCompat { p ->
                        val hit = placed.minByOrNull { (it.second - p).getDistance() }
                            ?.takeIf { (it.second - p).getDistance() < 48f }
                        selected = hit?.first?.entityId
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val cx = size.width / 2; val cy = size.height / 2
                val rMax = minOf(cx, cy) - 14f

                // rings (outer in magenta), with faint dBm band labels
                val bands = listOf(1f to "-55", 2f to "-78", 3f to "-100")
                for ((i, band) in bands.withIndex()) {
                    val rr = rMax * band.first / 3f
                    drawCircle(if (i == 2) Neon.magenta.copy(alpha = 0.35f) else Neon.grid, rr, Offset(cx, cy), style = Stroke(1.4f))
                    drawIntoCanvas { it.nativeCanvas.drawText(band.second, cx + 4f, cy - rr + 20f, labelPaint) }
                }
                drawLine(Neon.gridWeak, Offset(cx, cy - rMax), Offset(cx, cy + rMax), 1f)
                drawLine(Neon.gridWeak, Offset(cx - rMax, cy), Offset(cx + rMax, cy), 1f)

                // subtle scanlines
                var y = 0f
                while (y < size.height) { drawLine(Color.White.copy(alpha = 0.015f), Offset(0f, y), Offset(size.width, y), 1f); y += 4f }

                // sweep beam
                rotate(sweep, Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent, 0.07f to Neon.cyan.copy(alpha = 0.22f),
                            0.13f to Color.Transparent, 1f to Color.Transparent, center = Offset(cx, cy),
                        ),
                        startAngle = -90f, sweepAngle = 70f, useCenter = true,
                        topLeft = Offset(cx - rMax, cy - rMax), size = Size(rMax * 2, rMax * 2),
                    )
                }

                placed.forEach { (b, o) ->
                    val known = b.bearingDeg != null
                    val col = if (b.alert) Neon.alert else CategoryUi.color(b.category)
                    if (known) drawLine(col.copy(alpha = 0.45f), Offset(cx, cy), o, 1.5f)
                    if (b.alert) {
                        drawCircle(Neon.alert.copy(alpha = 0.28f * (1 - pulse)), 10f + pulse * 12f, o)
                        drawCircle(Neon.alert, 7f, o)
                    } else if (known) {
                        drawCircle(col.copy(alpha = 0.25f), 10f, o)
                        drawCircle(col, 6f, o)
                    } else {
                        drawCircle(col.copy(alpha = 0.8f), 6f, o, style = Stroke(2f))
                    }
                    if (b.entityId == selected) drawCircle(Neon.cyan, 16f, o, style = Stroke(2f))
                }

                drawCircle(Neon.cyan.copy(alpha = 0.22f), 10f, Offset(cx, cy))
                drawCircle(Neon.cyan, 4f, Offset(cx, cy))
            }
            Text("N", color = Neon.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
            Text(
                "${frame.blips.size} ◎",
                color = Neon.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }

        val withBearing = frame.blips.count { it.bearingDeg != null }
        Text(
            Texts.tr(
                "Tap a device for details. Radius = signal (centre = closest). Direction is real only with a pointer ($withBearing now); hollow = unknown. Red = alert.",
                "Tocca un dispositivo per i dettagli. Raggio = segnale (centro = più vicino). La direzione è reale solo con la freccia ($withBearing ora); vuoto = ignota. Rosso = allarme.",
            ),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (frame.movedM < 20) {
            Text(
                Texts.tr(
                    "Walk ~30 m for directions. A device with a steady signal while you move is moving with you.",
                    "Cammina ~30 m per le direzioni. Un dispositivo con segnale costante mentre ti muovi si sta muovendo con te.",
                ),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Tapped device → full details when analysed, otherwise a light live card.
    val sel = selected
    if (sel != null) {
        val report = analysis?.entities?.firstOrNull { it.entityId == sel }
        if (report != null) {
            DeviceDialog(report) { selected = null }
        } else {
            val b = frame.blips.firstOrNull { it.entityId == sel }
            if (b != null) RadarQuickInfo(b) { selected = null } else selected = null
        }
    }
}

@Composable
private fun BoardStrip(conn: dev.retrovision.app.ConnectionUi) {
    val s = conn.session
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A1416)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s == null) {
            Text(Texts.tr("No probe", "Nessuna sonda"), color = Neon.dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        } else {
            val h = probeHealth(s)
            Text(h.dot, color = h.color, fontSize = 14.sp)
            Column(Modifier.weight(1f)) {
                Text(s.info?.let { probeModel(it.probeType) } ?: "—", color = Neon.cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(
                    "Wi-Fi ${s.wifiObs} · BLE ${s.bleObs}" + (if (s.channel > 0) " · ch ${s.channel}" else ""),
                    color = Neon.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (s.channel > 0) Text("${"%.0f".format(s.chipTempC)}°C", color = h.color, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(h.text, color = h.color, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RadarQuickInfo(b: RadarBlip, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(b.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(CategoryUi.icon(b.category) + "  " + CategoryUi.label(b.category))
                Text("${b.rssi.toInt()} dBm" + (b.bearingDeg?.let { " · %.0f° (%.0f%%)".format(it, b.bearingConf * 100) } ?: " · " + Texts.tr("direction unknown", "direzione ignota")))
                Text(
                    Texts.tr("Not analysed yet — details appear after the next analysis pass.", "Non ancora analizzato — i dettagli compaiono dopo la prossima analisi."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
    )
}

/** Stable per-device angle (deg) for devices without a measured bearing, so they don't jump around. */
private fun placeholderAngle(id: String): Double {
    var h = 2166136261.toInt()
    for (ch in id) { h = h xor ch.code; h *= 16777619 }
    return (abs(h) % 360).toDouble()
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Offset) -> Unit) {
    androidx.compose.foundation.gestures.detectTapGestures(onTap = onTap)
}
