// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.retrovision.app.map.MapDownload
import dev.retrovision.app.map.OfflineMaps
import dev.retrovision.app.map.TileCache
import dev.retrovision.app.map.TileKey
import dev.retrovision.core.analysis.DeviceVisit
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.Level
import dev.retrovision.core.analysis.Visit
import dev.retrovision.core.map.MapInfo
import dev.retrovision.core.map.TileSource
import dev.retrovision.core.map.TileType
import dev.retrovision.core.map.WebMercator
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the user tapped on the map. Only the user's own data: track points, stays, routine places. */
sealed interface MapSel {
    data class Fix(val fix: GeoFix) : MapSel
    data class Stay(val visit: Visit) : MapSel
    data class Place(val place: FamiliarPlace) : MapSel
    data class Point(val lat: Double, val lon: Double) : MapSel
    /** Where a flagged device was heard: always a point of your own track. */
    data class DevicePlace(val device: DeviceMarks, val visit: DeviceVisit) : MapSel
}

/**
 * A device the analysis flagged (worth a look or strong), with the places where your receivers heard
 * it. The positions are yours at that moment, not the device's: nothing here locates anyone else.
 */
class DeviceMarks(val entityId: String, val label: String, val level: Level, val color: androidx.compose.ui.graphics.Color, val visits: List<DeviceVisit>)

/** Distinct marker colours for flagged devices (none reused for track, stays or routine). */
val DEVICE_COLORS = listOf(
    androidx.compose.ui.graphics.Color(0xFFFF7A59), androidx.compose.ui.graphics.Color(0xFFC792EA),
    androidx.compose.ui.graphics.Color(0xFFF78FB3), androidx.compose.ui.graphics.Color(0xFFFFD166),
    androidx.compose.ui.graphics.Color(0xFF9AD0FF), androidx.compose.ui.graphics.Color(0xFFB8F28B),
    androidx.compose.ui.graphics.Color(0xFFFF9F1C), androidx.compose.ui.graphics.Color(0xFF80FFDB),
)

/** A routine place being created or edited: centre, radius and name chosen by the user. */
data class RoutineEdit(val id: Long?, val lat: Double, val lon: Double, val radiusM: Double, val label: String)

/** Hoisted map state, shared by the inline map and the full-screen map. */
@Stable
class MapUiState {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var selection by mutableStateOf<MapSel?>(null)
    /** Scrubber position in [0,1] over the shown window; null = off (whole track bright). */
    var scrub by mutableStateOf<Float?>(null)
    var focus by mutableStateOf<Pair<Double, Double>?>(null)
    var windowH by mutableIntStateOf(24)
    var showTrack by mutableStateOf(true)
    var showStays by mutableStateOf(true)
    var showRoutine by mutableStateOf(true)
    var showGrid by mutableStateOf(true)
    var showDevices by mutableStateOf(true)
    var fullscreen by mutableStateOf(false)
    var edit by mutableStateOf<RoutineEdit?>(null)
    /** Keep the view centred on your position (like a navigation app); any drag turns it off. */
    var follow by mutableStateOf(false)
    /** Ground width (metres) to show when [focus] is applied; null = keep the zoom. */
    var focusSpanM by mutableStateOf<Double?>(null)
    /** Only this flagged device's places are drawn, and listed under the map. */
    var deviceFocus by mutableStateOf<String?>(null)
    var showInfo by mutableStateOf(false)
    /** Flagged devices switched off by the user (chips over the map, checkboxes under it). */
    var hiddenDevices by mutableStateOf<Set<String>>(emptySet())
    /** The replay scrubber takes room: shown only on request. */
    var showReplay by mutableStateOf(false)
    /** Bumped to recompute the fitted view (period change, "fit all"). Otherwise it stays put while data refreshes. */
    var fitEpoch by mutableIntStateOf(0)

    /** Set by [reset]: the next change of the fitted frame shows the whole frame instead of keeping the view. */
    var resetPending = false
    fun reset() { zoom = 1f; pan = Offset.Zero; follow = false; resetPending = true; fitEpoch++ }
    fun focusOn(lat: Double, lon: Double, spanM: Double? = null) { focus = lat to lon; focusSpanM = spanM }
    fun startEdit(e: RoutineEdit) { selection = null; edit = e; focusOn(e.lat, e.lon) }
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

/** Radius slider is logarithmic: fine control for small areas, still reaches 2 km. */
private const val R_MIN = 30.0
private const val R_MAX = 2000.0
private fun radiusOf(t: Float) = R_MIN * (R_MAX / R_MIN).pow(t.toDouble())
private fun sliderOf(r: Double) = (ln(r.coerceIn(R_MIN, R_MAX) / R_MIN) / ln(R_MAX / R_MIN)).toFloat()

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
    /** From precomputed world coordinates: no logarithms per point per frame. */
    fun sx(wx: Double) = (w / 2 + (wx - cx) * s + pan.x).toFloat()
    fun sy(wy: Double) = (h / 2 + (wy - cy) * s + pan.y).toFloat()
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

/**
 * Interactive dark map of the user's own movements, used inline and full screen.
 * Pinch to zoom, drag to pan, double tap to zoom in, tap to inspect, long press to pick a point.
 * Options (period, layers, base map, download) live in the ☰ menu.
 * Basemap tiles come only from a local file: no network request reveals where you are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMap(
    fixes: List<GeoFix>,
    visits: List<Visit>,
    routine: List<FamiliarPlace>,
    here: GeoFix?,
    state: MapUiState,
    onSaveRoutine: (RoutineEdit) -> Unit,
    onDeleteRoutine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    devices: List<DeviceMarks> = emptyList(),
) {
    val sorted = remember(fixes) { fixes.sortedBy { it.timeMs } }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val basemap by OfflineMaps.active.collectAsState()
    val maps by OfflineMaps.maps.collectAsState()
    val tileVersion by TileCache.version.collectAsState()
    val download by MapDownload.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var downloadBox by remember { mutableStateOf<DoubleArray?>(null) }
    // Recomputed only when data first appears or the user asks (period, fit all): refreshing the track
    // every 30 s must not move the view under the user's finger.
    val fit = remember(sorted.isEmpty(), here == null, basemap?.first?.id, state.fitEpoch) {
        fitOf(
            sorted.map { it.lat to it.lon } + routine.map { it.lat to it.lon } + listOfNotNull(here?.let { it.lat to it.lon }),
            basemap?.second?.info,
        )
    }
    fun projOf(f: Fit, zoom: Float = state.zoom, pan: Offset = state.pan): Proj? {
        if (viewSize.width == 0) return null
        val base = min(viewSize.width, viewSize.height) / f.spanW
        return Proj(f.cx, f.cy, base, viewSize.width.toFloat(), viewSize.height.toFloat(), zoom, pan, f.lat0)
    }
    fun proj(): Proj? = fit?.let { projOf(it) }

    // The view is stored relative to the fitted frame. When the frame changes (track loaded, period
    // changed), keep showing the same place at the same scale instead of jumping somewhere empty.
    var lastFit by remember { mutableStateOf<Fit?>(null) }
    LaunchedEffect(fit, viewSize) {
        val old = lastFit
        val f = fit
        if (viewSize.width == 0 || f == null) return@LaunchedEffect
        lastFit = f
        if (state.resetPending) { state.resetPending = false; return@LaunchedEffect }
        if (old == null || old === f || state.focus != null) return@LaunchedEffect
        val po = projOf(old) ?: return@LaunchedEffect
        val (lat, lon) = po.geo(Offset(viewSize.width / 2f, viewSize.height / 2f))
        val z = (state.zoom * f.spanW / old.spanW).toFloat().coerceIn(0.25f, 2000f)
        val pn = projOf(f, z, Offset.Zero) ?: return@LaunchedEffect
        state.zoom = z
        state.pan = pn.panToCenter(lat, lon)
    }
    val t0 = sorted.firstOrNull()?.timeMs ?: 0L
    val t1 = sorted.lastOrNull()?.timeMs ?: 0L
    val scrubT = state.scrub?.let { t0 + ((t1 - t0) * it).toLong() }
    val scrubFix = scrubT?.let { interpolate(sorted, it) }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateTimeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(state.focus, fit, viewSize) {
        val (lat, lon) = state.focus ?: return@LaunchedEffect
        val f = fit ?: return@LaunchedEffect
        if (viewSize.width == 0) return@LaunchedEffect
        val span = state.focusSpanM
        if (span != null) {
            // Zoom so that about [span] metres fit across the shorter side.
            state.zoom = (f.spanW * WebMercator.metresPerUnit(lat) / span).toFloat().coerceIn(0.25f, 2000f)
        } else if (state.zoom < 3f) state.zoom = 3f
        val p = proj() ?: return@LaunchedEffect
        state.pan = p.panToCenter(lat, lon)
        state.focus = null
        state.focusSpanM = null
    }
    // Follow mode: re-centre on each new position, without changing the zoom.
    LaunchedEffect(here, state.follow, fit, viewSize) {
        val h = here ?: return@LaunchedEffect
        if (!state.follow || state.focus != null) return@LaunchedEffect
        val p = proj() ?: return@LaunchedEffect
        state.pan = p.panToCenter(h.lat, h.lon)
    }
    // World (Mercator) coordinates computed once per data change, not on every frame of a pinch.
    val trackW = remember(sorted) {
        val stride = max(1, sorted.size / 4000)
        val idx = sorted.indices.filter { it % stride == 0 || it == sorted.lastIndex }
        Triple(DoubleArray(idx.size) { WebMercator.x(sorted[idx[it]].lon) }, DoubleArray(idx.size) { WebMercator.y(sorted[idx[it]].lat) }, LongArray(idx.size) { sorted[idx[it]].timeMs })
    }
    val devW = remember(devices) {
        devices.associate { d ->
            val pts = d.visits.filter { it.lat != null && it.lon != null }.sortedBy { it.startMs }
            d.entityId to (DoubleArray(pts.size) { WebMercator.x(pts[it].lon!!) } to DoubleArray(pts.size) { WebMercator.y(pts[it].lat!!) })
        }
    }
    val shownDevices = remember(devices, state.deviceFocus, state.showDevices, state.hiddenDevices) {
        if (!state.showDevices) emptyList()
        else devices.filter { if (state.deviceFocus != null) it.entityId == state.deviceFocus else it.entityId !in state.hiddenDevices }
    }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MapColors.label.toArgb(); textSize = 30f; typeface = Typeface.MONOSPACE
        }
    }
    val dashed = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }

    Box(modifier.clip(RoundedCornerShape(if (state.fullscreen) 0.dp else 16.dp)).background(MapColors.background)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(fit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val old = state.zoom
                        val nz = (old * zoomChange).coerceIn(0.25f, 2000f)
                        val zc = nz / old
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        val g = centroid - c
                        state.pan = g - (g - state.pan) * zc + panChange
                        state.zoom = nz
                        if (panChange.getDistance() > 2f) state.follow = false
                    }
                }
                .pointerInput(fit, sorted, visits, routine, shownDevices) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            val c = Offset(this.size.width / 2f, this.size.height / 2f)
                            val nz = (state.zoom * 2f).coerceAtMost(2000f)
                            val zc = nz / state.zoom
                            state.pan = (p - c) - ((p - c) - state.pan) * zc
                            state.zoom = nz
                        },
                        onLongPress = { p ->
                            val pr = proj() ?: return@detectTapGestures
                            val (lat, lon) = pr.geo(p)
                            val e = state.edit
                            if (e != null) state.edit = e.copy(lat = lat, lon = lon)
                            else state.selection = MapSel.Point(lat, lon)
                        },
                        onTap = { p ->
                            val pr = proj() ?: return@detectTapGestures
                            // While editing an area, a tap moves its centre.
                            val e = state.edit
                            if (e != null) {
                                val (lat, lon) = pr.geo(p)
                                state.edit = e.copy(lat = lat, lon = lon)
                                return@detectTapGestures
                            }
                            val hitPx = 22.dp.toPx()
                            val stay = if (!state.showStays) null else visits.minByOrNull { (pr.screen(it.lat, it.lon) - p).getDistance() }
                                ?.takeIf { (pr.screen(it.lat, it.lon) - p).getDistance() < hitPx }
                            val place = if (!state.showRoutine) null else routine.firstOrNull {
                                (pr.screen(it.lat, it.lon) - p).getDistance() < max(hitPx, pr.radiusPx(it.lat, it.radiusM))
                            }
                            val fix = if (!state.showTrack) null else sorted.minByOrNull { (pr.screen(it.lat, it.lon) - p).getDistance() }
                                ?.takeIf { (pr.screen(it.lat, it.lon) - p).getDistance() < hitPx }
                            var dev: MapSel.DevicePlace? = null
                            var best = hitPx
                            shownDevices.forEach { d ->
                                d.visits.forEach { v ->
                                    val dd = (pr.screen(v.lat ?: return@forEach, v.lon ?: return@forEach) - p).getDistance()
                                    if (dd < best) { best = dd; dev = MapSel.DevicePlace(d, v) }
                                }
                            }
                            state.selection = when {
                                dev != null -> dev
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

            // Adaptive metric grid; every 5th line brighter. Off by default over a real map.
            val step = niceStep(mPerPx, 90.0)
            val stepPx = (step / mPerPx).toFloat()
            if (stepPx > 8f && state.showGrid && basemap == null) {
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

            if (state.showRoutine) {
                routine.forEach {
                    if (it.id == state.edit?.id) return@forEach
                    val c = pr.screen(it.lat, it.lon)
                    val r = max(6f, pr.radiusPx(it.lat, it.radiusM))
                    drawCircle(MapColors.routine.copy(alpha = 0.10f), radius = r, center = c)
                    drawCircle(MapColors.routine, radius = r, center = c, style = Stroke(2f))
                }
            }

            if (state.showTrack) {
                // Past (up to the scrubber) bright, the rest dim. Gaps > 10 min break the line.
                // Points closer than 2 px to the previous one are skipped: invisible, and costly.
                val (xs, ys, ts) = trackW
                val bright = Path(); val dim = Path()
                var px = 0f; var py = 0f; var pt = Long.MIN_VALUE
                for (i in xs.indices) {
                    val x = pr.sx(xs[i]); val y = pr.sy(ys[i])
                    val t = ts[i]
                    val target = if (scrubT == null || t <= scrubT) bright else dim
                    if (pt == Long.MIN_VALUE || t - pt > 10 * 60_000L) {
                        target.moveTo(x, y); px = x; py = y; pt = t
                    } else if (abs(x - px) + abs(y - py) >= 2f || i == xs.lastIndex) {
                        target.moveTo(px, py); target.lineTo(x, y); px = x; py = y; pt = t
                    } else pt = t
                }
                val round = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(dim, MapColors.track.copy(alpha = 0.25f), style = round)
                drawPath(bright, MapColors.trackGlow, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bright, MapColors.track, style = round)
            }

            if (state.showStays) {
                visits.forEach { v ->
                    val c = pr.screen(v.lat, v.lon)
                    val r = (4f + (v.durationMs / 900_000f)).coerceAtMost(10f).dp.toPx()
                    drawCircle(MapColors.stay.copy(alpha = 0.25f), radius = r + 3.dp.toPx(), center = c)
                    drawCircle(MapColors.stay, radius = r, center = c, style = Stroke(2.dp.toPx()))
                }
            }

            // Devices that may follow you: a diamond wherever each was heard (your position then).
            // Sizes in dp so they stay readable on dense screens. The time-ordered line is drawn only
            // for the device in focus, to keep the map legible.
            val dr = 6.dp.toPx()
            val edge = 1.5.dp.toPx()
            val cell = dr * 1.2f
            shownDevices.forEach { d ->
                val (xs, ys) = devW[d.entityId] ?: return@forEach
                if (state.deviceFocus == d.entityId && xs.size >= 2) {
                    val path = Path()
                    for (i in xs.indices) { val x = pr.sx(xs[i]); val y = pr.sy(ys[i]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    drawPath(path, d.color.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), pathEffect = dashed))
                }
                // One diamond per screen cell: thousands of overlapping markers look the same and cost a lot.
                val used = HashSet<Long>()
                val dia = Path()
                for (i in xs.indices) {
                    val x = pr.sx(xs[i]); val y = pr.sy(ys[i])
                    if (x < -dr || y < -dr || x > size.width + dr || y > size.height + dr) continue
                    val key = ((x / cell).toLong() shl 32) or ((y / cell).toLong() and 0xffffffffL)
                    if (!used.add(key)) continue
                    dia.moveTo(x, y - dr); dia.lineTo(x + dr, y); dia.lineTo(x, y + dr); dia.lineTo(x - dr, y); dia.close()
                }
                drawPath(dia, MapColors.background, style = Stroke(edge * 2))
                drawPath(dia, d.color)
            }

            here?.let {
                val c = pr.screen(it.lat, it.lon)
                if (it.accuracyM > 0) {
                    val acc = max(20f, pr.radiusPx(it.lat, it.accuracyM.toDouble()))
                    drawCircle(MapColors.me.copy(alpha = 0.08f), radius = acc, center = c)
                }
                drawCircle(MapColors.me.copy(alpha = 0.22f), radius = 14.dp.toPx(), center = c)
                drawCircle(MapColors.background, radius = 7.dp.toPx(), center = c)
                drawCircle(MapColors.me, radius = 5.5.dp.toPx(), center = c)
            }
            scrubFix?.let {
                val c = pr.screen(it.lat, it.lon)
                drawCircle(MapColors.track, radius = 11f, center = c, style = Stroke(3f))
                drawCircle(MapColors.background, radius = 5f, center = c)
            }

            // Area being edited: dashed outline and centre mark.
            state.edit?.let { e ->
                val c = pr.screen(e.lat, e.lon)
                val r = max(6f, pr.radiusPx(e.lat, e.radiusM))
                drawCircle(MapColors.routine.copy(alpha = 0.18f), radius = r, center = c)
                drawCircle(MapColors.routine, radius = r, center = c, style = Stroke(3f, pathEffect = dashed))
                drawLine(MapColors.routine, c - Offset(12f, 0f), c + Offset(12f, 0f), 3f)
                drawLine(MapColors.routine, c - Offset(0f, 12f), c + Offset(0f, 12f), 3f)
            }

            when (val s = state.selection) {
                is MapSel.Fix -> drawCircle(MapColors.select, 12f, pr.screen(s.fix.lat, s.fix.lon), style = Stroke(3f))
                is MapSel.Stay -> drawCircle(MapColors.select, 20f, pr.screen(s.visit.lat, s.visit.lon), style = Stroke(3f))
                is MapSel.Place -> drawCircle(MapColors.select, max(14f, pr.radiusPx(s.place.lat, s.place.radiusM)), pr.screen(s.place.lat, s.place.lon), style = Stroke(3f))
                is MapSel.Point -> {
                    val c = pr.screen(s.lat, s.lon)
                    drawLine(MapColors.select, c - Offset(14f, 0f), c + Offset(14f, 0f), 3f)
                    drawLine(MapColors.select, c - Offset(0f, 14f), c + Offset(0f, 14f), 3f)
                }
                is MapSel.DevicePlace -> s.visit.lat?.let { la -> drawCircle(MapColors.select, 12.dp.toPx(), pr.screen(la, s.visit.lon ?: return@let), style = Stroke(2.5.dp.toPx())) }
                null -> {}
            }

            val bar = niceStep(mPerPx, 160.0)
            val barPx = (bar / mPerPx).toFloat()
            val y = size.height - 28f
            val x0 = size.width - 24f - barPx
            drawLine(MapColors.label, Offset(x0, y), Offset(x0 + barPx, y), 4f)
            drawLine(MapColors.label, Offset(x0, y - 8f), Offset(x0, y + 8f), 3f)
            drawLine(MapColors.label, Offset(x0 + barPx, y - 8f), Offset(x0 + barPx, y + 8f), 3f)
            drawIntoCanvas { it.nativeCanvas.drawText(distLabel(bar), x0, y - 14f, labelPaint) }
        }

        if (fit == null) {
            Text(
                Texts.tr(
                    "No positions yet. Start collecting with location on, or download a map from the ☰ menu.",
                    "Ancora nessuna posizione. Avvia la raccolta con la posizione attiva, o scarica una mappa dal menu ☰.",
                ),
                color = MapColors.label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        if (basemap != null) {
            Text(
                "© OpenStreetMap",
                color = MapColors.label, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }

        // ── Top bar: menu (left), zoom and full screen (right) ──
        val topPad: Modifier = Modifier // insets are applied by FullScreenDialog
        Box(Modifier.align(Alignment.TopStart).then(topPad).padding(8.dp).padding(end = 52.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapButton(MapGlyph.MENU, Texts.tr("Map options", "Opzioni mappa")) { menu = true }
                    MapButton(MapGlyph.INFO, Texts.tr("Map info", "Info mappa"), active = state.showInfo) { state.showInfo = !state.showInfo }
                    Text(
                        windowLabel(state.windowH) + (basemap?.first?.file?.nameWithoutExtension?.let { " · $it" } ?: ""),
                        color = MapColors.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    )
                }
                // Quick filters, like the chips over a maps app.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MapChip(Texts.tr("Track", "Percorso"), state.showTrack) { state.showTrack = it }
                    MapChip(Texts.tr("Stays", "Soste"), state.showStays) { state.showStays = it }
                    MapChip(Texts.tr("Routine", "Routine"), state.showRoutine) { state.showRoutine = it }
                    if (devices.isNotEmpty()) {
                        MapChip(Texts.tr("Devices", "Dispositivi") + " ${devices.size}", state.showDevices) { state.showDevices = it }
                    }
                    // One chip per device: tap to show or hide it.
                    if (state.showDevices) devices.forEach { d ->
                        val on = if (state.deviceFocus != null) state.deviceFocus == d.entityId else d.entityId !in state.hiddenDevices
                        MapChip("◆ " + d.label.removePrefix("“").take(14), on, dot = d.color) {
                            if (state.deviceFocus != null) state.deviceFocus = null
                            state.hiddenDevices = if (on) state.hiddenDevices + d.entityId else state.hiddenDevices - d.entityId
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuHeader(Texts.tr("Period", "Periodo"))
                listOf(6, 24, 72, 168).forEach { h ->
                    DropdownMenuItem(
                        text = { Text((if (state.windowH == h) "● " else "○ ") + windowLabel(h)) },
                        onClick = { state.windowH = h; state.reset(); state.scrub = null; state.selection = null; menu = false },
                    )
                }
                HorizontalDivider()
                MenuHeader(Texts.tr("Layers", "Livelli"))
                MenuCheck(Texts.tr("My track", "Il mio percorso"), state.showTrack) { state.showTrack = it }
                MenuCheck(Texts.tr("Stays", "Soste"), state.showStays) { state.showStays = it }
                MenuCheck(Texts.tr("Routine places", "Luoghi di routine"), state.showRoutine) { state.showRoutine = it }
                MenuCheck(Texts.tr("Devices that may follow you", "Dispositivi che potrebbero seguirti"), state.showDevices) { state.showDevices = it }
                MenuCheck(Texts.tr("Metric grid (no base map)", "Griglia metrica (senza mappa)"), state.showGrid) { state.showGrid = it }
                HorizontalDivider()
                MenuHeader(Texts.tr("Base map", "Mappa di base"))
                DropdownMenuItem(
                    text = { Text((if (basemap == null) "● " else "○ ") + Texts.tr("Grid only", "Solo griglia")) },
                    onClick = { scope.launch { withContext(Dispatchers.IO) { OfflineMaps.activate(null) } }; menu = false },
                )
                maps.forEach { m ->
                    DropdownMenuItem(
                        text = { Text((if (basemap?.first?.id == m.id) "● " else "○ ") + m.file.nameWithoutExtension + " (${m.sizeMb} MB)") },
                        onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { OfflineMaps.activate(m) } } }; menu = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(Texts.tr("Download the map of this area…", "Scarica la mappa di quest'area…")) },
                    enabled = !download.running,
                    onClick = {
                        menu = false
                        proj()?.let { p ->
                            val (n, w) = p.geo(Offset(0f, 0f))
                            val (s, e) = p.geo(Offset(viewSize.width.toFloat(), viewSize.height.toFloat()))
                            downloadBox = doubleArrayOf(w, s, e, n)
                        }
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (state.fullscreen) Texts.tr("Exit full screen", "Esci da schermo intero") else Texts.tr("Full screen", "Schermo intero")) },
                    onClick = { state.fullscreen = !state.fullscreen; menu = false },
                )
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).then(topPad).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapButton(
                if (state.fullscreen) MapGlyph.CLOSE else MapGlyph.FULLSCREEN,
                if (state.fullscreen) Texts.tr("Exit full screen", "Esci da schermo intero") else Texts.tr("Full screen", "Schermo intero"),
            ) { state.fullscreen = !state.fullscreen }
            MapButton(MapGlyph.PLUS, Texts.tr("Zoom in", "Avvicina")) { state.zoom = (state.zoom * 1.6f).coerceAtMost(2000f); state.pan = state.pan * 1.6f }
            MapButton(MapGlyph.MINUS, Texts.tr("Zoom out", "Allontana")) { state.zoom = (state.zoom / 1.6f).coerceAtLeast(0.25f); state.pan = state.pan / 1.6f }
            MapButton(MapGlyph.FIT, Texts.tr("Show everything", "Mostra tutto")) { state.reset() }
            MapButton(if (state.showReplay) MapGlyph.STOP else MapGlyph.PLAY, Texts.tr("Replay", "Ripercorri"), active = state.showReplay) {
                state.showReplay = !state.showReplay; if (!state.showReplay) state.scrub = null
            }
            if (here != null) {
                MapButton(MapGlyph.LOCATE, Texts.tr("Follow me", "Seguimi"), active = state.follow) {
                    state.follow = true
                    state.focusOn(here.lat, here.lon, spanM = 600.0)
                }
            }
        }

        // ── Bottom: download progress, replay scrubber, selection or area editor ──
        val bottomPad: Modifier = Modifier
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(bottomPad).padding(8.dp)
                .heightIn(max = if (state.fullscreen) 420.dp else 230.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (download.running) {
                OverlayCard {
                    Text(
                        Texts.tr("Downloading map", "Scarico la mappa") + " · " +
                            if (download.stage == "tiles" && download.total > 0) "${download.done / 1_048_576}/${download.total / 1_048_576} MB"
                            else Texts.tr("reading the index…", "leggo l'indice…"),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (download.total > 0) LinearProgressIndicator(progress = { download.done.toFloat() / download.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { MapDownload.cancel() }) { Text(Texts.tr("Cancel", "Annulla")) }
                }
            } else if (download.error.isNotEmpty()) {
                OverlayCard { Text(download.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (state.showInfo && state.edit == null) {
                MapInfoCard(sorted, visits, routine, devices, here, state)
            }

            if (sorted.size >= 2 && state.showTrack && state.showReplay && state.edit == null) {
                OverlayCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            scrubT?.let { timeFmt.format(Date(it)) } ?: Texts.tr("Replay", "Ripercorri"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(value = state.scrub ?: 1f, onValueChange = { state.scrub = it }, modifier = Modifier.weight(1f))
                        if (state.scrub != null) TextButton(onClick = { state.scrub = null }) { Text("✕") }
                    }
                }
            }

            val edit = state.edit
            if (edit != null) {
                RoutineEditor(
                    edit,
                    onChange = { state.edit = it },
                    onSave = { onSaveRoutine(it); state.edit = null },
                    onDelete = edit.id?.let { id -> { onDeleteRoutine(id); state.edit = null } },
                    onCancel = { state.edit = null },
                )
            } else {
                state.selection?.let { sel ->
                    OverlayCard {
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
                                    OutlinedButton(onClick = { state.startEdit(RoutineEdit(null, sel.visit.lat, sel.visit.lon, 150.0, "")) }) {
                                        Text(Texts.tr("Mark as routine place…", "Segna come luogo di routine…"))
                                    }
                                }
                            }
                            is MapSel.Place -> {
                                Text(
                                    (sel.place.label.ifEmpty { null }?.let { "$it · " } ?: "") +
                                        if (sel.place.kind == FamiliarPlace.Kind.HOME_LIKE) Texts.tr("routine place · home-like", "luogo di routine · tipo casa")
                                        else Texts.tr("routine place · frequent", "luogo di routine · frequente"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(Texts.tr("Radius", "Raggio") + " ${sel.place.radiusM.toInt()} m", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    state.startEdit(RoutineEdit(sel.place.id, sel.place.lat, sel.place.lon, sel.place.radiusM, sel.place.label))
                                }) { Text(Texts.tr("Change area…", "Modifica area…")) }
                            }
                            is MapSel.DevicePlace -> {
                                Text("◆ " + sel.device.label + " · " + Texts.level(sel.device.level), style = MaterialTheme.typography.titleSmall, color = sel.device.color)
                                Text(
                                    Texts.tr("Heard here ", "Sentito qui ") + dateTimeFmt.format(Date(sel.visit.startMs)) + "–" + timeFmt.format(Date(sel.visit.endMs)) +
                                        " · ${sel.visit.sightings} " + Texts.tr("sightings", "rilevazioni") + " · max ${sel.visit.maxRssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (state.deviceFocus != sel.device.entityId) {
                                    OutlinedButton(onClick = { state.deviceFocus = sel.device.entityId; fitDevice(state, sel.device) }) {
                                        Text(Texts.tr("All places of this device", "Tutti i luoghi di questo dispositivo") + " (${sel.device.visits.count { it.lat != null }})")
                                    }
                                }
                            }
                            is MapSel.Point -> {
                                Text(Texts.tr("Selected point", "Punto selezionato"), style = MaterialTheme.typography.titleSmall)
                                Text("%.5f, %.5f".format(sel.lat, sel.lon), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { state.startEdit(RoutineEdit(null, sel.lat, sel.lon, 150.0, "")) }) {
                                    Text(Texts.tr("Mark as routine place…", "Segna come luogo di routine…"))
                                }
                            }
                        }
                        TextButton(onClick = { state.selection = null }) { Text(Texts.tr("Close", "Chiudi")) }
                    }
                }
            }
        }
    }

    downloadBox?.let { box ->
        DownloadDialog(box, onDismiss = { downloadBox = null }) { name, z ->
            downloadBox = null
            scope.launch { MapDownload.download(name, box, z) }
        }
    }
}

/** Centres the view on all the places of one flagged device, with some margin. */
fun fitDevice(state: MapUiState, d: DeviceMarks) {
    val pts = d.visits.filter { it.lat != null && it.lon != null }
    if (pts.isEmpty()) return
    val lat = pts.map { it.lat!! }.average()
    val lon = pts.map { it.lon!! }.average()
    val hM = (pts.maxOf { it.lat!! } - pts.minOf { it.lat!! }) * 110_570.0
    val wM = (pts.maxOf { it.lon!! } - pts.minOf { it.lon!! }) * 111_320.0 * cos(Math.toRadians(lat))
    state.follow = false
    state.focusOn(lat, lon, spanM = max(400.0, max(hM, wM) * 1.4))
}

/** Numbers about what the map shows: distance, time moving, stays, GPS quality. */
@Composable
private fun MapInfoCard(sorted: List<GeoFix>, visits: List<Visit>, routine: List<FamiliarPlace>, devices: List<DeviceMarks>, here: GeoFix?, state: MapUiState) {
    val stats = remember(sorted) {
        var dist = 0.0; var movingMs = 0L
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]; val b = sorted[i]
            val dt = b.timeMs - a.timeMs
            if (dt > 10 * 60_000L) continue
            val d = dev.retrovision.core.analysis.Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            dist += d
            if (dt > 0 && d / (dt / 1000.0) > 0.7) movingMs += dt
        }
        val acc = sorted.map { it.accuracyM }.filter { it > 0 }.sorted()
        Triple(dist, movingMs, if (acc.isEmpty()) null else acc[acc.size / 2])
    }
    OverlayCard {
        Text(Texts.tr("On the map", "Sulla mappa") + " · " + windowLabel(state.windowH), style = MaterialTheme.typography.titleSmall)
        val (dist, movingMs, medAcc) = stats
        Text(
            Texts.tr("Distance", "Distanza") + " " + distLabel(dist) + " · " + Texts.tr("moving", "in movimento") + " ${movingMs / 60_000} min · " +
                "${visits.size} " + Texts.tr("stays", "soste") + " · ${routine.size} " + Texts.tr("routine places", "luoghi di routine"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${sorted.size} " + Texts.tr("positions", "posizioni") +
                (medAcc?.let { " · GPS " + Texts.tr("median", "mediana") + " ±%.0f m".format(it) } ?: "") +
                (here?.let { " · " + Texts.tr("now", "ora") + " %.5f, %.5f".format(it.lat, it.lon) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        if (devices.isNotEmpty()) Text(
            Texts.tr(
                "${devices.size} device(s) that may follow you: ◆ = your position when heard, from all stored data.",
                "${devices.size} dispositivi che potrebbero seguirti: ◆ = tua posizione quando sentiti, da tutti i dati salvati.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { state.showInfo = false }) { Text(Texts.tr("Close", "Chiudi")) }
    }
}

@Composable
private fun MapChip(label: String, on: Boolean, dot: androidx.compose.ui.graphics.Color? = null, onChange: (Boolean) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        // A device chip keeps its marker colour so the chip and the ◆ on the map match.
        color = when {
            dot != null && on -> MapColors.background
            dot != null -> dot
            on -> MaterialTheme.colorScheme.onPrimary
            else -> MapColors.label
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) (dot ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.92f) else MapColors.background.copy(alpha = 0.85f))
            .border(1.dp, if (on) (dot ?: MaterialTheme.colorScheme.primary) else (dot ?: MapColors.gridMajor), RoundedCornerShape(50))
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun windowLabel(h: Int) = when (h) {
    6 -> Texts.tr("last 6 h", "ultime 6 h")
    24 -> Texts.tr("last 24 h", "ultime 24 h")
    72 -> Texts.tr("last 3 days", "ultimi 3 giorni")
    else -> Texts.tr("last 7 days", "ultimi 7 giorni")
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun MenuCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(text = { Text((if (checked) "☑ " else "☐ ") + text) }, onClick = { onChange(!checked) })
}

@Composable
private fun OverlayCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    ) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
}

/** Centre, radius and name of a routine place. A tap on the map moves the centre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditor(
    e: RoutineEdit,
    onChange: (RoutineEdit) -> Unit,
    onSave: (RoutineEdit) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    OverlayCard {
        Text(
            if (e.id == null) Texts.tr("New routine place", "Nuovo luogo di routine") else Texts.tr("Routine place area", "Area del luogo di routine"),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            Texts.tr("Tap the map to move the centre.", "Tocca la mappa per spostare il centro."),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Texts.tr("Radius", "Raggio") + " ${e.radiusM.roundToInt()} m", style = MaterialTheme.typography.labelLarge)
            Slider(value = sliderOf(e.radiusM), onValueChange = { onChange(e.copy(radiusM = radiusOf(it))) }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(50, 100, 200, 500, 1000).forEach { r ->
                FilterChip(selected = e.radiusM.roundToInt() == r, onClick = { onChange(e.copy(radiusM = r.toDouble())) }, label = { Text("$r") })
            }
        }
        OutlinedTextField(
            value = e.label, onValueChange = { onChange(e.copy(label = it)) }, singleLine = true,
            label = { Text(Texts.tr("Name (e.g. home, office)", "Nome (es. casa, ufficio)")) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(e) }) { Text(Texts.tr("Save", "Salva")) }
            TextButton(onClick = onCancel) { Text(Texts.tr("Cancel", "Annulla")) }
            if (onDelete != null) TextButton(onClick = onDelete) { Text(Texts.tr("Remove", "Rimuovi"), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DownloadDialog(box: DoubleArray, onDismiss: () -> Unit, onStart: (String, Int) -> Unit) {
    var z by remember { mutableIntStateOf(15) }
    var name by remember { mutableStateOf("area-" + LocalDate.now().toString()) }
    val km = remember(box) {
        val midLat = (box[1] + box[3]) / 2
        val w = (box[2] - box[0]) * 111.32 * cos(Math.toRadians(midLat))
        val h = (box[3] - box[1]) * 110.57
        w to h
    }
    val tiles = MapDownload.tileEstimate(box, z)
    // Very rough: ~8 KB per vector tile on average in towns, less in the countryside.
    val mb = tiles * 8 / 1024
    val tooBig = tiles > 400_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.tr("Download this area", "Scarica quest'area")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.1f × %.1f km".format(km.first, km.second))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(12, 13, 14, 15).forEach { lv ->
                        FilterChip(selected = z == lv, onClick = { z = lv }, label = { Text("z$lv") })
                    }
                }
                Text(
                    Texts.tr(
                        "z15 shows every street and its name; z13 is enough for towns and main roads.",
                        "z15 mostra ogni strada col suo nome; z13 basta per paesi e strade principali.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(Texts.tr("About $tiles tiles, up to ~$mb MB", "Circa $tiles tasselli, fino a ~$mb MB"), style = MaterialTheme.typography.bodySmall)
                if (tooBig) Text(
                    Texts.tr("Too large: zoom in or choose a lower level.", "Troppo grande: avvicinati o scegli un livello più basso."),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(Texts.tr("Name", "Nome")) })
                Text(
                    Texts.tr(
                        "OpenStreetMap data from the Protomaps daily build. This one download tells their server which rectangle you asked for; after that the map works offline.",
                        "Dati OpenStreetMap dalla build giornaliera di Protomaps. Questo unico download fa sapere al loro server quale rettangolo hai chiesto; poi la mappa funziona offline.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(enabled = !tooBig, onClick = { onStart(name, z) }) { Text(Texts.tr("Download", "Scarica")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.tr("Cancel", "Annulla")) } },
    )
}

/** Full-screen version of the map, sharing [state] with the inline one. */
@Composable
fun FullscreenMap(content: @Composable () -> Unit, onClose: () -> Unit) {
    FullScreenDialog(onDismiss = onClose, background = MapColors.background) { Box(Modifier.fillMaxSize()) { content() } }
}

@Composable
private fun MapButton(glyph: MapGlyph, description: String, active: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = if (active) androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        ) else androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
    ) { MapGlyphIcon(glyph, description) }
}

/** Map button icons. Drawn here (or from the core icon set) so no font can turn them into boxes or emoji. */
enum class MapGlyph { MENU, INFO, CLOSE, PLUS, MINUS, FULLSCREEN, FIT, PLAY, STOP, LOCATE }

@Composable
private fun MapGlyphIcon(g: MapGlyph, description: String) {
    val core = when (g) {
        MapGlyph.MENU -> androidx.compose.material.icons.Icons.Filled.Menu
        MapGlyph.INFO -> androidx.compose.material.icons.Icons.Filled.Info
        MapGlyph.CLOSE -> androidx.compose.material.icons.Icons.Filled.Close
        MapGlyph.PLUS -> androidx.compose.material.icons.Icons.Filled.Add
        MapGlyph.PLAY -> androidx.compose.material.icons.Icons.Filled.PlayArrow
        else -> null
    }
    if (core != null) { androidx.compose.material3.Icon(core, description); return }
    val c = androidx.compose.material3.LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { contentDescription = description }) {
        val w = size.width; val sw = 2.2.dp.toPx(); val k = w * 0.3f
        val stroke = Stroke(sw, cap = StrokeCap.Round)
        when (g) {
            MapGlyph.MINUS -> drawLine(c, Offset(w * 0.2f, w / 2), Offset(w * 0.8f, w / 2), sw, StrokeCap.Round)
            MapGlyph.FULLSCREEN -> {
                val a = w * 0.15f; val b = w * 0.85f
                listOf(Offset(a, a) to Offset(1f, 1f), Offset(b, a) to Offset(-1f, 1f), Offset(a, b) to Offset(1f, -1f), Offset(b, b) to Offset(-1f, -1f)).forEach { (p, d) ->
                    drawLine(c, p, Offset(p.x + d.x * k, p.y), sw, StrokeCap.Round)
                    drawLine(c, p, Offset(p.x, p.y + d.y * k), sw, StrokeCap.Round)
                }
            }
            MapGlyph.FIT -> {
                drawRect(c, Offset(w * 0.22f, w * 0.22f), androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.56f), style = stroke)
                drawCircle(c, w * 0.08f, Offset(w / 2, w / 2))
            }
            MapGlyph.STOP -> drawRect(c, Offset(w * 0.25f, w * 0.25f), androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.5f))
            MapGlyph.LOCATE -> {
                drawCircle(c, w * 0.28f, Offset(w / 2, w / 2), style = stroke)
                drawCircle(c, w * 0.11f, Offset(w / 2, w / 2))
                listOf(Offset(w / 2, 0f) to Offset(w / 2, w * 0.18f), Offset(w / 2, w) to Offset(w / 2, w * 0.82f),
                    Offset(0f, w / 2) to Offset(w * 0.18f, w / 2), Offset(w, w / 2) to Offset(w * 0.82f, w / 2)).forEach { (a, b) ->
                    drawLine(c, a, b, sw, StrokeCap.Round)
                }
            }
            else -> {}
        }
    }
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
