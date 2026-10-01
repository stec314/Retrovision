package dev.retrovision.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.Visit
import dev.retrovision.core.model.GeoFix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.collectAsState
import dev.retrovision.app.map.OfflineMaps
import dev.retrovision.app.map.TileCache
import dev.retrovision.app.map.TileKey
import dev.retrovision.core.map.MapInfo
import dev.retrovision.core.map.TileSource
import dev.retrovision.core.map.TileType
import dev.retrovision.core.map.WebMercator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** What the user tapped on the map. Only the user's own data: track points, stays, routine places. */
sealed interface MapSel {
    data class Fix(val fix: GeoFix) : MapSel
    data class Stay(val visit: Visit) : MapSel
    data class Place(val place: FamiliarPlace) : MapSel
    data class Point(val lat: Double, val lon: Double) : MapSel
}

/** Hoisted map state, so the timeline below the map can focus a stay. */
@Stable
class MapUiState {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var selection by mutableStateOf<MapSel?>(null)
    /** Scrubber position in [0,1] over the shown window; null = off (whole track bright). */
    var scrub by mutableStateOf<Float?>(null)
    var focus by mutableStateOf<Pair<Double, Double>?>(null)

    fun reset() { zoom = 1f; pan = Offset.Zero }
    fun focusOn(lat: Double, lon: Double) { focus = lat to lon }
}

/** Web Mercator (world units 0..1, y down) fitted to the view, then user zoom/pan. Matches XYZ map tiles exactly. */
private class Proj(
    private val cx: Double, private val cy: Double,
    val base: Double, private val w: Float, private val h: Float,
    zoom: Float, private val pan: Offset, lat0: Double,
) {
    /** Screen pixels per world unit. */
    val s = base * zoom
    /** Ground metres per screen pixel around the view. */
    val mPerPx = WebMercator.metresPerUnit(lat0) / s
    fun screen(lat: Double, lon: Double) =
        Offset(w / 2 + ((WebMercator.x(lon) - cx) * s).toFloat() + pan.x, h / 2 + ((WebMercator.y(lat) - cy) * s).toFloat() + pan.y)
    fun screenX(wx: Double) = w / 2 + ((wx - cx) * s).toFloat() + pan.x
    fun screenY(wy: Double) = h / 2 + ((wy - cy) * s).toFloat() + pan.y
    fun worldX(px: Float) = (px - w / 2 - pan.x) / s + cx
    fun worldY(py: Float) = (py - h / 2 - pan.y) / s + cy
    fun geo(p: Offset): Pair<Double, Double> = WebMercator.lat(worldY(p.y)) to WebMercator.lon(worldX(p.x))
    fun radiusPx(lat: Double, metres: Double) = (metres / WebMercator.metresPerUnit(lat) * s).toFloat()
    /** Pan needed to put (lat, lon) in the centre at the current zoom. */
    fun panToCenter(lat: Double, lon: Double) =
        Offset(-((WebMercator.x(lon) - cx) * s).toFloat(), -((WebMercator.y(lat) - cy) * s).toFloat())
}

private class Fit(val lat0: Double, val lon0: Double, val cx: Double, val cy: Double, val spanW: Double)

private fun fitOf(points: List<Pair<Double, Double>>, fallback: MapInfo?): Fit? {
    if (points.isEmpty()) {
        val i = fallback ?: return null
        // Show roughly the archive's suggested view when there is no track yet.
        val span = 1.0 / (1 shl i.centerZoom.coerceIn(0, 18)) * 3
        return Fit(i.centerLat, i.centerLon, WebMercator.x(i.centerLon), WebMercator.y(i.centerLat), span)
    }
    val lat0 = points.map { it.first }.average()
    val lon0 = points.map { it.second }.average()
    val xs = points.map { WebMercator.x(it.second) }
    val ys = points.map { WebMercator.y(it.first) }
    val minSpan = 300.0 / WebMercator.metresPerUnit(lat0)
    val span = max(max(xs.max() - xs.min(), ys.max() - ys.min()), minSpan) * 1.2
    return Fit(lat0, lon0, (xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, span)
}

/** Dark-mode filter for raster maps: invert, rotate hue 180° (water stays blue), dim. */
private val RASTER_DARK: ColorFilter = run {
    val m = android.graphics.ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val c = -1f; val sn = 0f
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
        0.213f + c * 0.787f - sn * 0.213f, 0.715f - c * 0.715f - sn * 0.715f, 0.072f - c * 0.072f + sn * 0.928f, 0f, 0f,
        0.213f - c * 0.213f + sn * 0.143f, 0.715f + c * 0.285f + sn * 0.140f, 0.072f - c * 0.072f - sn * 0.283f, 0f, 0f,
        0.213f - c * 0.213f - sn * 0.787f, 0.715f - c * 0.715f + sn * 0.715f, 0.072f + c * 0.928f + sn * 0.072f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    m.postConcat(android.graphics.ColorMatrix().apply { setScale(0.75f, 0.8f, 0.9f, 1f) })
    ColorFilter.colorMatrix(ColorMatrix(m.array))
}

private val NICE_M = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1e3, 2e3, 5e3, 1e4, 2e4, 5e4, 1e5, 2e5, 5e5)

private fun niceStep(metresPerPx: Double, targetPx: Double): Double =
    NICE_M.lastOrNull { it / metresPerPx <= targetPx } ?: NICE_M.first()

private fun distLabel(m: Double) = if (m >= 1000) "${(m / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }} km" else "${m.toInt()} m"

private fun interpolate(fixes: List<GeoFix>, t: Long): GeoFix? {
    if (fixes.isEmpty()) return null
    val i = fixes.indexOfFirst { it.timeMs >= t }
    if (i <= 0) return if (i == 0) fixes.first() else fixes.last()
    val a = fixes[i - 1]; val b = fixes[i]
    val f = if (b.timeMs == a.timeMs) 0.0 else (t - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
    return GeoFix(t, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
}

/**
 * Interactive dark map of the user's own movements.
 * Pinch to zoom, drag to pan, double tap to zoom in, tap to inspect, long press to pick a point.
 * Basemap tiles come only from an imported offline file: no network request reveals where you are.
 */
@Composable
fun TrackMap(
    fixes: List<GeoFix>,
    visits: List<Visit>,
    routine: List<FamiliarPlace>,
    here: GeoFix?,
    state: MapUiState,
    onMarkRoutine: (lat: Double, lon: Double) -> Unit,
) {
    val sorted = remember(fixes) { fixes.sortedBy { it.timeMs } }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val basemap by OfflineMaps.active.collectAsState()
    val tileVersion by TileCache.version.collectAsState()
    val fit = remember(sorted, routine, here == null, basemap?.first?.id) {
        fitOf(
            sorted.map { it.lat to it.lon } + routine.map { it.lat to it.lon } + listOfNotNull(here?.let { it.lat to it.lon }),
            basemap?.second?.info,
        )
    }
    fun proj(): Proj? {
        val f = fit ?: return null
        if (viewSize.width == 0) return null
        val base = min(viewSize.width, viewSize.height) / f.spanW
        return Proj(f.cx, f.cy, base, viewSize.width.toFloat(), viewSize.height.toFloat(), state.zoom, state.pan, f.lat0)
    }
    val t0 = sorted.firstOrNull()?.timeMs ?: 0L
    val t1 = sorted.lastOrNull()?.timeMs ?: 0L
    val scrubT = state.scrub?.let { t0 + ((t1 - t0) * it).toLong() }
    val scrubFix = scrubT?.let { interpolate(sorted, it) }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateTimeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(state.focus, fit, viewSize) {
        val (lat, lon) = state.focus ?: return@LaunchedEffect
        if (state.zoom < 3f) state.zoom = 3f
        val p = proj() ?: return@LaunchedEffect
        state.pan = p.panToCenter(lat, lon)
        state.focus = null
    }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MapColors.label.toArgb(); textSize = 30f; typeface = Typeface.MONOSPACE
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MapColors.background),
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(fit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val old = state.zoom
                            val nz = (old * zoomChange).coerceIn(0.25f, 400f)
                            val zc = nz / old
                            val c = Offset(this.size.width / 2f, this.size.height / 2f)
                            val g = centroid - c
                            state.pan = g - (g - state.pan) * zc + panChange
                            state.zoom = nz
                        }
                    }
                    .pointerInput(fit, sorted, visits, routine) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                                val nz = (state.zoom * 2f).coerceAtMost(400f)
                                val zc = nz / state.zoom
                                state.pan = (p - c) - ((p - c) - state.pan) * zc
                                state.zoom = nz
                            },
                            onLongPress = { p ->
                                val pr = proj() ?: return@detectTapGestures
                                val (lat, lon) = pr.geo(p)
                                state.selection = MapSel.Point(lat, lon)
                            },
                            onTap = { p ->
                                val pr = proj() ?: return@detectTapGestures
                                val hitPx = 40f
                                val stay = visits.minByOrNull { (pr.screen(it.lat, it.lon) - p).getDistance() }
                                    ?.takeIf { (pr.screen(it.lat, it.lon) - p).getDistance() < hitPx }
                                val place = routine.firstOrNull {
                                    (pr.screen(it.lat, it.lon) - p).getDistance() < max(hitPx, pr.radiusPx(it.lat, it.radiusM))
                                }
                                val fix = sorted.minByOrNull { (pr.screen(it.lat, it.lon) - p).getDistance() }
                                    ?.takeIf { (pr.screen(it.lat, it.lon) - p).getDistance() < hitPx }
                                state.selection = when {
                                    stay != null -> MapSel.Stay(stay)
                                    place != null -> MapSel.Place(place)
                                    fix != null -> MapSel.Fix(fix)
                                    else -> null
                                }
                            },
                        )
                    },
            ) {
                val pr = proj() ?: return@Canvas
                val mPerPx = pr.mPerPx
                if (tileVersion < 0) return@Canvas // reading it makes new tiles trigger a redraw

                basemap?.let { (m, src) -> drawTiles(pr, m.id, src) }

                // Adaptive metric grid; every 5th line brighter.
                val step = niceStep(mPerPx, 90.0)
                val stepPx = (step / mPerPx).toFloat()
                if (stepPx > 8f && basemap == null) {
                    val origin = pr.screen(fit!!.lat0, fit.lon0)
                    var i = -((origin.x / stepPx).toInt() + 1)
                    while (origin.x + i * stepPx < size.width) {
                        val x = origin.x + i * stepPx
                        drawLine(if (i % 5 == 0) MapColors.gridMajor else MapColors.grid, Offset(x, 0f), Offset(x, size.height), 1f)
                        i++
                    }
                    var j = -((origin.y / stepPx).toInt() + 1)
                    while (origin.y + j * stepPx < size.height) {
                        val y = origin.y + j * stepPx
                        drawLine(if (j % 5 == 0) MapColors.gridMajor else MapColors.grid, Offset(0f, y), Offset(size.width, y), 1f)
                        j++
                    }
                }

                routine.forEach {
                    val c = pr.screen(it.lat, it.lon)
                    val r = max(6f, pr.radiusPx(it.lat, it.radiusM))
                    drawCircle(MapColors.routine.copy(alpha = 0.10f), radius = r, center = c)
                    drawCircle(MapColors.routine, radius = r, center = c, style = Stroke(2f))
                }

                // Track: past (up to the scrubber) bright, the rest dim. Gaps > 10 min break the line.
                val stride = max(1, sorted.size / 2500)
                val bright = Path(); val dim = Path()
                var prev: GeoFix? = null
                sorted.forEachIndexed { idx, f ->
                    if (idx % stride != 0 && idx != sorted.lastIndex) return@forEachIndexed
                    val o = pr.screen(f.lat, f.lon)
                    val target = if (scrubT == null || f.timeMs <= scrubT) bright else dim
                    val p0 = prev
                    if (p0 == null || f.timeMs - p0.timeMs > 10 * 60_000L) {
                        target.moveTo(o.x, o.y)
                    } else {
                        val a = pr.screen(p0.lat, p0.lon)
                        target.moveTo(a.x, a.y); target.lineTo(o.x, o.y)
                    }
                    prev = f
                }
                val round = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(dim, MapColors.track.copy(alpha = 0.25f), style = round)
                drawPath(bright, MapColors.trackGlow, style = Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bright, MapColors.track, style = round)

                // Stays: amber rings sized by duration.
                visits.forEach { v ->
                    val c = pr.screen(v.lat, v.lon)
                    val r = (5f + (v.durationMs / 600_000f)).coerceAtMost(16f)
                    drawCircle(MapColors.stay.copy(alpha = 0.25f), radius = r + 4f, center = c)
                    drawCircle(MapColors.stay, radius = r, center = c, style = Stroke(2.5f))
                }

                here?.let {
                    val c = pr.screen(it.lat, it.lon)
                    drawCircle(MapColors.me.copy(alpha = 0.22f), radius = 20f, center = c)
                    drawCircle(MapColors.me, radius = 8f, center = c)
                }
                scrubFix?.let {
                    val c = pr.screen(it.lat, it.lon)
                    drawCircle(MapColors.track, radius = 11f, center = c, style = Stroke(3f))
                    drawCircle(MapColors.background, radius = 5f, center = c)
                }

                // Selection highlight
                when (val s = state.selection) {
                    is MapSel.Fix -> drawCircle(MapColors.select, 12f, pr.screen(s.fix.lat, s.fix.lon), style = Stroke(3f))
                    is MapSel.Stay -> drawCircle(MapColors.select, 20f, pr.screen(s.visit.lat, s.visit.lon), style = Stroke(3f))
                    is MapSel.Place -> drawCircle(MapColors.select, max(14f, pr.radiusPx(s.place.lat, s.place.radiusM)), pr.screen(s.place.lat, s.place.lon), style = Stroke(3f))
                    is MapSel.Point -> {
                        val c = pr.screen(s.lat, s.lon)
                        drawLine(MapColors.select, c - Offset(14f, 0f), c + Offset(14f, 0f), 3f)
                        drawLine(MapColors.select, c - Offset(0f, 14f), c + Offset(0f, 14f), 3f)
                    }
                    null -> {}
                }

                // Scale bar
                val bar = niceStep(mPerPx, 160.0)
                val barPx = (bar / mPerPx).toFloat()
                val y = size.height - 28f
                drawLine(MapColors.label, Offset(24f, y), Offset(24f + barPx, y), 4f)
                drawLine(MapColors.label, Offset(24f, y - 8f), Offset(24f, y + 8f), 3f)
                drawLine(MapColors.label, Offset(24f + barPx, y - 8f), Offset(24f + barPx, y + 8f), 3f)
                drawIntoCanvas { it.nativeCanvas.drawText(distLabel(bar), 24f, y - 14f, labelPaint) }
            }

            if (fit == null) {
                Text(
                    Texts.tr("No positions yet. Start the collector with location on.", "Ancora nessuna posizione. Avvia la raccolta con la posizione attiva."),
                    color = MapColors.label, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            basemap?.let { (m, _) ->
                Text(
                    "© OpenStreetMap",
                    color = MapColors.label, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
                Text(
                    m.file.nameWithoutExtension,
                    color = MapColors.label, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }

            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapButton("+") { state.zoom = (state.zoom * 1.6f).coerceAtMost(400f); state.pan = state.pan * 1.6f }
                MapButton("−") { state.zoom = (state.zoom / 1.6f).coerceAtLeast(0.25f); state.pan = state.pan / 1.6f }
                MapButton("⤢") { state.reset() }
                if (here != null) MapButton("◎") { state.focusOn(here.lat, here.lon) }
            }
        }

        if (sorted.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    scrubT?.let { timeFmt.format(Date(it)) } ?: Texts.tr("Replay", "Ripercorri"),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp),
                )
                Slider(
                    value = state.scrub ?: 1f,
                    onValueChange = { state.scrub = it },
                    modifier = Modifier.weight(1f),
                )
                if (state.scrub != null) TextButton(onClick = { state.scrub = null }) { Text("✕") }
            }
        }

        state.selection?.let { sel ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (sel) {
                        is MapSel.Fix -> {
                            Text(Texts.tr("You were here", "Eri qui"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                dateTimeFmt.format(Date(sel.fix.timeMs)) +
                                    (sel.fix.speedMps?.let { " · %.0f km/h".format(it * 3.6) } ?: "") +
                                    (if (sel.fix.accuracyM > 0) " · ±%.0f m".format(sel.fix.accuracyM) else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is MapSel.Stay -> {
                            Text(Texts.tr("Stay", "Sosta"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${timeFmt.format(Date(sel.visit.startMs))}–${timeFmt.format(Date(sel.visit.endMs))} · ${sel.visit.durationMs / 60_000} min",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (routine.none { it.contains(sel.visit.lat, sel.visit.lon) }) {
                                OutlinedButton(onClick = { onMarkRoutine(sel.visit.lat, sel.visit.lon); state.selection = null }) {
                                    Text(Texts.tr("Mark as routine place", "Segna come luogo di routine"))
                                }
                            }
                        }
                        is MapSel.Place -> {
                            Text(
                                if (sel.place.kind == FamiliarPlace.Kind.HOME_LIKE) Texts.tr("Routine place · home-like", "Luogo di routine · tipo casa")
                                else Texts.tr("Routine place · frequent", "Luogo di routine · frequente"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                Texts.tr("Radius", "Raggio") + " ${sel.place.radiusM.toInt()} m · %.5f, %.5f".format(sel.place.lat, sel.place.lon),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is MapSel.Point -> {
                            Text(Texts.tr("Selected point", "Punto selezionato"), style = MaterialTheme.typography.titleSmall)
                            Text("%.5f, %.5f".format(sel.lat, sel.lon), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { onMarkRoutine(sel.lat, sel.lon); state.selection = null }) {
                                Text(Texts.tr("Mark as routine place", "Segna come luogo di routine"))
                            }
                        }
                    }
                    TextButton(onClick = { state.selection = null }) { Text(Texts.tr("Close", "Chiudi")) }
                }
            }
        }
        Text(
            Texts.tr(
                "Pinch to zoom · drag · double tap · tap a point or stay · long press to pick a spot",
                "Pizzica per lo zoom · trascina · doppio tocco · tocca un punto o una sosta · tieni premuto per scegliere un punto",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Text(label) }
}

/** Draws the offline basemap tiles under the track. Missing tiles fall back to a scaled-up ancestor while they render. */
private fun DrawScope.drawTiles(pr: Proj, mapId: String, src: TileSource) {
    val info = src.info
    val z = floor(log2(pr.s / 384.0)).toInt().coerceIn(max(0, info.minZoom), min(22, info.maxZoom + 5))
    val n = 1 shl z
    val tilePx = pr.s / n
    val x0 = floor(pr.worldX(0f) * n).toInt().coerceIn(0, n - 1)
    val x1 = floor(pr.worldX(size.width) * n).toInt().coerceIn(0, n - 1)
    val y0 = floor(pr.worldY(0f) * n).toInt().coerceIn(0, n - 1)
    val y1 = floor(pr.worldY(size.height) * n).toInt().coerceIn(0, n - 1)
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 96) return
    val filter = if (info.type == TileType.MVT) null else RASTER_DARK
    for (ty in y0..y1) for (tx in x0..x1) {
        val left = pr.screenX(tx.toDouble() / n)
        val top = pr.screenY(ty.toDouble() / n)
        val dst = IntSize(ceil(tilePx).toInt() + 1, ceil(tilePx).toInt() + 1)
        val dstOff = IntOffset(left.roundToInt(), top.roundToInt())
        val img = TileCache.get(TileKey(mapId, z, tx, ty), src)
        if (img != null) {
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = dstOff, dstSize = dst, colorFilter = filter)
            continue
        }
        for (d in 1..4) {
            if (z - d < 0) break
            val parent = TileCache.peek(TileKey(mapId, z - d, tx shr d, ty shr d)) ?: continue
            val sub = parent.width shr d
            if (sub < 1) break
            val so = IntOffset((tx - ((tx shr d) shl d)) * sub, (ty - ((ty shr d) shl d)) * sub)
            drawImage(parent, srcOffset = so, srcSize = IntSize(sub, sub), dstOffset = dstOff, dstSize = dst, colorFilter = filter)
            break
        }
    }
}
