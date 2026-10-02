// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Random-access bytes: a local file, never the network. */
fun interface RangeSource {
    fun read(offset: Long, length: Int): ByteArray
}

class FileRangeSource(file: File) : RangeSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }
    override fun close() = raf.close()
}

enum class TileType { UNKNOWN, MVT, PNG, JPEG, WEBP, AVIF }

class MapInfo(
    val type: TileType,
    val minZoom: Int,
    val maxZoom: Int,
    /** west, south, east, north in degrees */
    val bounds: DoubleArray,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
)

/** Anything that returns decompressed tile bytes for z/x/y (XYZ scheme, y down). */
interface TileSource : Closeable {
    val info: MapInfo
    fun tile(z: Int, x: Int, y: Int): ByteArray?
}

class MapFormatException(msg: String) : Exception(msg)

internal object Bytes {
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
    fun isGzip(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()
    fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }
}

/**
 * PMTiles v3 reader (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 * Supports none/gzip compression; brotli and zstd archives are rejected with a clear error.
 */
class PmtilesReader(private val src: RangeSource, private val closer: Closeable? = null) : TileSource {
    private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private val rootOffset: Long
    private val rootLength: Int
    private val leafOffset: Long
    private val dataOffset: Long
    private val internalCompression: Int
    private val tileCompression: Int
    override val info: MapInfo
    private val root: List<Entry>
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 64
    }

    init {
        val h = ByteBuffer.wrap(src.read(0, HEADER_LEN)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(7).also { h.get(it) }
        if (String(magic, Charsets.US_ASCII) != "PMTiles") throw MapFormatException("Not a PMTiles file")
        val version = h.get().toInt()
        if (version != 3) throw MapFormatException("PMTiles version $version not supported (need 3)")
        rootOffset = h.getLong(8)
        rootLength = h.getLong(16).toInt()
        leafOffset = h.getLong(40)
        dataOffset = h.getLong(56)
        internalCompression = h.get(97).toInt()
        tileCompression = h.get(98).toInt()
        for (c in intArrayOf(internalCompression, tileCompression)) {
            if (c == 3 || c == 4) throw MapFormatException("PMTiles compressed with ${if (c == 3) "brotli" else "zstd"}: re-extract with gzip")
        }
        val type = when (h.get(99).toInt()) { 1 -> TileType.MVT; 2 -> TileType.PNG; 3 -> TileType.JPEG; 4 -> TileType.WEBP; 5 -> TileType.AVIF; else -> TileType.UNKNOWN }
        info = MapInfo(
            type = type,
            minZoom = h.get(100).toInt() and 0xff,
            maxZoom = h.get(101).toInt() and 0xff,
            bounds = doubleArrayOf(h.getInt(102) / 1e7, h.getInt(106) / 1e7, h.getInt(110) / 1e7, h.getInt(114) / 1e7),
            centerZoom = h.get(118).toInt() and 0xff,
            centerLon = h.getInt(119) / 1e7,
            centerLat = h.getInt(123) / 1e7,
        )
        root = parseDir(decompress(src.read(rootOffset, rootLength), internalCompression))
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || z > 26) return null
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return null
        val id = zxyToTileId(z, x, y)
        var dir = root
        repeat(4) {
            val e = find(dir, id) ?: return null
            if (e.runLength > 0) {
                val raw = src.read(dataOffset + e.offset, e.length)
                return decompress(raw, tileCompression)
            }
            val key = e.offset
            dir = synchronized(leafCache) { leafCache[key] } ?: parseDir(
                decompress(src.read(leafOffset + e.offset, e.length), internalCompression),
            ).also { synchronized(leafCache) { leafCache[key] = it } }
        }
        return null
    }

    override fun close() { closer?.close() }

    private fun find(entries: List<Entry>, id: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = entries[mid].tileId.compareTo(id)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return entries[mid]
            }
        }
        // hi = last entry with tileId < id
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e // leaf directory covers it
            if (id - e.tileId < e.runLength) return e
        }
        return null
    }

    companion object {
        const val HEADER_LEN = 127

        fun open(file: File): PmtilesReader {
            val s = FileRangeSource(file)
            return try { PmtilesReader(s, s) } catch (e: Exception) { s.close(); throw e }
        }

        private fun decompress(b: ByteArray, c: Int): ByteArray = when (c) {
            2 -> Bytes.gunzip(b)
            0 -> if (Bytes.isGzip(b)) Bytes.gunzip(b) else b
            else -> b
        }

        private fun parseDir(b: ByteArray): List<Entry> {
            val r = VarintReader(b)
            val n = r.varint().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += r.varint(); ids[i] = last }
            val runs = IntArray(n) { r.varint().toInt() }
            val lens = IntArray(n) { r.varint().toInt() }
            val offs = LongArray(n)
            for (i in 0 until n) {
                val v = r.varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
        }

        /** Hilbert-curve tile id, as defined by the PMTiles v3 spec. */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (i in 0 until z) acc += 1L shl (2 * i)
            val n = 1L shl z
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = n / 2
            while (s > 0) {
                val rx = if (tx and s > 0) 1L else 0L
                val ry = if (ty and s > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) { tx = n - 1 - tx; ty = n - 1 - ty }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }
    }
}

internal class VarintReader(private val b: ByteArray, var pos: Int = 0, private val end: Int = b.size) {
    fun hasMore() = pos < end
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw MapFormatException("Truncated varint")
            val c = b[pos++].toInt() and 0xff
            result = result or ((c and 0x7f).toLong() shl shift)
            if (c and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw MapFormatException("Varint too long")
        }
    }
}
