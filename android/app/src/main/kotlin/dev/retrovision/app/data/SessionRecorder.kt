// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.session.SessionLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records observations and your own GPS fixes to a file in app-private storage, for later replay. */
object SessionRecorder {
    /** Name of the file being written, or null. */
    val recording = MutableStateFlow<String?>(null)
    private var writer: SessionLog.Writer? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "sessions").also { it.mkdirs() }

    @Synchronized
    fun start(ctx: Context) {
        if (writer != null) return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".rvsl"
        // Encrypted at rest; if the Keystore is unavailable, don't record at all rather than in clear.
        writer = runCatching { SessionLog.Writer(SessionFiles.openWrite(ctx, File(dir(ctx), name))) }.getOrNull() ?: return
        recording.value = name
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.close() }
        writer = null
        recording.value = null
    }

    fun write(s: Sighting) {
        runCatching { writer?.write(s) }
    }

    fun write(f: GeoFix) {
        runCatching { writer?.write(f) }
    }
}
