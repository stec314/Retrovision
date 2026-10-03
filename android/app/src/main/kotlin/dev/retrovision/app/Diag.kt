// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pipeline counters shown on the Diagnostics screen. All values are cumulative since app start. */
data class DiagMetrics(
    val analysisRuns: Long = 0,
    val analysisLastMs: Long = 0,
    val analysisMaxMs: Long = 0,
    val analysisErrors: Long = 0,
    val writerBatches: Long = 0,
    val writerLastRows: Int = 0,
    val writerLastMs: Long = 0,
    val writerMaxMs: Long = 0,
    val writerErrors: Long = 0,
    /** Sightings dropped because the DB queue was full (writer slower than the probe). */
    val queueDrops: Long = 0,
    /** USB chunks dropped because the decoder was far behind. */
    val inboxDrops: Long = 0,
    val inboxBacklog: Int = 0,
    val usbErrors: Long = 0,
    val connections: Long = 0,
    /** Main-thread stalls longer than [Diag.STALL_MS]: what the user feels as a frozen app. */
    val uiStalls: Long = 0,
    val uiStallMaxMs: Long = 0,
)

/**
 * Local diagnostics: a ring buffer of events, pipeline counters, a main-thread stall detector,
 * and a plain-text report the user can copy or share. Nothing is sent anywhere by the app.
 * Messages must not contain device identifiers (MACs, SSIDs): the report is meant to be shared.
 */
object Diag {
    const val STALL_MS = 2_000L
    private const val MAX_EVENTS = 400

    class Event(val timeMs: Long, val level: Char, val tag: String, val msg: String)

    private val events = ArrayDeque<Event>()
    /** Bumped on every new event so the UI can refresh. */
    val version = MutableStateFlow(0L)
    val metrics = MutableStateFlow(DiagMetrics())

    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add('E', tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(300)}")

    private fun add(level: Char, tag: String, msg: String) {
        synchronized(events) {
            events.addLast(Event(System.currentTimeMillis(), level, tag, msg))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        version.value = version.value + 1
        if (level == 'E') android.util.Log.e("RV/$tag", msg) else if (level == 'W') android.util.Log.w("RV/$tag", msg)
    }

    inline fun update(f: (DiagMetrics) -> DiagMetrics) {
        synchronized(this) { metrics.value = f(metrics.value) }
    }

    fun events(): List<Event> = synchronized(events) { events.toList() }

    fun clear() {
        synchronized(events) { events.clear() }
        metrics.value = DiagMetrics()
        version.value = version.value + 1
    }

    // ---- main-thread stall detector ----------------------------------------------

    @Volatile private var watching = false

    /** Posts a tick to the main thread every second and measures how late it runs. */
    fun startStallWatch() {
        if (watching) return
        watching = true
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val posted = SystemClock.uptimeMillis()
                val ran = java.util.concurrent.atomic.AtomicLong(0)
                main.post { ran.set(SystemClock.uptimeMillis()) }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                var waited = 1_000L
                while (ran.get() == 0L && waited < 30_000L) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                    waited += 250
                }
                val late = (if (ran.get() == 0L) SystemClock.uptimeMillis() else ran.get()) - posted
                if (late >= STALL_MS) {
                    update { it.copy(uiStalls = it.uiStalls + 1, uiStallMaxMs = maxOf(it.uiStallMaxMs, late)) }
                    w("ui", "main thread blocked ${late} ms" + if (late >= 5_000) " (Android shows 'app not responding' after ~5 s)" else "")
                }
            }
        }, "rv-stall-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    // ---- report --------------------------------------------------------------------

    fun heapLine(): String {
        val rt = Runtime.getRuntime()
        return "heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} / ${rt.maxMemory() shr 20} MB"
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun format(e: Event) = "${fmt.format(Date(e.timeMs))} ${e.level} ${e.tag}: ${e.msg}"

    /** Plain-text report: versions, counters, recent events, last crash, the app's own warnings from logcat. */
    fun report(ctx: Context, withLogcat: Boolean): String = buildString {
        val m = metrics.value
        val c = Collector.connection.value
        val s = c.session
        val load = Collector.analysisLoad.value
        appendLine("Retrovision diagnostics · ${Date()}")
        appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(heapLine() + " · cores ${Runtime.getRuntime().availableProcessors()}")
        runCatching { ctx.getDatabasePath("retrovision.db").length() shr 20 }.getOrNull()?.let { appendLine("database $it MB") }
        appendLine()
        appendLine("[probe] link ${c.link} · phase ${s?.phase} · fw ${s?.info?.firmware} · proto ${s?.info?.protocol}")
        if (s != null) {
            appendLine("  wifi ${s.wifiObs} (5 GHz ${s.wifi5Obs}) · ble ${s.bleObs} · seq gaps ${s.lostFrames} · probe queue drops ${s.probeDropped} · crc ${s.badFrames} · no clock ${s.droppedNoClock}")
            appendLine("  probe heap ${s.freeHeap / 1024} KiB · ${"%.0f".format(s.chipTempC)} °C · clock ±${s.clockUncertaintyUs} µs")
            if (s.lastLog.isNotEmpty()) appendLine("  last probe log: ${s.lastLog}")
        }
        appendLine("[usb] connections ${m.connections} · errors ${m.usbErrors} · inbox backlog ${m.inboxBacklog} · inbox drops ${m.inboxDrops}")
        appendLine("[db] batches ${m.writerBatches} · last ${m.writerLastRows} rows in ${m.writerLastMs} ms · max ${m.writerMaxMs} ms · queue drops ${m.queueDrops} · errors ${m.writerErrors}")
        appendLine("[analysis] runs ${m.analysisRuns} · last ${m.analysisLastMs} ms · max ${m.analysisMaxMs} ms · errors ${m.analysisErrors}")
        appendLine("  window ${load.rawRows} raw rows → ${load.analysedRows} analysed · bucket ${load.bucketMs / 1000} s · truncated ${load.truncated}")
        appendLine("[ui] stalls ≥${STALL_MS / 1000} s: ${m.uiStalls} · longest ${m.uiStallMaxMs} ms")
        appendLine()
        appendLine("[events] newest last")
        events().forEach { appendLine(format(it)) }
        CrashLog.read(ctx)?.let {
            appendLine()
            appendLine("[last crash]")
            appendLine(it)
        }
        if (withLogcat) {
            appendLine()
            appendLine("[logcat: this app only, warnings and errors]")
            appendLine(
                runCatching {
                    val p = ProcessBuilder("logcat", "-d", "-t", "300", "-v", "time", "*:W").redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().use { it.readText() }.takeLast(40_000)
                }.getOrElse { "unavailable: ${it.message}" },
            )
        }
    }
}
