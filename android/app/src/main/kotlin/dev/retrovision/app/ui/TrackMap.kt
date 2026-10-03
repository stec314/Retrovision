// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.retrovision.app.map.MapDownload
import dev.retrovision.app.map.OfflineMap
import dev.retrovision.app.map.OfflineMaps
import dev.retrovision.core.analysis.DeviceVisit
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.Level
import dev.retrovision.core.analysis.Visit
import dev.retrovision.core.map.TileType
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
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
    var selection by mutableStateOf<MapSel?>(null)
    /** Scrubber position in [0,1] over the shown window; null = off (whole track bright). */
    var scrub by mutableStateOf<Float?>(null)
    var focus by mutableStateOf<Pair<Double, Double>?>(null)
    var windowH by mutableIntStateOf(24)
    var showTrack by mutableStateOf(true)
    var showStays by mutableStateOf(true)
    var showRoutine by mutableStateOf(true)
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
    /** Bumped by [reset]: the map fits everything it shows. */
    var fitEpoch by mutableIntStateOf(0)
    /** Last camera, so the full-screen map (a second map view) opens where the inline one was. */
    var camera: org.maplibre.android.camera.CameraPosition? = null

    fun reset() { follow = false; fitEpoch++ }
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

/** Times a device was heard, widened by a minute each side: "present" for a fix of your track. */
private class Presence(visits: List<DeviceVisit>) {
    private val starts: LongArray
    private val ends: LongArray
    init {
        val v = visits.sortedBy { it.startMs }
        starts = LongArray(v.size) { v[it].startMs - 60_000L }
        ends = LongArray(v.size) { v[it].endMs + 60_000L }
    }
    fun at(t: Long): Boolean {
        var lo = 0; var hi = starts.size - 1; var k = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (starts[mid] <= t) { k = mid; lo = mid + 1 } else hi = mid - 1 }
        // A few earlier intervals can be longer: check back a little.
        var i = k
        while (i >= 0 && i >= k - 8) { if (t <= ends[i]) return true; i-- }
        return false
    }
}

private fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

private fun circlePolygon(lat: Double, lon: Double, radiusM: Double, n: Int = 64): Polygon {
    val pts = ArrayList<Point>(n + 1)
    val dLat = radiusM / 110_574.0
    val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)))
    for (i in 0..n) {
        val a = 2 * Math.PI * i / n
        pts += Point.fromLngLat(lon + dLon * kotlin.math.sin(a), lat + dLat * cos(a))
    }
    return Polygon.fromLngLats(listOf(pts))
}

private fun boundsOf(points: List<Pair<Double, Double>>, minSpanM: Double = 300.0): LatLngBounds? {
    if (points.isEmpty()) return null
    var n = points.maxOf { it.first }; var s = points.minOf { it.first }
    var e = points.maxOf { it.second }; var w = points.minOf { it.second }
    val lat = (n + s) / 2
    val minLat = minSpanM / 110_574.0
    val minLon = minSpanM / (111_320.0 * cos(Math.toRadians(lat)))
    if (n - s < minLat) { val c = (n + s) / 2; n = c + minLat / 2; s = c - minLat / 2 }
    if (e - w < minLon) { val c = (e + w) / 2; e = c + minLon / 2; w = c - minLon / 2 }
    return LatLngBounds.from(n, e, s, w)
}

private fun spanBounds(lat: Double, lon: Double, spanM: Double): LatLngBounds {
    val dLat = spanM / 2 / 110_574.0
    val dLon = spanM / 2 / (111_320.0 * cos(Math.toRadians(lat)))
    return LatLngBounds.from(lat + dLat, lon + dLon, lat - dLat, lon - dLon)
}

/** Style JSON for the active offline map: Protomaps dark layers over the local file, or a plain background. */
private fun styleJson(ctx: android.content.Context, basemap: OfflineMap?): String {
    val bg = hex(MapColors.background)
    val root = org.json.JSONObject()
    root.put("version", 8)
    root.put("glyphs", "asset://map/fonts/{fontstack}/{range}.pbf")
    root.put("sprite", "asset://map/sprites/dark")
    val sources = org.json.JSONObject()
    val layers = org.json.JSONArray()
    val path = basemap?.file?.absolutePath
    val url = when {
        path == null -> null
        path.endsWith(".mbtiles", true) -> "mbtiles://$path"
        else -> "pmtiles://file://$path"
    }
    if (url != null && basemap?.info?.type == TileType.MVT) {
        sources.put("protomaps", org.json.JSONObject().put("type", "vector").put("url", url).put("attribution", "© OpenStreetMap"))
        val l = org.json.JSONArray(ctx.assets.open("map/layers-dark.json").bufferedReader().use { it.readText() })
        for (i in 0 until l.length()) layers.put(l.get(i))
    } else {
        layers.put(org.json.JSONObject().put("id", "bg").put("type", "background").put("paint", org.json.JSONObject().put("background-color", bg)))
        if (url != null) {
            sources.put("raster", org.json.JSONObject().put("type", "raster").put("url", url).put("tileSize", 256))
            // Raster maps are made for daylight: dimmed and desaturated so the overlays stay readable.
            layers.put(
                org.json.JSONObject().put("id", "raster").put("type", "raster").put("source", "raster")
                    .put("paint", org.json.JSONObject().put("raster-brightness-max", 0.55).put("raster-saturation", -0.4)),
            )
        }
    }
    root.put("sources", sources)
    root.put("layers", layers)
    return root.toString()
}

private const val SRC_ROUTINE = "rv-routine"
private const val SRC_TRACK = "rv-track"
private const val SRC_PRESENCE = "rv-presence"
private const val SRC_STAYS = "rv-stays"
private const val SRC_HERE = "rv-here"
private const val SRC_HERE_ACC = "rv-here-acc"
private const val SRC_EDIT = "rv-edit"
private const val SRC_SEL = "rv-sel"
private const val SRC_SCRUB = "rv-scrub"
private fun devSrc(i: Int) = "rv-dev-$i"
private fun devClusterLayer(i: Int) = "rv-devc-$i"
private fun devCountLayer(i: Int) = "rv-devn-$i"
private fun devPointLayer(i: Int) = "rv-devp-$i"
private fun diamond(i: Int) = "rv-dia-$i"

private fun diamondBitmap(color: androidx.compose.ui.graphics.Color, density: Float): android.graphics.Bitmap {
    val sizeDp = 16f
    val px = (sizeDp * density).toInt().coerceAtLeast(8)
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    b.density = (160 * density).toInt()
    val c = android.graphics.Canvas(b)
    val path = android.graphics.Path().apply {
        val m = px / 2f; val r = px / 2f - 1.5f * density
        moveTo(m, m - r); lineTo(m + r, m); lineTo(m, m + r); lineTo(m - r, m); close()
    }
    c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
    c.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * density; this.color = MapColors.background.toArgb()
    })
    return b
}

/** Overlay sources and layers (your data) on top of the base map. Called on every style load. */
private fun addOverlays(st: Style, density: Float) {
    val empty = FeatureCollection.fromFeatures(emptyList())
    fun src(id: String, opts: GeoJsonOptions? = null) =
        st.addSource(if (opts == null) GeoJsonSource(id, empty) else GeoJsonSource(id, empty, opts))
    val routine = hex(MapColors.routine); val track = hex(MapColors.track); val stay = hex(MapColors.stay)
    val me = hex(MapColors.me); val bg = hex(MapColors.background)

    src(SRC_ROUTINE)
    st.addLayer(FillLayer("rv-routine-fill", SRC_ROUTINE).withProperties(PropertyFactory.fillColor(routine), PropertyFactory.fillOpacity(0.10f)))
    st.addLayer(LineLayer("rv-routine-line", SRC_ROUTINE).withProperties(PropertyFactory.lineColor(routine), PropertyFactory.lineWidth(1.5f)))

    src(SRC_TRACK)
    val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
    st.addLayer(
        LineLayer("rv-track-dim", SRC_TRACK).withProperties(*round, PropertyFactory.lineColor(track), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineOpacity(0.25f))
            .withFilter(Expression.eq(Expression.get("past"), Expression.literal(false))),
    )
    st.addLayer(
        LineLayer("rv-track-glow", SRC_TRACK).withProperties(*round, PropertyFactory.lineColor(track), PropertyFactory.lineWidth(8f), PropertyFactory.lineOpacity(0.2f))
            .withFilter(Expression.eq(Expression.get("past"), Expression.literal(true))),
    )
    st.addLayer(
        LineLayer("rv-track", SRC_TRACK).withProperties(*round, PropertyFactory.lineColor(track), PropertyFactory.lineWidth(2.5f))
            .withFilter(Expression.eq(Expression.get("past"), Expression.literal(true))),
    )

    // Stretches of your track while a device was with you, in its colour.
    src(SRC_PRESENCE)
    st.addLayer(
        LineLayer("rv-presence", SRC_PRESENCE).withProperties(
            *round, PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))), PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.9f),
        ),
    )

    src(SRC_STAYS)
    st.addLayer(
        CircleLayer("rv-stays", SRC_STAYS).withProperties(
            PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))), PropertyFactory.circleColor(stay), PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeColor(stay), PropertyFactory.circleStrokeWidth(2f),
        ),
    )

    DEVICE_COLORS.forEachIndexed { i, color ->
        st.addImage(diamond(i), diamondBitmap(color, density))
        src(devSrc(i), GeoJsonOptions().withCluster(true).withClusterRadius(42).withClusterMaxZoom(15))
        val c = hex(color)
        st.addLayer(
            CircleLayer(devClusterLayer(i), devSrc(i)).withProperties(
                PropertyFactory.circleColor(c), PropertyFactory.circleOpacity(0.9f),
                PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.get("point_count"), Expression.stop(2, 11), Expression.stop(50, 17), Expression.stop(500, 23))),
                PropertyFactory.circleStrokeColor(bg), PropertyFactory.circleStrokeWidth(1.5f),
            ).withFilter(Expression.has("point_count")),
        )
        st.addLayer(
            SymbolLayer(devCountLayer(i), devSrc(i)).withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
                PropertyFactory.textFont(arrayOf("NotoSansMedium")), PropertyFactory.textSize(12f), PropertyFactory.textColor(bg),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
            ).withFilter(Expression.has("point_count")),
        )
        st.addLayer(
            SymbolLayer(devPointLayer(i), devSrc(i)).withProperties(
                PropertyFactory.iconImage(diamond(i)), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ).withFilter(Expression.not(Expression.has("point_count"))),
        )
    }

    src(SRC_HERE_ACC)
    st.addLayer(FillLayer("rv-here-acc", SRC_HERE_ACC).withProperties(PropertyFactory.fillColor(me), PropertyFactory.fillOpacity(0.08f)))
    src(SRC_HERE)
    st.addLayer(CircleLayer("rv-here-halo", SRC_HERE).withProperties(PropertyFactory.circleRadius(14f), PropertyFactory.circleColor(me), PropertyFactory.circleOpacity(0.22f)))
    st.addLayer(
        CircleLayer("rv-here", SRC_HERE).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(me), PropertyFactory.circleStrokeColor(bg), PropertyFactory.circleStrokeWidth(2f),
        ),
    )

    src(SRC_SCRUB)
    st.addLayer(
        CircleLayer("rv-scrub", SRC_SCRUB).withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(bg), PropertyFactory.circleStrokeColor(track), PropertyFactory.circleStrokeWidth(3f),
        ),
    )

    src(SRC_EDIT)
    st.addLayer(FillLayer("rv-edit-fill", SRC_EDIT).withProperties(PropertyFactory.fillColor(routine), PropertyFactory.fillOpacity(0.18f)))
    st.addLayer(
        LineLayer("rv-edit-line", SRC_EDIT).withProperties(
            PropertyFactory.lineColor(routine), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
        ),
    )

    src(SRC_SEL)
    st.addLayer(
        CircleLayer("rv-sel", SRC_SEL).withProperties(
            PropertyFactory.circleRadius(13f), PropertyFactory.circleOpacity(0f), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

private fun Style.set(id: String, fc: FeatureCollection) { getSourceAs<GeoJsonSource>(id)?.setGeoJson(fc) }
private fun fc(features: List<Feature>) = FeatureCollection.fromFeatures(features)

/**
 * Interactive map of your own movements, used inline and full screen: a MapLibre (GPU) view over the
 * offline map file, with Compose controls on top. Pinch, drag and double tap like any maps app; tap to
 * inspect, long press to pick a point. Nothing is fetched from the network: the base map is a local
 * .pmtiles/.mbtiles file and style, fonts and icons are inside the app.
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
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val sorted = remember(fixes) { fixes.sortedBy { it.timeMs } }
    val basemap by OfflineMaps.active.collectAsState()
    val maps by OfflineMaps.maps.collectAsState()
    val download by MapDownload.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var downloadBox by remember { mutableStateOf<DoubleArray?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var mPerPx by remember { mutableStateOf(0.0) }
    val t0 = sorted.firstOrNull()?.timeMs ?: 0L
    val t1 = sorted.lastOrNull()?.timeMs ?: 0L
    val scrubT = state.scrub?.let { t0 + ((t1 - t0) * it).toLong() }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateTimeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    val shownDevices = remember(devices, state.deviceFocus, state.showDevices, state.hiddenDevices) {
        if (!state.showDevices) emptyList()
        else devices.filter { if (state.deviceFocus != null) it.entityId == state.deviceFocus else it.entityId !in state.hiddenDevices }
    }
    // Track thinned to at most ~4000 points, used for drawing and for taps.
    val thin = remember(sorted) {
        val stride = max(1, sorted.size / 4000)
        sorted.filterIndexed { i, _ -> i % stride == 0 || i == sorted.lastIndex }
    }

    // ── The map view and its lifecycle ──
    val mapView = remember {
        val opts = MapLibreMapOptions.createFromAttributes(ctx, null)
            .textureMode(true).attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        MapView(ctx, opts).also { it.onCreate(null) }
    }
    val lifecycle = @Suppress("DEPRECATION") androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var started = false; var resumed = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            map?.cameraPosition?.let { state.camera = it }
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Taps and long presses read the latest data through these.
    val tapData by rememberUpdatedState(Triple(thin, visits, routine))
    val tapDevices by rememberUpdatedState(shownDevices)
    val onTap: (LatLng, Boolean) -> Unit = { ll, long ->
        val m = map
        val e = state.edit
        if (m != null) {
            if (e != null) {
                state.edit = e.copy(lat = ll.latitude, lon = ll.longitude)
            } else if (long) {
                state.selection = MapSel.Point(ll.latitude, ll.longitude)
            } else {
                val p = m.projection.toScreenLocation(ll)
                val hit = 22f * density
                fun d(lat: Double, lon: Double): Float {
                    val q = m.projection.toScreenLocation(LatLng(lat, lon))
                    return kotlin.math.hypot(q.x - p.x, q.y - p.y)
                }
                val box = android.graphics.RectF(p.x - hit, p.y - hit, p.x + hit, p.y + hit)
                val clusterIds = DEVICE_COLORS.indices.map { devClusterLayer(it) }.toTypedArray()
                val cluster = m.queryRenderedFeatures(box, *clusterIds).firstOrNull()
                if (cluster != null) {
                    // A group of markers: zoom in on it.
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, m.cameraPosition.zoom + 2.0))
                } else {
                    val pointIds = DEVICE_COLORS.indices.map { devPointLayer(it) }.toTypedArray()
                    val f = m.queryRenderedFeatures(box, *pointIds).firstOrNull()
                    val dev = f?.let { ft ->
                        val id = ft.getStringProperty("id")
                        val i = ft.getNumberProperty("i")?.toInt() ?: -1
                        val dm = tapDevices.firstOrNull { it.entityId == id }
                        val v = dm?.visits?.getOrNull(i)
                        if (dm != null && v != null) MapSel.DevicePlace(dm, v) else null
                    }
                    val (tTrack, tVisits, tRoutine) = tapData
                    val stay = if (!state.showStays) null else tVisits.minByOrNull { d(it.lat, it.lon) }?.takeIf { d(it.lat, it.lon) < hit }
                    val place = if (!state.showRoutine) null else tRoutine.firstOrNull { it.contains(ll.latitude, ll.longitude) }
                    val fix = if (!state.showTrack) null else tTrack.minByOrNull { d(it.lat, it.lon) }?.takeIf { d(it.lat, it.lon) < hit }
                    state.selection = when {
                        dev != null -> dev
                        stay != null -> MapSel.Stay(stay)
                        fix != null -> MapSel.Fix(fix)
                        place != null -> MapSel.Place(place)
                        else -> null
                    }
                }
            }
        }
    }
    val onTapLatest by rememberUpdatedState(onTap)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.addOnMapClickListener { onTapLatest(it, false); true }
            m.addOnMapLongClickListener { onTapLatest(it, true); true }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) { state.follow = false }
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
            })
            val updateScale = {
                val c = m.cameraPosition.target
                // Metres per physical pixel, times density = metres per dp (what the scale bar is drawn in).
                if (c != null) mPerPx = m.projection.getMetersPerPixelAtLatitude(c.latitude) * density
            }
            m.addOnCameraMoveListener { updateScale() }
            m.addOnCameraIdleListener { updateScale(); state.camera = m.cameraPosition }
            state.camera?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            map = m
        }
    }

    // Style: base map + overlay layers. Reloaded when the base map changes.
    LaunchedEffect(map, basemap?.first?.id) {
        val m = map ?: return@LaunchedEffect
        val json = withContext(Dispatchers.IO) { runCatching { styleJson(ctx, basemap?.first) }.getOrElse { styleJson(ctx, null) } }
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { st -> addOverlays(st, density); style = st }
    }

    // ── Data → sources (only when the data changes, never per frame) ──
    LaunchedEffect(style, routine, state.showRoutine, state.edit?.id) {
        val st = style ?: return@LaunchedEffect
        st.set(SRC_ROUTINE, fc(if (!state.showRoutine) emptyList() else routine.filter { it.id != state.edit?.id }.map { Feature.fromGeometry(circlePolygon(it.lat, it.lon, it.radiusM)) }))
    }
    LaunchedEffect(style, thin, scrubT, state.showTrack) {
        val st = style ?: return@LaunchedEffect
        if (!state.showTrack || thin.size < 2) { st.set(SRC_TRACK, fc(emptyList())); st.set(SRC_SCRUB, fc(emptyList())); return@LaunchedEffect }
        val features = withContext(Dispatchers.Default) {
            val out = ArrayList<Feature>()
            var run = ArrayList<Point>(); var runPast = true; var prevT = Long.MIN_VALUE
            fun flush() {
                if (run.size >= 2) out += Feature.fromGeometry(LineString.fromLngLats(run)).also { it.addBooleanProperty("past", runPast) }
                run = ArrayList()
            }
            for (f in thin) {
                val past = scrubT == null || f.timeMs <= scrubT
                val gap = prevT != Long.MIN_VALUE && f.timeMs - prevT > 10 * 60_000L
                if (gap) flush()
                if (run.isNotEmpty() && past != runPast) {
                    val last = run.last(); flush(); run.add(last)
                }
                runPast = past
                run.add(Point.fromLngLat(f.lon, f.lat))
                prevT = f.timeMs
            }
            flush()
            out
        }
        st.set(SRC_TRACK, fc(features))
        val s = scrubT?.let { interpolate(sorted, it) }
        st.set(SRC_SCRUB, fc(listOfNotNull(s?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
    }
    LaunchedEffect(style, visits, state.showStays) {
        val st = style ?: return@LaunchedEffect
        st.set(SRC_STAYS, fc(if (!state.showStays) emptyList() else visits.map { v ->
            Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).also { it.addNumberProperty("r", (5.0 + v.durationMs / 900_000.0).coerceAtMost(12.0)) }
        }))
    }
    LaunchedEffect(style, shownDevices, devices) {
        val st = style ?: return@LaunchedEffect
        val perSlot = withContext(Dispatchers.Default) {
            DEVICE_COLORS.indices.map { slot ->
                val d = devices.getOrNull(slot)?.takeIf { it in shownDevices } ?: return@map emptyList<Feature>()
                d.visits.mapIndexedNotNull { i, v ->
                    if (v.lat == null || v.lon == null) null
                    else Feature.fromGeometry(Point.fromLngLat(v.lon!!, v.lat!!)).also { it.addStringProperty("id", d.entityId); it.addNumberProperty("i", i) }
                }
            }
        }
        perSlot.forEachIndexed { slot, feats -> st.set(devSrc(slot), fc(feats)) }
    }
    // Presence: for the device in focus, your track coloured where it was with you.
    LaunchedEffect(style, sorted, shownDevices, state.deviceFocus) {
        val st = style ?: return@LaunchedEffect
        val focus = shownDevices.firstOrNull { it.entityId == state.deviceFocus }
        if (focus == null) { st.set(SRC_PRESENCE, fc(emptyList())); return@LaunchedEffect }
        val features = withContext(Dispatchers.Default) {
            val pres = Presence(focus.visits)
            val out = ArrayList<Feature>()
            var run = ArrayList<Point>(); var prevT = Long.MIN_VALUE
            fun flush() {
                if (run.size >= 2) out += Feature.fromGeometry(LineString.fromLngLats(run)).also { it.addStringProperty("color", hex(focus.color)) }
                run = ArrayList()
            }
            for (f in sorted) {
                if (!pres.at(f.timeMs) || (prevT != Long.MIN_VALUE && f.timeMs - prevT > 10 * 60_000L)) flush()
                if (pres.at(f.timeMs)) run.add(Point.fromLngLat(f.lon, f.lat))
                prevT = f.timeMs
            }
            flush()
            out
        }
        st.set(SRC_PRESENCE, fc(features))
    }
    LaunchedEffect(style, here) {
        val st = style ?: return@LaunchedEffect
        val h = here
        st.set(SRC_HERE, fc(listOfNotNull(h?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
        st.set(SRC_HERE_ACC, fc(listOfNotNull(h?.takeIf { it.accuracyM > 0 }?.let { Feature.fromGeometry(circlePolygon(it.lat, it.lon, it.accuracyM.toDouble(), 40)) })))
    }
    LaunchedEffect(style, state.edit) {
        val st = style ?: return@LaunchedEffect
        val e = state.edit
        st.set(SRC_EDIT, fc(listOfNotNull(e?.let { Feature.fromGeometry(circlePolygon(it.lat, it.lon, it.radiusM)) })))
    }
    LaunchedEffect(style, state.selection) {
        val st = style ?: return@LaunchedEffect
        val at = when (val s = state.selection) {
            is MapSel.Fix -> s.fix.lat to s.fix.lon
            is MapSel.Stay -> s.visit.lat to s.visit.lon
            is MapSel.Place -> s.place.lat to s.place.lon
            is MapSel.Point -> s.lat to s.lon
            is MapSel.DevicePlace -> s.visit.lat?.let { la -> s.visit.lon?.let { la to it } }
            null -> null
        }
        st.set(SRC_SEL, fc(listOfNotNull(at?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })))
    }

    // ── Camera ──
    // A requested place (and optionally a ground width to show around it).
    LaunchedEffect(map, state.focus) {
        val m = map ?: return@LaunchedEffect
        val (lat, lon) = state.focus ?: return@LaunchedEffect
        val span = state.focusSpanM
        awaitLaidOut(mapView)
        if (span != null) m.animateCamera(CameraUpdateFactory.newLatLngBounds(spanBounds(lat, lon, span), 0), 600)
        else m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), max(m.cameraPosition.zoom, 15.0)), 600)
        state.focus = null
        state.focusSpanM = null
    }
    // Follow mode: keep your position centred, at the current zoom.
    LaunchedEffect(map, here, state.follow) {
        val m = map ?: return@LaunchedEffect
        val h = here ?: return@LaunchedEffect
        if (!state.follow || state.focus != null) return@LaunchedEffect
        m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(h.lat, h.lon)), 500)
    }
    // Fit everything shown: on "show everything", and once when data first appears with no other view.
    var fitted by remember { mutableStateOf(state.camera != null) }
    var fitEpochDone by remember { mutableIntStateOf(state.fitEpoch) }
    LaunchedEffect(map, state.fitEpoch, sorted.isEmpty(), shownDevices.isEmpty(), here == null) {
        val m = map ?: return@LaunchedEffect
        if (fitted && state.fitEpoch == fitEpochDone) return@LaunchedEffect
        fitEpochDone = state.fitEpoch
        if (state.focus != null || state.follow) { fitted = true; return@LaunchedEffect }
        val pts = thin.map { it.lat to it.lon } + routine.map { it.lat to it.lon } +
            shownDevices.flatMap { d -> d.visits.mapNotNull { v -> v.lat?.let { la -> v.lon?.let { la to it } } } } +
            listOfNotNull(here?.let { it.lat to it.lon })
        val b = boundsOf(pts) ?: basemap?.second?.info?.let { i -> LatLngBounds.from(i.bounds[3], i.bounds[2], i.bounds[1], i.bounds[0]) } ?: return@LaunchedEffect
        awaitLaidOut(mapView)
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, (40 * density).toInt()))
        fitted = true
    }

    Box(modifier.clip(RoundedCornerShape(if (state.fullscreen) 0.dp else 16.dp)).background(MapColors.background)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (sorted.isEmpty() && basemap == null) {
            Text(
                Texts.tr(
                    "No positions yet. Start collecting with location on, or download a map from the menu.",
                    "Ancora nessuna posizione. Avvia la raccolta con la posizione attiva, o scarica una mappa dal menu.",
                ),
                color = MapColors.label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // Attribution (left) and scale bar (right), drawn by us so they stay inside the rounded frame.
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (basemap != null) Text("© OpenStreetMap", color = MapColors.label, style = MaterialTheme.typography.labelSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (mPerPx > 0) ScaleBar(mPerPx)
        }

        // ── Top bar: menu, info and chips (left); zoom and the rest (right) ──
        Box(Modifier.align(Alignment.TopStart).padding(8.dp).padding(end = 56.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapButton(MapGlyph.MENU, Texts.tr("Map options", "Opzioni mappa")) { menu = true }
                    MapButton(MapGlyph.INFO, Texts.tr("Map info", "Info mappa"), active = state.showInfo) { state.showInfo = !state.showInfo }
                    Text(
                        windowLabel(state.windowH) + (basemap?.first?.file?.nameWithoutExtension?.let { " · $it" } ?: ""),
                        color = MapColors.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapChip(Texts.tr("Track", "Percorso"), state.showTrack) { state.showTrack = it }
                    MapChip(Texts.tr("Stays", "Soste"), state.showStays) { state.showStays = it }
                    MapChip(Texts.tr("Routine", "Routine"), state.showRoutine) { state.showRoutine = it }
                    if (devices.size > 1) {
                        MapChip(Texts.tr("Devices", "Dispositivi") + " ${devices.size}", state.showDevices) { state.showDevices = it }
                    }
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
                HorizontalDivider()
                MenuHeader(Texts.tr("Base map", "Mappa di base"))
                DropdownMenuItem(
                    text = { Text((if (basemap == null) "● " else "○ ") + Texts.tr("None", "Nessuna")) },
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
                        map?.projection?.visibleRegion?.latLngBounds?.let { b ->
                            downloadBox = doubleArrayOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
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
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MapButton(
                if (state.fullscreen) MapGlyph.CLOSE else MapGlyph.FULLSCREEN,
                if (state.fullscreen) Texts.tr("Exit full screen", "Esci da schermo intero") else Texts.tr("Full screen", "Schermo intero"),
            ) { state.fullscreen = !state.fullscreen }
            MapButton(MapGlyph.PLUS, Texts.tr("Zoom in", "Avvicina")) { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
            MapButton(MapGlyph.MINUS, Texts.tr("Zoom out", "Allontana")) { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
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

        // ── Bottom: download progress, replay, info, selection or area editor ──
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).padding(bottom = 18.dp)
                .heightIn(max = if (state.fullscreen) 420.dp else 240.dp)
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

            if (state.showInfo && state.edit == null) MapInfoCard(sorted, visits, routine, devices, here, state)

            if (sorted.size >= 2 && state.showTrack && state.showReplay && state.edit == null) {
                OverlayCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(scrubT?.let { timeFmt.format(Date(it)) } ?: Texts.tr("Replay", "Ripercorri"), style = MaterialTheme.typography.labelMedium)
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
                                        Text(Texts.tr("Only this device, with its route", "Solo questo dispositivo, col suo percorso"))
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

/** Waits until the map view has a size: camera fits computed before layout land in the wrong place. */
private suspend fun awaitLaidOut(v: android.view.View) {
    while (v.width == 0 || v.height == 0) kotlinx.coroutines.delay(30)
}

@Composable
private fun ScaleBar(metresPerDp: Double) {
    val target = 90.0
    val m = niceStep(metresPerDp, target)
    val widthDp = (m / metresPerDp).toFloat()
    Column(horizontalAlignment = Alignment.End) {
        Text(distLabel(m), color = MapColors.label, style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.size(width = widthDp.dp, height = 8.dp)) {
            val y = size.height - 2.dp.toPx()
            val sw = 2.dp.toPx()
            drawLine(MapColors.label, Offset(0f, y), Offset(size.width, y), sw)
            drawLine(MapColors.label, Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw)
            drawLine(MapColors.label, Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw)
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
internal fun MapGlyphIcon(g: MapGlyph, description: String) {
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

