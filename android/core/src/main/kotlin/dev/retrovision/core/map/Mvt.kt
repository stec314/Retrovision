// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.map

/**
 * Minimal Mapbox Vector Tile (MVT 2.1) decoder: layers, features, tags, geometry.
 * Geometry is returned in tile coordinates (0..extent), as a list of rings/lines/points.
 */
object Mvt {
    enum class GeomType { UNKNOWN, POINT, LINESTRING, POLYGON }

    class Feature(
        val type: GeomType,
        val props: Map<String, Any>,
        /** Each part is a flat [x0,y0,x1,y1,...] array in tile units. */
        val parts: List<FloatArray>,
    ) {
        fun str(k: String) = props[k] as? String
        fun num(k: String) = (props[k] as? Number)?.toDouble()
    }

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    fun decode(bytes: ByteArray): List<Layer> {
        val data = if (Bytes.isGzip(bytes)) Bytes.gunzip(bytes) else bytes
        val r = Pb(data, 0, data.size)
        val out = ArrayList<Layer>()
        while (r.hasMore()) {
            val (field, wire) = r.key()
            if (field == 3 && wire == 2) out.add(layer(r.sub())) else r.skip(wire)
        }
        return out
    }

    private class RawFeature(val type: Int, val tags: IntArray, val geom: IntArray)

    private fun layer(r: Pb): Layer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raw = ArrayList<RawFeature>()
        while (r.hasMore()) {
            val (f, w) = r.key()
            when {
                f == 1 && w == 2 -> name = r.string()
                f == 2 && w == 2 -> raw.add(feature(r.sub()))
                f == 3 && w == 2 -> keys.add(r.string())
                f == 4 && w == 2 -> values.add(value(r.sub()))
                f == 5 && w == 0 -> extent = r.varint().toInt()
                else -> r.skip(w)
            }
        }
        val features = raw.map { rf ->
            val props = HashMap<String, Any>(rf.tags.size / 2)
            var i = 0
            while (i + 1 < rf.tags.size) {
                val k = keys.getOrNull(rf.tags[i]); val v = values.getOrNull(rf.tags[i + 1])
                if (k != null && v != null) props[k] = v
                i += 2
            }
            val type = GeomType.entries.getOrElse(rf.type) { GeomType.UNKNOWN }
            Feature(type, props, geometry(rf.geom, type))
        }
        return Layer(name, extent, features)
    }

    private fun feature(r: Pb): RawFeature {
        var type = 0
        var tags = IntArray(0)
        var geom = IntArray(0)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when {
                f == 2 && w == 2 -> tags = r.packed()
                f == 3 && w == 0 -> type = r.varint().toInt()
                f == 4 && w == 2 -> geom = r.packed()
                else -> r.skip(w)
            }
        }
        return RawFeature(type, tags, geom)
    }

    private fun value(r: Pb): Any {
        var v: Any = ""
        while (r.hasMore()) {
            val (f, w) = r.key()
            v = when (f) {
                1 -> r.string()
                2 -> java.lang.Float.intBitsToFloat(r.fixed32())
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                4 -> r.varint()
                5 -> r.varint()
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                7 -> r.varint() != 0L
                else -> { r.skip(w); v }
            }
        }
        return v
    }

    private fun zz(v: Int) = (v ushr 1) xor -(v and 1)

    internal fun geometry(g: IntArray, type: GeomType): List<FloatArray> {
        val parts = ArrayList<FloatArray>()
        var cur = FloatArrayBuilder()
        var x = 0
        var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7
            val count = g[i] ushr 3
            i++
            when (cmd) {
                1 -> repeat(count) {
                    if (i + 1 >= g.size) return parts
                    x += zz(g[i]); y += zz(g[i + 1]); i += 2
                    if (type == GeomType.POINT) {
                        parts.add(floatArrayOf(x.toFloat(), y.toFloat()))
                    } else {
                        if (cur.size > 0) parts.add(cur.build())
                        cur = FloatArrayBuilder()
                        cur.add(x.toFloat(), y.toFloat())
                    }
                }
                2 -> repeat(count) {
                    if (i + 1 >= g.size) return parts
                    x += zz(g[i]); y += zz(g[i + 1]); i += 2
                    cur.add(x.toFloat(), y.toFloat())
                }
                7 -> if (cur.size >= 2) cur.add(cur[0], cur[1])
                else -> return parts
            }
        }
        if (cur.size > 0) parts.add(cur.build())
        return parts
    }

    internal class FloatArrayBuilder {
        private var a = FloatArray(16)
        var size = 0; private set
        operator fun get(i: Int) = a[i]
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }
        fun build() = a.copyOf(size)
    }

    /** Tiny protobuf reader over a byte range. */
    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw MapFormatException("Truncated MVT")
                val c = b[pos++].toInt() and 0xff
                result = result or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }
        fun key(): Pair<Int, Int> { val k = varint(); return (k ushr 3).toInt() to (k and 7).toInt() }
        fun len(): Int { val l = varint().toInt(); if (l < 0 || pos + l > end) throw MapFormatException("Bad MVT length"); return l }
        fun sub(): Pb { val l = len(); val p = Pb(b, pos, pos + l); pos += l; return p }
        fun string(): String { val l = len(); val s = String(b, pos, l, Charsets.UTF_8); pos += l; return s }
        fun fixed32(): Int { var v = 0; for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xff) shl (8 * i)); pos += 4; return v }
        fun fixed64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8; return v }
        fun packed(): IntArray {
            val l = len()
            val stop = pos + l
            val out = ArrayList<Int>(l)
            while (pos < stop) out.add(varint().toInt())
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += len()
                5 -> pos += 4
                else -> throw MapFormatException("Unsupported wire type $wire")
            }
        }
    }
}
