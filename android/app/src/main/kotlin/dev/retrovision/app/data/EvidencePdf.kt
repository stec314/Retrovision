// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A device's evidence file: what it is, why it was flagged, every stretch it was heard with where
 * you were, a sketch of those positions, and a SHA-256 of the data table. Meant for a report to the
 * police or a lawyer: plain, dated, complete. The hash proves the table was not changed after the
 * file was made only if the hash (or the file) is also kept somewhere else, e.g. emailed to yourself.
 */
object EvidencePdf {
    class Stretch(val fromMs: Long, val toMs: Long, val n: Int, val maxRssi: Int, val lat: Double?, val lon: Double?)

    class Input(
        val title: String,
        val ids: List<String>,
        val facts: List<String>,
        val reasons: List<String>,
        val stretches: List<Stretch>,
        /** Signal and your movement over the last hours it was heard (two charts, one time axis). */
        val signal: ChartsData.Signal? = null,
    )

    private const val W = 595
    private const val H = 842
    private const val M = 40f

    /** The data table as hashed: one line per stretch, UTC ISO times, fixed number formats. */
    fun table(input: Input): String {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return buildString {
            append("ids=").append(input.ids.sorted().joinToString(",")).append('\n')
            for (s in input.stretches.sortedBy { it.fromMs }) {
                append(iso.format(Date(s.fromMs))).append(';').append(iso.format(Date(s.toMs))).append(';')
                append(s.n).append(';').append(s.maxRssi).append(';')
                append(s.lat?.let { String.format(Locale.ROOT, "%.5f", it) } ?: "").append(';')
                append(s.lon?.let { String.format(Locale.ROOT, "%.5f", it) } ?: "").append('\n')
            }
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun write(ctx: Context, uri: Uri, input: Input, appVersion: String) {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val head = Paint().apply { textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { textSize = 9f; isAntiAlias = true }
        val mono = Paint().apply { textSize = 7.5f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        val table = table(input)
        val hash = sha256(table)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
            page!!.canvas.drawText("Retrovision · " + input.title.take(70) + " · p. $pageNo", M, H - 20f, body)
        }
        fun need(h: Float) { if (page == null || y + h > H - M) newPage() }
        fun line(text: String, p: Paint, gap: Float = 3f) {
            // Wrap to the page width.
            val words = text.split(' ')
            var cur = ""
            for (w in words) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(t) > W - 2 * M && cur.isNotEmpty()) {
                    need(p.textSize + gap); page!!.canvas.drawText(cur, M, y + p.textSize, p); y += p.textSize + gap; cur = w
                } else cur = t
            }
            need(p.textSize + gap); page!!.canvas.drawText(cur, M, y + p.textSize, p); y += p.textSize + gap
        }

        newPage()
        line("Device evidence file", title, 8f)
        line(input.title, head, 6f)
        line("Made " + fmt.format(Date()) + " with Retrovision $appVersion. Times are the phone's local time; the data table hash below uses UTC.", body)
        y += 6f
        line("What it is", head, 4f)
        input.facts.forEach { line(it, body) }
        line("Identifiers: " + input.ids.joinToString(", "), body)
        if (input.reasons.isNotEmpty()) {
            y += 6f
            line("Why it was flagged (analysis at the time of export)", head, 4f)
            input.reasons.forEach { line("• $it", body) }
        }

        // Sketch: positions where it was heard, in time order, north up, equal scale.
        val pts = input.stretches.filter { it.lat != null }.sortedBy { it.fromMs }
        if (pts.size >= 2) {
            y += 6f
            line("Where you were when it was heard (${pts.size} points, north up)", head, 4f)
            val box = 220f
            need(box + 10f)
            val c = page!!.canvas
            val lat0 = pts.minOf { it.lat!! }; val lat1 = pts.maxOf { it.lat!! }
            val lon0 = pts.minOf { it.lon!! }; val lon1 = pts.maxOf { it.lon!! }
            val cos = Math.cos(Math.toRadians((lat0 + lat1) / 2))
            val spanM = maxOf((lat1 - lat0) * 111_320.0, (lon1 - lon0) * 111_320.0 * cos, 50.0)
            val scale = (box - 20) / spanM
            fun px(s: Stretch) = M + 10 + ((s.lon!! - lon0) * 111_320.0 * cos * scale).toFloat()
            fun py(s: Stretch) = y + box - 10 - ((s.lat!! - lat0) * 111_320.0 * scale).toFloat()
            val frame = Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.5f }
            c.drawRect(M, y, M + box, y + box, frame)
            val path = Paint().apply { color = Color.rgb(120, 120, 200); strokeWidth = 0.8f; isAntiAlias = true }
            for (i in 1 until pts.size) c.drawLine(px(pts[i - 1]), py(pts[i - 1]), px(pts[i]), py(pts[i]), path)
            val dot = Paint().apply { color = Color.rgb(200, 40, 60); isAntiAlias = true }
            pts.forEach { c.drawCircle(px(it), py(it), 2.2f, dot) }
            c.drawText("%.0f m".format(spanM), M + box + 8, y + box, body)
            y += box + 8
        }

        val sg = input.signal
        if (sg != null && sg.rssi.isNotEmpty()) {
            y += 6f
            line("Its signal and your movement, last hours before its last sighting (same time axis)", head, 4f)
            val w = W - 2 * M
            val tf0 = DateFormat.getTimeInstance(DateFormat.SHORT)
            fun xs(t: Long) = M + ((t - sg.fromMs).toFloat() / (sg.toMs - sg.fromMs).coerceAtLeast(1)) * w
            val grid = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.4f }
            val ink = Paint().apply { color = Color.rgb(60, 60, 140); isAntiAlias = true; strokeWidth = 1.2f }
            // Signal, -100 (bottom) to -30 dBm (top).
            need(110f)
            var c = page!!.canvas
            val h1 = 90f
            for (v in listOf(-30, -65, -100)) { val yy = y + h1 * (-30 - v) / 70f; c.drawLine(M, yy, M + w, yy, grid); c.drawText("$v dBm", M + w - 40, yy - 2, mono) }
            for ((t, r) in sg.rssi) c.drawCircle(xs(t), y + h1 * (-30 - r.coerceIn(-100, -30)) / 70f, 1.4f, ink)
            y += h1 + 8f
            // Distance travelled since the start of the span.
            need(80f)
            c = page!!.canvas
            val h2 = 60f
            val maxM = sg.travelled.maxOfOrNull { it.second }?.coerceAtLeast(100.0) ?: 100.0
            c.drawLine(M, y + h2, M + w, y + h2, grid)
            var px = -1f; var py = -1f
            for ((t, m) in sg.travelled) {
                val xx = xs(t); val yy = y + h2 - (m / maxM).toFloat() * h2
                if (px >= 0) c.drawLine(px, py, xx, yy, ink)
                px = xx; py = yy
            }
            c.drawText("distance you moved, max " + (if (maxM >= 1000) "%.1f km".format(maxM / 1000) else "${maxM.toInt()} m"), M, y + 8, mono)
            y += h2 + 4f
            line(tf0.format(Date(sg.fromMs)) + " → " + tf0.format(Date(sg.toMs)) + ". Steady signal while the distance climbs: it moved with you.", body)
        }

        y += 6f
        line("Every stretch it was heard (gaps over 10 minutes split them)", head, 4f)
        line("from · to · frames · max dBm · your position", mono)
        val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (s in input.stretches.sortedBy { it.fromMs }) {
            line(
                fmt.format(Date(s.fromMs)) + " · " + tf.format(Date(s.toMs)) + " · ${s.n} · ${s.maxRssi} · " +
                    (s.lat?.let { String.format(Locale.ROOT, "%.5f, %.5f", it, s.lon) } ?: "no GPS fix"),
                mono, 2f,
            )
        }
        y += 8f
        line("Integrity", head, 4f)
        line("SHA-256 of the data table (identifiers + every stretch in UTC): $hash", mono)
        line(
            "Keep a copy of this file or of the hash outside the phone (email it to yourself): that dates it. " +
                "Signal strength is not distance; a match on a name or vendor is not identity. What the file shows is when and where your receivers heard these identifiers.",
            body,
        )
        page?.let { doc.finishPage(it) }
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
    }
}
