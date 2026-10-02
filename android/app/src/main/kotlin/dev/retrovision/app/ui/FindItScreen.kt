// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import kotlinx.coroutines.delay

/**
 * "Find it" mode: a hotter/colder meter to physically locate a transmitter (a planted tracker,
 * say). Move around; the bar fills and beeps faster as the device's signal gets stronger.
 * This cannot point — it only tells you "warmer/colder" as you move.
 */
@Composable
fun FindItDialog(entityId: String, label: String, onClose: () -> Unit) {
    val frame by Collector.liveRadar.collectAsState()
    val blip = frame.blips.firstOrNull { it.entityId == entityId }
    val rssi = blip?.rssi
    var beep by remember { mutableStateOf(true) }

    // -35 dBm (very close) -> 1.0, -100 dBm (far/lost) -> 0.0
    val heat = rssi?.let { ((it + 100.0) / (100.0 - 35.0)).coerceIn(0.0, 1.0) } ?: 0.0
    val anim by animateFloatAsState(heat.toFloat(), label = "heat")
    val color by animateColorAsState(
        when {
            rssi == null -> Color(0xFF445566)
            heat > 0.75 -> Color(0xFFFF4D4D)
            heat > 0.5 -> Color(0xFFFF9E3D)
            heat > 0.25 -> Color(0xFFFFC857)
            else -> Color(0xFF3DDCFF)
        },
        label = "color",
    )

    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    LaunchedEffect(beep) {
        while (beep) {
            val r = rssi
            if (r == null) {
                delay(800)
            } else {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                // interval shrinks from ~900 ms (cold) to ~120 ms (hot)
                delay((900 - 780 * heat).toLong().coerceIn(120, 900))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Texts.tr("Find it", "Trovalo") + " · $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                        val cx = size.width / 2; val cy = size.height
                        val maxR = size.height
                        // concentric arcs that light up with heat
                        for (k in 1..5) {
                            val on = anim >= (k - 0.5f) / 5f
                            drawCircle(
                                if (on) color else Color(0xFF223040),
                                radius = maxR * k / 5f, center = Offset(cx, cy),
                                style = Stroke(width = if (on) 10f else 4f),
                            )
                        }
                    }
                    Text(
                        rssi?.let { "${it.toInt()} dBm" } ?: Texts.tr("no signal", "nessun segnale"),
                        color = color, fontFamily = FontFamily.Monospace, fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 8.dp).align(Alignment.BottomCenter),
                    )
                }
                Text(
                    when {
                        rssi == null -> Texts.tr("Not hearing it right now. Move around.", "Non lo sento ora. Spostati.")
                        heat > 0.75 -> Texts.tr("Very close — you're on top of it.", "Vicinissimo — ci sei sopra.")
                        heat > 0.5 -> Texts.tr("Warm — getting closer.", "Caldo — ti stai avvicinando.")
                        heat > 0.25 -> Texts.tr("Cool — keep moving.", "Freddo — continua a muoverti.")
                        else -> Texts.tr("Cold — far away.", "Gelido — lontano.")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = color,
                )
                Text(
                    Texts.tr(
                        "Signal strength only — it cannot point a direction. Walk until it peaks, then search there.",
                        "Solo intensità del segnale — non indica una direzione. Cammina finché non è al massimo, poi cerca lì.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.tr("Beep", "Bip"))
                    Switch(checked = beep, onCheckedChange = { beep = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Done", "Fatto")) } },
    )
}
