// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import dev.retrovision.core.map.MapFormatException
import dev.retrovision.core.map.MapInfo
import dev.retrovision.core.map.PmtilesReader
import dev.retrovision.core.map.TileSource
import dev.retrovision.core.map.TileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** An imported offline map file, stored in app-private storage. */
data class OfflineMap(val file: File, val info: MapInfo) {
    val id: String get() = file.name
    val sizeMb: Long get() = file.length() / (1024 * 1024)
}

/**
 * Offline basemaps: PMTiles (vector or raster) and MBTiles (vector or raster).
 * Files are copied into app storage once; rendering never touches the network.
 */
object OfflineMaps {
    private const val PREF = "offlineMap"
    private lateinit var dir: File
    private lateinit var ctx: Context

    private val _maps = MutableStateFlow<List<OfflineMap>>(emptyList())
    val maps: StateFlow<List<OfflineMap>> = _maps

    private val _active = MutableStateFlow<Pair<OfflineMap, TileSource>?>(null)
    /** The open map used by the map view, or null for the plain grid. */
    val active: StateFlow<Pair<OfflineMap, TileSource>?> = _active

    private val _import = MutableStateFlow<Float?>(null)
    /** Import progress 0..1, null when idle. */
    val importProgress: StateFlow<Float?> = _import

    fun init(context: Context) {
        ctx = context.applicationContext
        dir = File(ctx.noBackupFilesDir, "maps").apply { mkdirs() }
        refresh()
        val want = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREF, null)
        val pick = _maps.value.firstOrNull { it.id == want }
        if (pick != null) runCatching { activate(pick) }
    }

    private fun refresh() {
        _maps.value = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.mapNotNull { f ->
            runCatching { open(f).use { OfflineMap(f, it.info) } }.getOrNull()
        }.sortedBy { it.file.name.lowercase() }
    }

    fun directory(): File = dir

    /** Validates a finished file, moves it into place and activates it. */
    @Synchronized
    fun adopt(tmp: File, target: File): OfflineMap {
        val info = open(tmp).use { it.info }
        if (_active.value?.first?.id == target.name) activate(null)
        target.delete()
        if (!tmp.renameTo(target)) throw MapFormatException("Could not store the map")
        refresh()
        val m = OfflineMap(target, info)
        activate(m)
        return m
    }

    fun open(f: File): TileSource = when {
        f.name.endsWith(".mbtiles", true) -> MbtilesSource(f)
        else -> PmtilesReader.open(f)
    }

    @Synchronized
    fun activate(m: OfflineMap?) {
        val old = _active.value
        _active.value = m?.let { it to open(it.file) }
        old?.second?.close()
        TileCache.clear()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(PREF, m?.id).apply()
    }

    fun delete(m: OfflineMap) {
        if (_active.value?.first?.id == m.id) activate(null)
        m.file.delete()
        refresh()
    }

    /** Copies a user-picked file (SAF) into app storage, validates it, and activates it. */
    suspend fun import(uri: Uri): Result<OfflineMap> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            var name = "map.pmtiles"
            var total = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    if (!c.isNull(1)) total = c.getLong(1)
                }
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!name.endsWith(".pmtiles", true) && !name.endsWith(".mbtiles", true)) {
                throw MapFormatException("Only .pmtiles and .mbtiles files are supported")
            }
            val target = File(dir, name)
            // Hidden temp name that keeps the extension, so open() picks the right reader for validation.
            val tmp = File(dir, ".import-$name")
            _import.value = 0f
            try {
                cr.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _import.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                val info = open(tmp).use { it.info }
                if (info.type == TileType.AVIF || info.type == TileType.UNKNOWN) throw MapFormatException("Tile format ${info.type} not supported")
                if (_active.value?.first?.id == target.name) activate(null)
                target.delete()
                if (!tmp.renameTo(target)) throw MapFormatException("Could not store the map")
                refresh()
                val m = OfflineMap(target, info)
                activate(m)
                m
            } finally {
                tmp.delete()
                _import.value = null
            }
        }
    }
}

/** MBTiles (SQLite, TMS rows). Vector tiles may be gzipped; the decoder handles that. */
class MbtilesSource(file: File) : TileSource {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    override val info: MapInfo

    init {
        val meta = HashMap<String, String>()
        db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: "" }
        val type = when (meta["format"]?.lowercase()) {
            "pbf", "mvt" -> TileType.MVT
            "png" -> TileType.PNG
            "jpg", "jpeg" -> TileType.JPEG
            "webp" -> TileType.WEBP
            else -> throw MapFormatException("Unknown MBTiles format '${meta["format"]}'")
        }
        var minZ = meta["minzoom"]?.toIntOrNull()
        var maxZ = meta["maxzoom"]?.toIntOrNull()
        if (minZ == null || maxZ == null) {
            db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                if (c.moveToFirst()) { minZ = c.getInt(0); maxZ = c.getInt(1) }
            }
        }
        val b = meta["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }
            ?: listOf(-180.0, -85.0, 180.0, 85.0)
        val center = meta["center"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        info = MapInfo(
            type, minZ ?: 0, maxZ ?: 14, b.toDoubleArray(),
            center?.getOrNull(2)?.toInt() ?: (minZ ?: 0),
            center?.getOrNull(0) ?: ((b[0] + b[2]) / 2), center?.getOrNull(1) ?: ((b[1] + b[3]) / 2),
        )
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val row = (1 shl z) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), row.toString()),
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    override fun close() = db.close()
}
