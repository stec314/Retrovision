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
        writer = SessionLog.Writer(FileOutputStream(File(dir(ctx), name)))
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
