// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PmtilesExtractTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource("map/$name")!!.toURI())

    @Test fun tileZoomInverse() {
        for (z in 0..14) assertEquals(z, PmtilesExtractor.tileZoom(PmtilesReader.zxyToTileId(z, (1 shl z) - 1, 0)))
    }

    @Test fun extractsBboxThroughLeafDirectoriesWithFewRequests() {
        val src = FileRangeSource(res("leafy.pmtiles"))
        var requests = 0
        val counting = RangeSource { o, l -> requests++; src.read(o, l) }
        val ex = PmtilesExtractor(counting)
        val bbox = doubleArrayOf(10.0, 44.0, 12.0, 45.0) // around Bologna
        val out = Files.createTempFile("ex", ".pmtiles").toFile()
        ex.extract(out, bbox, 0, 7)
        PmtilesReader.open(out).use { r ->
            assertEquals(0, r.info.minZoom)
            assertEquals(7, r.info.maxZoom)
            var n = 0
            for (z in 0..7) {
                val k = 1 shl z
                val x0 = (WebMercator.x(10.0) * k).toInt(); val x1 = (WebMercator.x(12.0) * k).toInt()
                val y0 = (WebMercator.y(45.0) * k).toInt(); val y1 = (WebMercator.y(44.0) * k).toInt()
                for (x in x0..x1) for (y in y0..y1) { assertEquals("$z/$x/$y", String(r.tile(z, x, y)!!)); n++ }
            }
            assertTrue(n > 8)
            assertNull(r.tile(7, 0, 0)) // outside the box
        }
        // header + root + a few leaves + one or two data batches, not one request per tile
        assertTrue("requests=$requests", requests < 30)
        out.delete(); src.close()
    }

    @Test fun writerSharesDuplicateContentAndBuildsLeaves() {
        val out = Files.createTempFile("w", ".pmtiles").toFile()
        // 30k tiles at z8 (above the root limit) where every 3rd shares content
        val ids = (0 until 30_000).map { PmtilesReader.zxyToTileId(8, it % 256, it / 256) }
        PmtilesWriter.write(out, ids.mapIndexed { i, id -> id to (i % 3).toLong() + (if (i % 3 == 0) 0 else i.toLong() * 10) },
            { k -> "c$k".toByteArray() }, TileType.MVT, 1, 8, 8, doubleArrayOf(-180.0, -85.0, 180.0, 85.0))
        PmtilesReader.open(out).use { r ->
            for (i in listOf(0, 1, 2, 3, 299, 29_999)) {
                val k = (i % 3).toLong() + (if (i % 3 == 0) 0 else i.toLong() * 10)
                assertEquals("c$k", String(r.tile(8, i % 256, i / 256)!!))
            }
        }
        out.delete()
    }
}
