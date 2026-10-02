package dev.retrovision.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A radar of nearby devices. Honest by construction:
 *  - the RADIUS is signal strength (strong = near the centre) — a real proximity cue;
 *  - the ANGLE is a real geographic bearing ONLY for devices whose direction movement could
 *    estimate (solid blip, pointer); otherwise the device sits on its strength ring at a
 *    stable placeholder angle (hollow blip) and its direction is unknown;
 *  - devices that raised an alert pulse in red.
 * North is up. A single antenna cannot measure true direction, so angle is never invented.
 */
@Composable
fun RadarView() {
    val frame by Collector.liveRadar.collectAsState()
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )

    val grid = Color(0xFF1E3A2E)
    val gridBright = Color(0xFF2E5A46)
    val beam = Color(0xFF3DDCFF)
    val me = Color(0xFF3DDCFF)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF07120D)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp)) {
                val cx = size.width / 2
                val cy = size.height / 2
                val rMax = minOf(cx, cy)
                // rings at 1/3, 2/3, 3/3
                for (k in 1..3) {
                    drawCircle(if (k == 3) gridBright else grid, radius = rMax * k / 3f, center = Offset(cx, cy), style = Stroke(1.5f))
                }
                drawLine(grid, Offset(cx, cy - rMax), Offset(cx, cy + rMax), 1f)
                drawLine(grid, Offset(cx - rMax, cy), Offset(cx + rMax, cy), 1f)

                // sweeping beam
                rotate(sweep, Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent, 0.08f to beam.copy(alpha = 0.28f), 0.12f to Color.Transparent,
                            1f to Color.Transparent, center = Offset(cx, cy),
                        ),
                        startAngle = -90f, sweepAngle = 60f, useCenter = true,
                        topLeft = Offset(cx - rMax, cy - rMax),
                        size = androidx.compose.ui.geometry.Size(rMax * 2, rMax * 2),
                    )
                }

                // RSSI (dBm) -> radius: -35 at centre, -100 at the rim
                fun rssiRadius(rssi: Double): Float {
                    val t = ((-35.0 - rssi) / (100.0 - 35.0)).coerceIn(0.0, 1.0)
                    return (rMax * t).toFloat()
                }

                frame.blips.forEach { b ->
                    val known = b.bearingDeg != null
                    val angleDeg = b.bearingDeg ?: placeholderAngle(b.entityId)
                    val rad = Math.toRadians(angleDeg - 90) // 0° = north = up
                    val r = rssiRadius(b.rssi).coerceAtLeast(6f)
                    val px = cx + (r * cos(rad)).toFloat()
                    val py = cy + (r * sin(rad)).toFloat()
                    val color = CategoryUi.color(b.category)
                    if (b.alert) {
                        val rr = 10f + pulse * 10f
                        drawCircle(Color(0xFFFF5C7A).copy(alpha = 0.30f * (1 - pulse)), rr + 6f, Offset(px, py))
                        drawCircle(Color(0xFFFF5C7A), 7f, Offset(px, py))
                    } else if (known) {
                        drawCircle(color, 6f, Offset(px, py))
                    } else {
                        drawCircle(color.copy(alpha = 0.7f), 6f, Offset(px, py), style = Stroke(2f))
                    }
                    // pointer for a known bearing
                    if (known) {
                        val base = if (b.alert) Color(0xFFFF5C7A) else color
                        drawLine(base.copy(alpha = 0.6f), Offset(cx, cy), Offset(px, py), 2f)
                    }
                }

                // me, at the centre
                drawCircle(me.copy(alpha = 0.25f), 9f, Offset(cx, cy))
                drawCircle(me, 4f, Offset(cx, cy))
            }
            Text(
                "N", color = Color(0xFF5A8C76), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            )
        }
        val withBearing = frame.blips.count { it.bearingDeg != null }
        Text(
            Texts.tr(
                "Radius = signal strength (centre = closest). Direction is real only for the ${withBearing} device(s) with a pointer — estimated while you move; a hollow dot means direction unknown. Red = alert.",
                "Raggio = intensità del segnale (centro = più vicino). La direzione è reale solo per i ${withBearing} dispositivi con la freccia — stimata mentre ti muovi; un pallino vuoto significa direzione ignota. Rosso = allarme.",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (frame.movedM < 20) {
            Text(
                Texts.tr(
                    "Walk ~30 m to let the app estimate directions. A device that keeps a steady signal while you move is moving with you — shown without a direction.",
                    "Cammina ~30 m per far stimare le direzioni. Un dispositivo con segnale costante mentre ti muovi si sta muovendo con te — mostrato senza direzione.",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stable per-device angle (deg) for devices without a measured bearing, so they don't jump around. */
private fun placeholderAngle(id: String): Double {
    var h = 2166136261.toInt()
    for (ch in id) { h = h xor ch.code; h *= 16777619 }
    return (abs(h) % 360).toDouble()
}
