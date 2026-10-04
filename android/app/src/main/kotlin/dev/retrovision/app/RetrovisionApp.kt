// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app

import android.app.Application
import dev.retrovision.app.data.Db
import dev.retrovision.app.data.Prefs

class RetrovisionApp : Application() {
    val db: Db by lazy { Db.open(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        // A restore confirmed in the previous run is applied before anything opens the database.
        dev.retrovision.app.data.Backup.applyPending(this)
        Diag.i("app", "started · ${BuildConfig.VERSION_NAME} · ${Diag.heapLine()}")
        Diag.startStallWatch()
        // Map renderer: offline only. Marked disconnected so it never tries the network on its own.
        runCatching {
            org.maplibre.android.MapLibre.getInstance(this)
            org.maplibre.android.MapLibre.setConnected(false)
        }.onFailure { Diag.w("map", "renderer init failed: ${it.message}") }
        Thread { dev.retrovision.app.enrich.Vendors.load(this) }.start()
        Thread { dev.retrovision.app.map.OfflineMaps.init(this) }.start()
        // Automatic backup, if due (also checked hourly while collecting).
        Thread({ Thread.sleep(20_000); runCatching { dev.retrovision.app.data.Backup.runIfDue(this) } }, "rv-autobackup").apply { isDaemon = true }.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Running-low levels while in use are the warning before an out-of-memory kill.
        if (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN) Diag.w("memory", "system memory low (level $level) · ${Diag.heapLine()}")
        if (level >= TRIM_MEMORY_COMPLETE) Diag.w("memory", "app likely to be killed soon (level $level) · ${Diag.heapLine()}")
    }

    companion object {
        lateinit var instance: RetrovisionApp
            private set
    }
}

/**
 * Keeps the last uncaught exception in app-private storage so it can be shown (and copied) on the
 * next start. Stays on the phone: nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(ctx: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val file = java.io.File(ctx.filesDir, FILE)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val rt = Runtime.getRuntime()
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                file.writeText(
                    "time ${java.util.Date()}\n" +
                        "thread ${t.name}\n" +
                        "heap used ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB / max ${rt.maxMemory() shr 20} MB\n" +
                        "version ${BuildConfig.VERSION_NAME}\n\n" + sw.toString().take(16_000) +
                        "\n\nlast events:\n" + Diag.events().takeLast(60).joinToString("\n") { Diag.format(it) },
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(ctx: android.content.Context): String? =
        runCatching { java.io.File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: android.content.Context) {
        runCatching { java.io.File(ctx.filesDir, FILE).delete() }
    }
}
