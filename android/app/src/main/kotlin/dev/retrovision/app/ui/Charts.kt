// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.CatalogFlags
import dev.retrovision.app.data.ChartsData
import dev.retrovision.app.data.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// Chart conventions (see the dataviz rules): one y-axis per chart (signal and movement are two
// stacked charts on one time axis, never a dual axis); magnitudes in one hue, light to dark, with a
// scale legend; the error colour only for flagged devices and always with ⚠; hairline guides.

private val LABEL_W = 112.dp

@Composable
private fun ink() = MaterialTheme.colorScheme.onSurfaceVariant
@Composable
private fun hair() = MaterialTheme.colorScheme.outlineVariant

/** Range chips shared by the charts. */
@Composable
private fun RangeChips(options: List<Pair<Long, String>>, value: Long, onChange: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(value == v, { onChange(v) }, label = { Text(l) }) }
    }
}

/** Time ticks under a time axis (4 labels), aligned with the plot area right of the labels. */
@Composable
private fun TimeTicks(fromMs: Long, toMs: Long, withDate: Boolean) {
    val f = if (withDate) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) else DateFormat.getTimeInstance(DateFormat.SHORT)
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(LABEL_W))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..3) Text(f.format(Date(fromMs + (toMs - fromMs) * i / 3)), style = MaterialTheme.typography.labelSmall, color = ink())
        }
    }
}

private fun DrawScope.xOf(t: Long, from: Long, to: Long) = ((t - from).toFloat() / (to - from).coerceAtLeast(1)) * size.width

/** Your stays as faint vertical bands behind a lane: routine places grey, other places tinted. */
private fun DrawScope.stayBands(bands: List<ChartsData.Band>, from: Long, to: Long, routine: Color, other: Color) {
    for (b in bands) {
        val x0 = xOf(b.fromMs, from, to).coerceIn(0f, size.width)
        val x1 = xOf(b.toMs, from, to).coerceIn(0f, size.width)
        if (x1 - x0 >= 0.5f) drawRect(if (b.routine) routine else other, Offset(x0, 0f), Size(x1 - x0, size.height))
    }
}

// ---------------------------------------------------------------------------------------------
// 1 + 2. Device timeline with your stays, and receiver coverage

/**
 * Devices × time, aligned with your day. A neighbour lights up only inside the "home" bands; a
 * device sharing your route shows once; a device that follows you lights up in bands at different
 * places. The coverage rows below say when nothing could have been heard (probe off, no GPS), so a
 * gap is never read as an absence.
 */
@Composable
fun DeviceTimelineSection() {
    val app = RetrovisionApp.instance
    val analysis = Collector.analysis.value
    var span by remember { mutableLongStateOf(24 * 3_600_000L) }
    var data by remember { mutableStateOf<ChartsData.Timeline?>(null) }
    var open by remember { mutableStateOf<ArchiveItem?>(null) }
    var report by remember { mutableStateOf<dev.retrovision.core.analysis.EntityReport?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(span) {
        data = null
        data = withContext(Dispatchers.IO) {
            val dao = app.db.dao()
            val now = System.currentTimeMillis()
            val from = now - span
            val specs = LinkedHashMap<String, ChartsData.LaneSpec>()
            val a = Collector.analysis.value
            val flagged = a?.entities.orEmpty().filter { it.alert || it.score >= 0.5 }.take(10)
            for (r in flagged) specs[r.entityId] = ChartsData.LaneSpec(r.entityId, (r.memberIds + r.entityId).toList(), Texts.entityLabel(r), r.alert)
            // Their companions (same person or car): a lane each, so the whole group is in view.
            for (r in flagged) a?.groupOf?.get(r.entityId)?.members?.forEach { id ->
                if (id !in specs && specs.size < 16) specs[id] = ChartsData.LaneSpec(id, listOf(id), "👥 " + anyLabel(id), false)
            }
            for (c in dao.catalogFlagged(from, CatalogFlags.ALERTED or CatalogFlags.SUSPICIOUS, 12)) {
                if (specs.size >= 16) break
                if (c.entityId in specs) continue
                val ids = (c.members.split('\n').filter { it.isNotBlank() } + c.entityId).distinct()
                specs[c.entityId] = ChartsData.LaneSpec(c.entityId, ids, ArchiveItem.of(c).title, (c.flags and CatalogFlags.ALERTED) != 0)
            }
            ChartsData.timeline(dao, from, now, specs.values.toList(), dao.familiarNow().map { it.toModel() }, app.prefs.maxFixAccuracyM)
        }
    }

    RangeChips(listOf(6 * 3_600_000L to "6 h", 24 * 3_600_000L to "24 h", 7 * 86_400_000L to Texts.tr("7 days", "7 giorni")), span) { span = it }
    Text(
        Texts.tr(
            "Each row is a device: a mark wherever it was heard. Behind it, your stays: grey at routine places, tinted elsewhere. A device lit up in tinted stays at different places is the one to look at.",
            "Ogni riga è un dispositivo: un segno dove è stato sentito. Dietro, le tue soste: grigie nei luoghi abituali, colorate altrove. Un dispositivo acceso in soste colorate in luoghi diversi è quello da guardare.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    val d = data
    if (d == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val routineC = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val otherC = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
    val hairC = hair()

    // Your day.
    LaneRow(Texts.tr("You", "Tu"), false, null) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            for (b in d.bands) {
                val x0 = xOf(b.fromMs, d.fromMs, d.toMs).coerceIn(0f, size.width)
                val x1 = xOf(b.toMs, d.fromMs, d.toMs).coerceIn(0f, size.width)
                drawRoundRect(
                    if (b.routine) Color.Gray.copy(alpha = 0.45f) else otherC.copy(alpha = 0.55f),
                    Offset(x0, 2f), Size(maxOf(2f, x1 - x0), size.height - 4f), CornerRadius(4f, 4f),
                )
            }
        }
    }
    if (d.lanes.isEmpty()) Text(
        Texts.tr("No device flagged in this period: nothing to draw.", "Nessun dispositivo segnalato in questo periodo: niente da disegnare."),
        style = MaterialTheme.typography.bodySmall, color = ink(),
    )
    d.lanes.forEach { lane ->
        LaneRow(lane.spec.label, lane.spec.flagged, onClick = {
            scope.launch {
                val c = withContext(Dispatchers.IO) { app.db.dao().catalogRow(lane.spec.key) }
                if (c != null) open = ArchiveItem.of(c)
                else analysis?.entities?.firstOrNull { it.entityId == lane.spec.key }?.let { report = it }
            }
        }) {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                stayBands(d.bands, d.fromMs, d.toMs, routineC, otherC)
                drawLine(hairC, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                val c = if (lane.spec.flagged) error else primary
                for (m in lane.marks) {
                    val x0 = xOf(m[0], d.fromMs, d.toMs); val x1 = xOf(m[1], d.fromMs, d.toMs)
                    drawRoundRect(c, Offset(x0, 4f), Size(maxOf(2f, x1 - x0), size.height - 8f), CornerRadius(2f, 2f))
                }
            }
        }
    }
    // Coverage: when something could have been heard at all.
    Overline(Texts.tr("Listening", "In ascolto"), Modifier.padding(top = 6.dp))
    val cov = d.coverage
    listOf(
        Texts.tr("Probe", "Sonda") to cov.probe,
        Texts.tr("Phone BT", "BT telefono") to cov.phone,
        Texts.tr("GPS good", "GPS buono") to cov.gps,
    ).forEach { (label, on) ->
        LaneRow(label, false, null) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val w = size.width / on.size.coerceAtLeast(1)
                drawRect(hairC, Offset.Zero, size)
                on.forEachIndexed { i, b -> if (b) drawRect(primary.copy(alpha = 0.55f), Offset(i * w, 0f), Size(w + 0.5f, size.height)) }
            }
        }
    }
    TimeTicks(d.fromMs, d.toMs, withDate = d.toMs - d.fromMs > 24 * 3_600_000L)
    Text(
        Texts.tr(
            "Blank in “Listening” = nothing could be heard then (probe off or unplugged, no GPS): a device missing there is unknown, not absent.",
            "Vuoto in “In ascolto” = in quel momento non si poteva sentire nulla (sonda spenta o scollegata, niente GPS): un dispositivo che manca lì è sconosciuto, non assente.",
        ),
        style = MaterialTheme.typography.labelSmall, color = ink(),
    )
    open?.let { h -> ArchiveDetailDialog(h) { open = null } }
    report?.let { r -> DeviceDialog(r) { report = null } }
}

@Composable
private fun LaneRow(label: String, flagged: Boolean, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (flagged) "⚠ " else "") + label,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (onClick != null) MaterialTheme.colorScheme.onSurface else ink(),
            modifier = Modifier.width(LABEL_W).padding(end = 6.dp).let { if (onClick != null) it.clickable { onClick() } else it },
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

// ---------------------------------------------------------------------------------------------
// 3 + 4. Per device: signal next to your movement, and the week × hour habit

/**
 * Two charts on one time axis (never one chart with two y-axes): the device's signal, and how far
 * you had moved. A steady signal while the distance climbs = it travels with you; a signal that
 * fades as you go = you left it behind.
 */
@Composable
fun DeviceChartsSection(ids: List<String>, lastMs: Long) {
    val app = RetrovisionApp.instance
    var span by remember { mutableLongStateOf(2 * 3_600_000L) }
    var sig by remember(ids) { mutableStateOf<ChartsData.Signal?>(null) }
    var wh by remember(ids) { mutableStateOf<ChartsData.WeekHour?>(null) }
    LaunchedEffect(ids, span) {
        sig = null
        sig = withContext(Dispatchers.IO) { ChartsData.signal(app.db.dao(), ids, lastMs - span, lastMs + 5 * 60_000L) }
    }
    LaunchedEffect(ids) { wh = withContext(Dispatchers.IO) { ChartsData.weekHour(app.db.dao(), ids) } }

    Overline(Texts.tr("Signal and your movement", "Segnale e tuoi spostamenti"))
    RangeChips(listOf(3_600_000L to "1 h", 2 * 3_600_000L to "2 h", 6 * 3_600_000L to "6 h", 24 * 3_600_000L to "24 h"), span) { span = it }
    val s = sig
    if (s == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else SignalCharts(s)
    Text(
        Texts.tr(
            "Up to its last sighting. Steady signal while your distance climbs: it moves with you. Signal fading as you go: you left it behind.",
            "Fino all'ultimo avvistamento. Segnale stabile mentre la distanza sale: si muove con te. Segnale che cala mentre vai: l'hai lasciato indietro.",
        ),
        style = MaterialTheme.typography.labelSmall, color = ink(),
    )
    Overline(Texts.tr("When it is around (days per weekday and hour)", "Quando c'è (giorni per giorno della settimana e ora)"), Modifier.padding(top = 8.dp))
    val w = wh
    if (w == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else WeekHourChart(w)
}

@Composable
private fun SignalCharts(s: ChartsData.Signal) {
    val primary = MaterialTheme.colorScheme.primary
    val hairC = hair()
    val labelC = ink()
    if (s.rssi.isEmpty()) { Text(Texts.tr("Not heard in this span.", "Non sentito in questo intervallo."), style = MaterialTheme.typography.bodySmall); return }
    // Signal: −100 (bottom) to −30 dBm (top).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(44.dp)) {
            Text("−30", style = MaterialTheme.typography.labelSmall, color = labelC)
            Box(Modifier.height(72.dp))
            Text("−100", style = MaterialTheme.typography.labelSmall, color = labelC)
        }
        Canvas(Modifier.weight(1f).height(110.dp)) {
            for (v in listOf(-30, -65, -100)) {
                val y = size.height * (-30 - v) / 70f
                drawLine(hairC, Offset(0f, y), Offset(size.width, y), 1f)
            }
            for ((t, r) in s.rssi) {
                val x = ((t - s.fromMs).toFloat() / (s.toMs - s.fromMs)) * size.width
                val y = size.height * (-30 - r.coerceIn(-100, -30)) / 70f
                drawCircle(primary, 3f, Offset(x, y))
            }
        }
    }
    Text("dBm", style = MaterialTheme.typography.labelSmall, color = labelC)
    // Distance you had travelled since the start of the span.
    val maxM = s.travelled.maxOfOrNull { it.second }?.coerceAtLeast(100.0) ?: 100.0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(44.dp)) {
            Text(if (maxM >= 1000) "%.1f km".format(maxM / 1000) else "${maxM.toInt()} m", style = MaterialTheme.typography.labelSmall, color = labelC)
            Box(Modifier.height(36.dp))
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelC)
        }
        Canvas(Modifier.weight(1f).height(70.dp)) {
            drawLine(hairC, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            var prev: Offset? = null
            for ((t, m) in s.travelled) {
                val p = Offset(((t - s.fromMs).toFloat() / (s.toMs - s.fromMs)) * size.width, size.height - (m / maxM).toFloat() * size.height)
                prev?.let { drawLine(primary, it, p, 4f) }
                prev = p
            }
        }
    }
    Text(Texts.tr("distance you moved", "distanza che hai percorso"), style = MaterialTheme.typography.labelSmall, color = labelC)
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(44.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            val f = DateFormat.getTimeInstance(DateFormat.SHORT)
            for (i in 0..2) Text(f.format(Date(s.fromMs + (s.toMs - s.fromMs) * i / 2)), style = MaterialTheme.typography.labelSmall, color = labelC)
        }
    }
}

/** 7 × 24 grid, one hue light to dark by days heard; tap a cell for its value. */
@Composable
private fun WeekHourChart(w: ChartsData.WeekHour) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val labelC = ink()
    val max = w.days.maxOrNull()?.coerceAtLeast(1) ?: 1
    var picked by remember(w) { mutableIntStateOf(-1) }
    val days = listOf(Texts.tr("Mon", "Lun"), Texts.tr("Tue", "Mar"), Texts.tr("Wed", "Mer"), Texts.tr("Thu", "Gio"), Texts.tr("Fri", "Ven"), Texts.tr("Sat", "Sab"), Texts.tr("Sun", "Dom"))
    if (w.totalDays == 0) { Text(Texts.tr("No stored sightings.", "Nessun avvistamento salvato."), style = MaterialTheme.typography.bodySmall); return }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(32.dp)) { days.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = labelC, modifier = Modifier.height(16.dp)) } }
        Canvas(
            Modifier.weight(1f).height(112.dp).pointerInput(w) {
                detectTapGestures { o ->
                    val col = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                    val row = (o.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                    picked = row * 24 + col
                }
            },
        ) {
            val cw = size.width / 24f; val ch = size.height / 7f
            for (r in 0 until 7) for (c in 0 until 24) {
                val v = w.at(r, c)
                val color = if (v == 0) empty else primary.copy(alpha = 0.2f + 0.8f * v / max)
                drawRoundRect(color, Offset(c * cw + 1f, r * ch + 1f), Size(cw - 2f, ch - 2f), CornerRadius(2f, 2f))
                if (r * 24 + c == picked) drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(c * cw + 1f, r * ch + 1f), Size(cw - 2f, ch - 2f), CornerRadius(2f, 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(32.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { Text("$it", style = MaterialTheme.typography.labelSmall, color = labelC) }
        }
    }
    // Scale legend: one hue, light to dark.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("1", style = MaterialTheme.typography.labelSmall, color = labelC)
        listOf(0.2f, 0.45f, 0.7f, 1f).forEach { a -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)).background(primary.copy(alpha = a))) }
        Text("$max " + Texts.tr("days", "giorni"), style = MaterialTheme.typography.labelSmall, color = labelC)
    }
    val text = if (picked >= 0) "${days[picked / 24]} ${picked % 24}:00–${picked % 24 + 1}:00 · ${w.days[picked]} " + Texts.tr("of ${w.totalDays} days heard", "dei ${w.totalDays} giorni in cui è stato sentito")
    else Texts.tr("Heard on ${w.totalDays} days. Same weekday and hour every week: a routine (commuter, colleague, neighbour). Tap a cell.", "Sentito in ${w.totalDays} giorni. Stesso giorno e ora ogni settimana: un'abitudine (pendolare, collega, vicino). Tocca una cella.")
    Text(text, style = MaterialTheme.typography.labelSmall, color = labelC)
}

// ---------------------------------------------------------------------------------------------
// 5. Devices × your places

/**
 * Rows: devices heard at several places, plus flagged ones. Columns: your routine places (days
 * heard there) and "elsewhere" (distinct ~200 m spots). A device present in several unrelated
 * columns is worth a look; one present only at home is a neighbour.
 */
@Composable
fun PlacesMatrixSection() {
    val app = RetrovisionApp.instance
    var days by remember { mutableLongStateOf(14L) }
    var m by remember { mutableStateOf<ChartsData.Matrix?>(null) }
    var open by remember { mutableStateOf<ArchiveItem?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(days) {
        m = null
        m = withContext(Dispatchers.IO) {
            val dao = app.db.dao()
            val now = System.currentTimeMillis()
            val from = now - days * 86_400_000L
            val rows = LinkedHashMap<String, ChartsData.LaneSpec>()
            val mask = CatalogFlags.ALERTED or CatalogFlags.SUSPICIOUS or CatalogFlags.GROUPED or CatalogFlags.KNOWS_MY_NETWORK
            for (c in dao.catalogFlagged(from, mask, 12) + dao.catalogMultiPlace(from, 2, 30)) {
                if (rows.size >= 30 || c.entityId in rows) continue
                val ids = (c.members.split('\n').filter { it.isNotBlank() } + c.entityId).distinct()
                rows[c.entityId] = ChartsData.LaneSpec(c.entityId, ids, ArchiveItem.of(c).title, (c.flags and (CatalogFlags.ALERTED or CatalogFlags.SUSPICIOUS)) != 0)
            }
            val mat = ChartsData.matrix(dao, rows.values.toList(), dao.familiarNow().map { it.toModel() }, from, now)
            // Most spread first: other places, then routine places covered.
            ChartsData.Matrix(mat.columns, mat.rows.sortedWith(compareByDescending<ChartsData.MatrixRow> { it.spec.flagged }.thenByDescending { it.otherPlaces + it.routineDays.count { d -> d > 0 } }), mat.fromMs)
        }
    }
    RangeChips(listOf(7L to Texts.tr("7 days", "7 giorni"), 14L to Texts.tr("14 days", "14 giorni"), 30L to Texts.tr("30 days", "30 giorni")), days) { days = it }
    val mat = m
    if (mat == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    if (mat.rows.isEmpty()) { Text(Texts.tr("No device heard at several places in this period.", "Nessun dispositivo sentito in più luoghi in questo periodo."), style = MaterialTheme.typography.bodySmall); return }
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val labelC = ink()
    val maxDays = mat.rows.maxOf { r -> r.routineDays.maxOrNull() ?: 0 }.coerceAtLeast(1)
    val maxOther = mat.rows.maxOf { it.otherPlaces }.coerceAtLeast(1)
    val cell = 34.dp
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Row {
                Box(Modifier.width(LABEL_W))
                mat.columns.forEach { c ->
                    Text(
                        if (c.routine) c.label else Texts.tr("Else­where", "Altrove"),
                        style = MaterialTheme.typography.labelSmall, color = labelC, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(cell + 4.dp).padding(horizontal = 1.dp),
                    )
                }
            }
            mat.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        (if (r.spec.flagged) "⚠ " else "") + r.spec.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(LABEL_W).padding(end = 6.dp).clickable {
                            scope.launch { withContext(Dispatchers.IO) { app.db.dao().catalogRow(r.spec.key) }?.let { open = ArchiveItem.of(it) } }
                        },
                    )
                    r.routineDays.forEach { v -> MatrixCell(v, v.toFloat() / maxDays, primary, empty, cell) }
                    MatrixCell(r.otherPlaces, r.otherPlaces.toFloat() / maxOther, primary, empty, cell)
                }
            }
        }
    }
    Text(
        Texts.tr(
            "Routine places: days heard there (darker = more). Elsewhere: distinct ~200 m spots outside them, over the last $days days. A row lit in several unrelated columns is the one to look at; only at home is a neighbour.",
            "Luoghi abituali: giorni in cui è stato sentito lì (più scuro = di più). Altrove: punti distinti di ~200 m fuori da essi, negli ultimi $days giorni. Una riga accesa in più colonne non legate tra loro è quella da guardare; solo a casa è un vicino.",
        ),
        style = MaterialTheme.typography.labelSmall, color = labelC,
    )
    open?.let { h -> ArchiveDetailDialog(h) { open = null } }
}

@Composable
private fun MatrixCell(v: Int, frac: Float, hue: Color, empty: Color, size: androidx.compose.ui.unit.Dp) {
    val bg = if (v == 0) empty else hue.copy(alpha = 0.2f + 0.8f * frac.coerceIn(0f, 1f))
    Box(
        Modifier.padding(2.dp).size(size).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (v > 0) Text(
            "$v", style = MaterialTheme.typography.labelSmall,
            color = if (frac > 0.55f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
