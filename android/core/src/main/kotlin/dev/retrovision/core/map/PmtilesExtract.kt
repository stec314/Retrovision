package dev.retrovision.core.map

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Writes a PMTiles v3 archive (gzip directories, root + leaf directories when needed). */
object PmtilesWriter {
    class Tile(val tileId: Long, val data: ByteArray)

    /**
     * [entries] maps tile ids to a content key; [content] returns the bytes for a key.
     * Tiles sharing a key are stored once (oceans, empty land).
     */
    fun write(
        out: File,
        entries: List<Pair<Long, Long>>,
        content: (Long) -> ByteArray,
        type: TileType,
        tileCompression: Int,
        minZoom: Int,
        maxZoom: Int,
        bounds: DoubleArray,
        metadataJson: String = "{}",
    ) {
        val sorted = entries.sortedBy { it.first }
        val tmp = File(out.path + ".data")
        val dirEntries = ArrayList<DirEntry>(sorted.size)
        val offsetOfKey = HashMap<Long, Pair<Long, Int>>()
        tmp.outputStream().buffered(1 shl 16).use { data ->
            var pos = 0L
            for ((id, key) in sorted) {
                val placed = offsetOfKey[key] ?: run {
                    val b = content(key)
                    data.write(b)
                    val p = pos to b.size
                    pos += b.size
                    offsetOfKey[key] = p
                    p
                }
                val last = dirEntries.lastOrNull()
                if (last != null && last.offset == placed.first && last.tileId + last.runLength == id) {
                    last.runLength++
                } else {
                    dirEntries.add(DirEntry(id, placed.first, placed.second, 1))
                }
            }
        }
        val (root, leaves) = buildDirs(dirEntries)
        val meta = Bytes.gzip(metadataJson.toByteArray())
        val rootOff = PmtilesReader.HEADER_LEN.toLong()
        val metaOff = rootOff + root.size
        val leafOff = metaOff + meta.size
        val dataOff = leafOff + leaves.size
        val dataLen = tmp.length()

        val h = ByteBuffer.allocate(PmtilesReader.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray(Charsets.US_ASCII)); h.put(3)
        h.putLong(rootOff); h.putLong(root.size.toLong())
        h.putLong(metaOff); h.putLong(meta.size.toLong())
        h.putLong(leafOff); h.putLong(leaves.size.toLong())
        h.putLong(dataOff); h.putLong(dataLen)
        h.putLong(sorted.size.toLong()) // addressed tiles
        h.putLong(dirEntries.size.toLong()) // tile entries
        h.putLong(offsetOfKey.size.toLong()) // tile contents
        h.put(0) // clustered: no guarantee of data order
        h.put(2) // internal compression gzip
        h.put(tileCompression.toByte())
        h.put(when (type) { TileType.MVT -> 1; TileType.PNG -> 2; TileType.JPEG -> 3; TileType.WEBP -> 4; TileType.AVIF -> 5; else -> 0 }.toByte())
        h.put(minZoom.toByte()); h.put(maxZoom.toByte())
        h.putInt((bounds[0] * 1e7).toInt()); h.putInt((bounds[1] * 1e7).toInt())
        h.putInt((bounds[2] * 1e7).toInt()); h.putInt((bounds[3] * 1e7).toInt())
        h.put(((minZoom + maxZoom) / 2).toByte())
        h.putInt(((bounds[0] + bounds[2]) / 2 * 1e7).toInt()); h.putInt(((bounds[1] + bounds[3]) / 2 * 1e7).toInt())

        out.outputStream().buffered(1 shl 16).use { o ->
            o.write(h.array()); o.write(root); o.write(meta); o.write(leaves)
            tmp.inputStream().use { it.copyTo(o, 1 shl 16) }
        }
        tmp.delete()
    }

    internal class DirEntry(val tileId: Long, val offset: Long, val length: Int, var runLength: Int)

    private fun serialize(entries: List<DirEntry>): ByteArray {
        val b = ByteArrayOutputStream()
        fun v(x: Long) {
            var n = x
            while (n >= 0x80) { b.write(((n and 0x7f) or 0x80).toInt()); n = n ushr 7 }
            b.write(n.toInt())
        }
        v(entries.size.toLong())
        var last = 0L
        for (e in entries) { v(e.tileId - last); last = e.tileId }
        for (e in entries) v(e.runLength.toLong())
        for (e in entries) v(e.length.toLong())
        for (i in entries.indices) {
            val e = entries[i]
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) v(0) else v(e.offset + 1)
        }
        return Bytes.gzip(b.toByteArray())
    }

    private fun buildDirs(entries: List<DirEntry>): Pair<ByteArray, ByteArray> {
        val root = serialize(entries)
        if (root.size <= 16384 - PmtilesReader.HEADER_LEN) return root to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream()
            val rootEntries = ArrayList<DirEntry>()
            for (chunk in entries.chunked(leafSize)) {
                val l = serialize(chunk)
                rootEntries.add(DirEntry(chunk.first().tileId, leaves.size().toLong(), l.size, 0))
                leaves.write(l)
            }
            val r = serialize(rootEntries)
            if (r.size <= 16384 - PmtilesReader.HEADER_LEN) return r to leaves.toByteArray()
            leafSize *= 2
        }
    }
}

/**
 * Cuts the tiles of a bounding box out of a (remote) PMTiles archive, reading only the byte
 * ranges it needs, and writes them to a local PMTiles file.
 */
class PmtilesExtractor(private val src: RangeSource) {
    class Progress(val stage: String, val done: Long, val total: Long)

    class Plan(val tiles: Int, val bytes: Long)

    private val header = ByteBuffer.wrap(src.read(0, PmtilesReader.HEADER_LEN)).order(ByteOrder.LITTLE_ENDIAN).also {
        val magic = ByteArray(7); it.get(magic)
        if (String(magic, Charsets.US_ASCII) != "PMTiles" || it.get(7).toInt() != 3) throw MapFormatException("Not a PMTiles v3 archive")
    }
    private val rootOffset = header.getLong(8)
    private val rootLength = header.getLong(16).toInt()
    private val leafOffset = header.getLong(40)
    private val dataOffset = header.getLong(56)
    private val internalCompression = header.get(97).toInt()
    val tileCompression = header.get(98).toInt()
    val type: TileType = when (header.get(99).toInt()) { 1 -> TileType.MVT; 2 -> TileType.PNG; 3 -> TileType.JPEG; 4 -> TileType.WEBP; else -> TileType.UNKNOWN }
    val maxZoom = header.get(101).toInt() and 0xff
    private val minZoomSrc = header.get(100).toInt() and 0xff

    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private fun parseDir(raw: ByteArray): List<Entry> {
        val b = if (internalCompression == 2 || Bytes.isGzip(raw)) Bytes.gunzip(raw) else raw
        if (internalCompression == 3 || internalCompression == 4) throw MapFormatException("Archive uses brotli/zstd directories")
        val r = VarintReader(b)
        val n = r.varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += r.varint(); ids[i] = last }
        val runs = IntArray(n) { r.varint().toInt() }
        val lens = IntArray(n) { r.varint().toInt() }
        val offs = LongArray(n)
        for (i in 0 until n) { val v = r.varint(); offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1 }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    /** All tile ids covering [bbox] (west, south, east, north) from [minZoom] to [maxZoomWanted]. */
    fun tileIds(bbox: DoubleArray, minZoom: Int, maxZoomWanted: Int): LongArray {
        val out = ArrayList<Long>()
        for (z in maxOf(minZoom, minZoomSrc)..minOf(maxZoomWanted, maxZoom)) {
            val n = 1 shl z
            val x0 = floor(WebMercator.x(bbox[0]) * n).toInt().coerceIn(0, n - 1)
            val x1 = floor(WebMercator.x(bbox[2]) * n).toInt().coerceIn(0, n - 1)
            val y0 = floor(WebMercator.y(bbox[3]) * n).toInt().coerceIn(0, n - 1)
            val y1 = floor(WebMercator.y(bbox[1]) * n).toInt().coerceIn(0, n - 1)
            for (x in x0..x1) for (y in y0..y1) out.add(PmtilesReader.zxyToTileId(z, x, y))
        }
        return out.toLongArray().also { it.sort() }
    }

    /** Resolves sorted tile ids to (tileId, dataOffset, length), fetching only the leaf directories involved. */
    private fun resolve(ids: LongArray, progress: (Progress) -> Unit, cancelled: () -> Boolean): List<Triple<Long, Long, Int>> {
        val out = ArrayList<Triple<Long, Long, Int>>(ids.size)
        val leafCache = HashMap<Long, List<Entry>>()
        var fetchedLeaves = 0
        fun lookup(dir: List<Entry>, id: Long, depth: Int): Entry? {
            var lo = 0; var hi = dir.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (dir[mid].tileId <= id) lo = mid + 1 else hi = mid - 1
            }
            if (hi < 0) return null
            val e = dir[hi]
            if (e.runLength == 0) {
                if (depth > 3) return null
                val leaf = leafCache.getOrPut(e.offset) {
                    if (cancelled()) throw InterruptedException()
                    fetchedLeaves++
                    progress(Progress("directories", fetchedLeaves.toLong(), 0))
                    parseDir(src.read(leafOffset + e.offset, e.length))
                }
                return lookup(leaf, id, depth + 1)
            }
            return if (id - e.tileId < e.runLength) e else null
        }
        val root = parseDir(src.read(rootOffset, rootLength))
        for (id in ids) {
            val e = lookup(root, id, 0) ?: continue
            out.add(Triple(id, e.offset, e.length))
        }
        return out
    }

    fun plan(bbox: DoubleArray, minZoom: Int, maxZoomWanted: Int, progress: (Progress) -> Unit = {}, cancelled: () -> Boolean = { false }): Plan {
        val r = resolve(tileIds(bbox, minZoom, maxZoomWanted), progress, cancelled)
        return Plan(r.size, r.distinctBy { it.second }.sumOf { it.third.toLong() })
    }

    /**
     * Downloads the tiles and writes [out]. Contiguous byte ranges are fetched together
     * (gaps under [maxGap] are read and thrown away) to keep the number of requests low.
     */
    fun extract(
        out: File,
        bbox: DoubleArray,
        minZoom: Int,
        maxZoomWanted: Int,
        progress: (Progress) -> Unit = {},
        cancelled: () -> Boolean = { false },
        maxGap: Int = 256 * 1024,
        maxBatch: Int = 8 * 1024 * 1024,
    ) {
        val ids = tileIds(bbox, minZoom, maxZoomWanted)
        val resolved = resolve(ids, progress, cancelled)
        if (resolved.isEmpty()) throw MapFormatException("No tiles in this area")
        val unique = resolved.distinctBy { it.second }.sortedBy { it.second }
        val total = unique.sumOf { it.third.toLong() }

        // Tile contents go to a scratch file keyed by source offset, then into the archive.
        val scratch = File(out.path + ".tiles")
        val where = HashMap<Long, Pair<Long, Int>>(unique.size * 2)
        RandomAccessFile(scratch, "rw").use { raf ->
            var done = 0L
            var i = 0
            while (i < unique.size) {
                if (cancelled()) throw InterruptedException()
                val start = unique[i].second
                var end = start + unique[i].third
                var j = i + 1
                while (j < unique.size && unique[j].second - end <= maxGap && unique[j].second + unique[j].third - start <= maxBatch) {
                    end = maxOf(end, unique[j].second + unique[j].third); j++
                }
                val buf = src.read(dataOffset + start, (end - start).toInt())
                for (k in i until j) {
                    val t = unique[k]
                    val rel = (t.second - start).toInt()
                    where[t.second] = raf.filePointer to t.third
                    raf.write(buf, rel, t.third)
                    done += t.third
                }
                progress(Progress("tiles", done, total))
                i = j
            }
        }
        val zooms = resolved.map { tileZoom(it.first) }
        RandomAccessFile(scratch, "r").use { raf ->
            PmtilesWriter.write(
                out,
                resolved.map { it.first to it.second },
                { key ->
                    val (p, n) = where.getValue(key)
                    ByteArray(n).also { raf.seek(p); raf.readFully(it) }
                },
                type, tileCompression, zooms.min(), zooms.max(), bbox,
                """{"attribution":"© OpenStreetMap contributors","generator":"Retrovision"}""",
            )
        }
        scratch.delete()
    }

    companion object {
        fun tileZoom(id: Long): Int {
            var acc = 0L
            var z = 0
            while (true) {
                val n = 1L shl (2 * z)
                if (id < acc + n) return z
                acc += n; z++
            }
        }
    }
}
