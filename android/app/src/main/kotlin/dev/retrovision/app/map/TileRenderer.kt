package dev.retrovision.app.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.retrovision.core.map.Mvt
import dev.retrovision.core.map.TileSource
import dev.retrovision.core.map.TileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class TileKey(val map: String, val z: Int, val x: Int, val y: Int)

/** Decoded tiles ready to draw. Rendering runs on two background threads; [version] ticks when a tile lands. */
object TileCache {
    const val PX = 512
    private val scope = CoroutineScope(SupervisorJob() + Executors.newFixedThreadPool(2).asCoroutineDispatcher())
    private val cache = object : LruCache<TileKey, ImageBitmap>(56 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: ImageBitmap) = value.width * value.height * 2
    }
    private val inFlight = HashSet<TileKey>()
    private val missing = HashSet<TileKey>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun clear() {
        synchronized(this) { inFlight.clear(); missing.clear() }
        cache.evictAll()
        _version.value++
    }

    fun peek(k: TileKey): ImageBitmap? = cache.get(k)

    /** Returns the tile if ready, otherwise schedules it and returns null. */
    fun get(k: TileKey, src: TileSource): ImageBitmap? {
        cache.get(k)?.let { return it }
        synchronized(this) {
            if (k in missing || !inFlight.add(k)) return null
            // Drop stale requests when the user flies around: keep the queue short.
            if (inFlight.size > 48) { inFlight.remove(k); return null }
        }
        scope.launch {
            val img = runCatching { render(src, k) }.getOrNull()
            synchronized(this@TileCache) {
                inFlight.remove(k)
                if (img == null) missing.add(k)
            }
            if (img != null) { cache.put(k, img); _version.value++ }
        }
        return null
    }

    private fun render(src: TileSource, k: TileKey): ImageBitmap? {
        val info = src.info
        // Overzoom: beyond the archive's max zoom, draw the matching part of the deepest tile.
        val sz = min(k.z, info.maxZoom)
        val dz = k.z - sz
        val bytes = src.tile(sz, k.x shr dz, k.y shr dz) ?: return null
        return when (info.type) {
            TileType.MVT -> VectorStyle.render(bytes, k.z, k.x, k.y, dz).asImageBitmap()
            TileType.PNG, TileType.JPEG, TileType.WEBP -> {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (dz == 0) bmp.asImageBitmap()
                else {
                    val f = 1 shl dz
                    val w = bmp.width / f
                    val ox = (k.x - ((k.x shr dz) shl dz)) * w
                    val oy = (k.y - ((k.y shr dz) shl dz)) * w
                    Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, ox, oy, max(1, w), max(1, w)), bmp.width, bmp.height, true).asImageBitmap()
                }
            }
            else -> null
        }
    }
}

/**
 * Dark cartographic style for vector tiles. Understands the Protomaps basemap schema
 * (earth, water, landuse, landcover, roads, transit, buildings, boundaries, places)
 * and the OpenMapTiles schema (water, waterway, landuse, landcover, park, transportation, building, boundary, place).
 */
object VectorStyle {
    private const val WATER = 0xFF0A1C2B.toInt()
    private const val LAND = 0xFF0F151C.toInt()
    private const val PARK = 0xFF0F2118.toInt()
    private const val URBAN = 0xFF131A23.toInt()
    private const val INDUSTRIAL = 0xFF16161F.toInt()
    private const val BUILDING = 0xFF1B2430.toInt()
    private const val ROAD_HIGHWAY = 0xFF6A84A6.toInt()
    private const val ROAD_MAJOR = 0xFF4A5E78.toInt()
    private const val ROAD_MINOR = 0xFF33414F.toInt()
    private const val ROAD_PATH = 0xFF2C3744.toInt()
    private const val ROAD_LABEL = 0xFF8FA3B8.toInt()
    private const val POI_LABEL = 0xFF7F93A8.toInt()
    private const val RAIL = 0xFF2C3340.toInt()
    private const val BOUNDARY = 0xFF4A3F66.toInt()
    private const val LABEL = 0xFF9DB0C4.toInt()
    private const val HALO = 0xFF0B0F14.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)

    @Synchronized
    fun render(bytes: ByteArray, z: Int, x: Int, y: Int, dz: Int): Bitmap {
        val layers = Mvt.decode(bytes).associateBy { it.name }
        val bmp = Bitmap.createBitmap(TileCache.PX, TileCache.PX, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val protomaps = "earth" in layers
        c.drawColor(if (protomaps) WATER else LAND)

        val scale = 2.0.pow(dz).toFloat()
        val ox = -((x - ((x shr dz) shl dz)) * TileCache.PX).toFloat()
        val oy = -((y - ((y shr dz) shl dz)) * TileCache.PX).toFloat()
        fun path(layer: Mvt.Layer, parts: List<FloatArray>, close: Boolean): Path {
            val k = TileCache.PX.toFloat() / layer.extent * scale
            val p = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (part in parts) {
                if (part.size < 2) continue
                p.moveTo(part[0] * k + ox, part[1] * k + oy)
                var i = 2
                while (i + 1 < part.size) { p.lineTo(part[i] * k + ox, part[i + 1] * k + oy); i += 2 }
                if (close) p.close()
            }
            return p
        }
        fun polygons(name: String, color: (Mvt.Feature) -> Int?) {
            val l = layers[name] ?: return
            for (f in l.features) {
                if (f.type != Mvt.GeomType.POLYGON) continue
                val col = color(f) ?: continue
                fill.color = col
                c.drawPath(path(l, f.parts, true), fill)
            }
        }
        // Line width grows with zoom like a real road would, clamped for readability.
        fun w(base: Float) = (base * 2.0.pow((z - 14).toDouble()).toFloat()).coerceIn(0.6f, base * 6f)
        fun lines(name: String, style: (Mvt.Feature) -> Triple<Int, Float, Boolean>?) {
            val l = layers[name] ?: return
            for (f in l.features) {
                if (f.type != Mvt.GeomType.LINESTRING) continue
                val (col, width, dashed) = style(f) ?: continue
                line.color = col; line.strokeWidth = width; line.pathEffect = if (dashed) dash else null
                c.drawPath(path(l, f.parts, false), line)
            }
            line.pathEffect = null
        }

        // Land and land use
        polygons("earth") { LAND }
        polygons("landcover") { f ->
            when (f.str("kind") ?: f.str("class")) {
                "forest", "wood", "grassland", "grass", "scrub", "farmland", "park" -> PARK
                "urban_area" -> URBAN
                else -> null
            }
        }
        polygons("landuse") { f ->
            when (f.str("kind") ?: f.str("class")) {
                "park", "forest", "wood", "grass", "nature_reserve", "garden", "golf_course", "cemetery", "recreation_ground", "meadow", "village_green" -> PARK
                "residential", "neighbourhood", "suburb", "pedestrian", "school", "university", "hospital", "commercial", "retail" -> URBAN
                "industrial", "railway", "military", "quarry", "aerodrome", "landfill" -> INDUSTRIAL
                else -> null
            }
        }
        polygons("park") { PARK }
        polygons("water") { WATER }
        lines("water") { Triple(WATER, w(2.5f), false) }
        lines("waterway") { Triple(WATER, w(2.5f), false) }
        if (z >= 14) {
            polygons("buildings") { BUILDING }
            polygons("building") { BUILDING }
        }
        lines("boundaries") { Triple(BOUNDARY, 1.5f, true) }
        lines("boundary") { f -> if (((f.num("admin_level") ?: 2.0)) <= 4) Triple(BOUNDARY, 1.5f, true) else null }

        // Roads, drawn minor first so major roads sit on top.
        fun roadClass(f: Mvt.Feature): Int = when (f.str("kind") ?: f.str("pmap:kind") ?: f.str("class")) {
            "highway", "motorway", "trunk" -> 3
            "major_road", "primary", "secondary" -> 2
            "minor_road", "tertiary", "minor", "service", "street", "residential" -> 1
            "path", "track", "footway", "cycleway", "pedestrian" -> 0
            "rail" -> -1
            else -> 1
        }
        for (rank in 0..3) {
            val style: (Mvt.Feature) -> Triple<Int, Float, Boolean>? = { f ->
                if (roadClass(f) != rank) null else when (rank) {
                    3 -> Triple(ROAD_HIGHWAY, w(5f), false)
                    2 -> Triple(ROAD_MAJOR, w(3.5f), false)
                    1 -> Triple(ROAD_MINOR, w(2.2f), false)
                    else -> if (z >= 14) Triple(ROAD_PATH, w(1.2f), true) else null
                }
            }
            lines("roads", style)
            lines("transportation", style)
        }
        lines("transit") { f -> if ((f.str("kind") ?: "") == "rail") Triple(RAIL, w(1.6f), true) else null }
        lines("transportation") { f -> if (roadClass(f) == -1) Triple(RAIL, w(1.6f), true) else null }

        val placed = ArrayList<RectF>()
        fun free(r: RectF): Boolean {
            if (placed.any { RectF.intersects(it, r) }) return false
            placed.add(r); return true
        }
        fun nameOf(f: Mvt.Feature) = f.str("name:it") ?: f.str("name") ?: f.str("name:latin")

        // Place labels first: they matter most for orientation.
        val places = layers["places"] ?: layers["place"]
        if (places != null) {
            val k = TileCache.PX.toFloat() / places.extent * scale
            text.textAlign = Paint.Align.CENTER
            val ordered = places.features.filter { it.type == Mvt.GeomType.POINT }.sortedBy {
                when (it.str("kind") ?: it.str("pmap:kind") ?: it.str("class")) {
                    "country" -> 0; "region", "state" -> 1; "locality", "city" -> 2; "town" -> 3; "village" -> 4; else -> 5
                }
            }
            for (f in ordered) {
                val name = nameOf(f) ?: continue
                val kind = f.str("kind") ?: f.str("pmap:kind") ?: f.str("class") ?: ""
                val size = when (kind) {
                    "country" -> 30f
                    "region", "state" -> if (z <= 9) 26f else continue
                    "locality", "city" -> 28f
                    "town" -> 25f
                    "village", "hamlet" -> if (z >= 11) 22f else continue
                    "neighbourhood", "suburb", "quarter", "macrohood", "microhood" -> if (z >= 13) 21f else continue
                    else -> continue
                }
                val pt = f.parts.firstOrNull() ?: continue
                val px = pt[0] * k + ox
                val py = pt[1] * k + oy
                if (px < -60 || py < -20 || px > TileCache.PX + 60 || py > TileCache.PX + 20) continue
                text.textSize = size
                text.typeface = if (kind == "neighbourhood" || kind == "suburb" || kind == "quarter") Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC) else Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                val w = text.measureText(name)
                if (!free(RectF(px - w / 2 - 4, py - size, px + w / 2 + 4, py + 6))) continue
                text.style = Paint.Style.STROKE; text.strokeWidth = 6f; text.color = HALO
                c.drawText(name, px, py, text)
                text.style = Paint.Style.FILL; text.color = LABEL
                c.drawText(name, px, py, text)
            }
        }

        // Street names along the street, once per name per tile.
        val roadLayer = layers["roads"]?.let { it to "kind" } ?: layers["transportation_name"]?.let { it to "class" }
        if (roadLayer != null && z >= 13) {
            val (l, _) = roadLayer
            val k = TileCache.PX.toFloat() / l.extent * scale
            val seen = HashSet<String>()
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            text.textAlign = Paint.Align.CENTER
            val rankOf = { f: Mvt.Feature -> roadClass(f) }
            for (f in l.features.sortedByDescending(rankOf)) {
                if (f.type != Mvt.GeomType.LINESTRING) continue
                val rank = rankOf(f)
                val minZ = when (rank) { 3 -> 13; 2 -> 14; 1 -> 15; 0 -> 17; else -> 99 }
                if (z < minZ) continue
                val name = nameOf(f) ?: f.str("ref") ?: continue
                if (!seen.add(name)) continue
                val part = f.parts.maxByOrNull { it.size } ?: continue
                if (part.size < 4) continue
                val forward = part[0] <= part[part.size - 2]
                val p = Path()
                val n = part.size / 2
                for (i in 0 until n) {
                    val idx = if (forward) i else n - 1 - i
                    val x = part[idx * 2] * k + ox; val y = part[idx * 2 + 1] * k + oy
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                text.textSize = if (rank >= 2) 21f else 19f
                val len = PathMeasure(p, false).length
                val w = text.measureText(name)
                if (len < w + 24) continue
                val start = (len - w) / 2
                val pm = PathMeasure(p, false)
                val pos = FloatArray(2)
                pm.getPosTan(start + w / 2, pos, null)
                if (!free(RectF(pos[0] - w / 2, pos[1] - 14, pos[0] + w / 2, pos[1] + 14))) continue
                text.textAlign = Paint.Align.LEFT
                text.style = Paint.Style.STROKE; text.strokeWidth = 5f; text.color = HALO
                c.drawTextOnPath(name, p, start, 7f, text)
                text.style = Paint.Style.FILL; text.color = ROAD_LABEL
                c.drawTextOnPath(name, p, start, 7f, text)
                text.textAlign = Paint.Align.CENTER
            }
        }

        // A few points of interest at street level: stations, hospitals, police, pharmacies, fuel.
        val pois = layers["pois"] ?: layers["poi"]
        if (pois != null && z >= 16) {
            val k = TileCache.PX.toFloat() / pois.extent * scale
            text.textSize = 18f
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            text.textAlign = Paint.Align.LEFT
            for (f in pois.features) {
                if (f.type != Mvt.GeomType.POINT) continue
                val kind = f.str("kind") ?: f.str("class") ?: continue
                val glyph = when (kind) {
                    "station", "train_station", "railway", "subway", "bus_station" -> "🚉"
                    "hospital", "clinic" -> "🏥"
                    "police" -> "👮"
                    "pharmacy" -> "💊"
                    "fuel" -> "⛽"
                    "parking" -> "🅿"
                    "supermarket" -> "🛒"
                    "school", "university", "college" -> "🎓"
                    "park" -> "🌳"
                    else -> continue
                }
                val name = nameOf(f) ?: ""
                val pt = f.parts.firstOrNull() ?: continue
                val px = pt[0] * k + ox; val py = pt[1] * k + oy
                val label = if (name.isEmpty()) glyph else "$glyph $name"
                val w = text.measureText(label)
                if (!free(RectF(px - 10, py - 16, px + w, py + 6))) continue
                text.style = Paint.Style.STROKE; text.strokeWidth = 5f; text.color = HALO
                c.drawText(label, px - 10, py, text)
                text.style = Paint.Style.FILL; text.color = POI_LABEL
                c.drawText(label, px - 10, py, text)
            }
        }
        return bmp
    }
}
