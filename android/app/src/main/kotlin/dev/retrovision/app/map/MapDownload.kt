package dev.retrovision.app.map

import dev.retrovision.core.map.PmtilesExtractor
import dev.retrovision.core.map.RangeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** HTTP range reads with retries. Only used during an explicit map download. */
class HttpRangeSource(private val url: String) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                c.setRequestProperty("User-Agent", "Retrovision")
                try {
                    if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} (range not supported?)")
                    val out = ByteArray(length)
                    c.inputStream.use { ins ->
                        var n = 0
                        while (n < length) {
                            val r = ins.read(out, n, length - n)
                            if (r < 0) throw IOException("short read")
                            n += r
                        }
                    }
                    return out
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw IOException("Download failed: ${last?.message}", last)
    }
}

data class DownloadUi(
    val running: Boolean = false,
    val stage: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val error: String = "",
    val finished: String = "",
)

/**
 * Downloads one area from the latest Protomaps daily build (OpenStreetMap data) into a local
 * .pmtiles file. This is the only moment the map talks to the network, and it reveals only the
 * rectangle being downloaded, once.
 */
object MapDownload {
    private val _ui = MutableStateFlow(DownloadUi())
    val ui: StateFlow<DownloadUi> = _ui
    @Volatile private var cancel = false

    fun cancel() { cancel = true }

    private fun latestBuild(): String {
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val today = LocalDate.now(ZoneOffset.UTC)
        for (d in 0..14) {
            val u = "https://build.protomaps.com/${today.minusDays(d.toLong()).format(fmt)}.pmtiles"
            val c = URL(u).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try { if (c.responseCode == 200) return u } catch (_: IOException) { } finally { c.disconnect() }
        }
        throw IOException("No Protomaps build found in the last two weeks")
    }

    /** [bbox] = west, south, east, north. */
    suspend fun download(name: String, bbox: DoubleArray, maxZoom: Int): Result<OfflineMap> = withContext(Dispatchers.IO) {
        cancel = false
        _ui.value = DownloadUi(running = true, stage = "build")
        val result = runCatching {
            val url = latestBuild()
            val ex = PmtilesExtractor(HttpRangeSource(url))
            val dir = OfflineMaps.directory()
            val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "area" }
            val tmp = File(dir, ".dl-$safe.pmtiles")
            val target = File(dir, "$safe.pmtiles")
            try {
                ex.extract(
                    tmp, bbox, 0, maxZoom,
                    progress = { p -> _ui.value = _ui.value.copy(stage = p.stage, done = p.done, total = p.total) },
                    cancelled = { cancel },
                )
                if (cancel) throw InterruptedException()
                OfflineMaps.adopt(tmp, target)
            } finally {
                tmp.delete()
                File(tmp.path + ".tiles").delete()
                File(tmp.path + ".data").delete()
            }
        }
        _ui.value = result.fold(
            onSuccess = { DownloadUi(finished = it.file.nameWithoutExtension) },
            onFailure = {
                DownloadUi(
                    error = if (it is InterruptedException) "" else (it.message ?: it.javaClass.simpleName),
                )
            },
        )
        result
    }

    /** Rough size check before downloading: how many tiles at the deepest zoom. */
    fun tileEstimate(bbox: DoubleArray, maxZoom: Int): Long {
        var total = 0L
        for (z in 0..maxZoom) {
            val n = 1 shl z
            val x0 = (dev.retrovision.core.map.WebMercator.x(bbox[0]) * n).toLong()
            val x1 = (dev.retrovision.core.map.WebMercator.x(bbox[2]) * n).toLong()
            val y0 = (dev.retrovision.core.map.WebMercator.y(bbox[3]) * n).toLong()
            val y1 = (dev.retrovision.core.map.WebMercator.y(bbox[1]) * n).toLong()
            total += (x1 - x0 + 1) * (y1 - y0 + 1)
        }
        return total
    }
}
