package dev.retrovision.app.probe

import android.content.Context
import dev.retrovision.core.flash.Chip
import org.json.JSONObject

/** One flashable image bundled in the APK by CI (assets/firmware/manifest.json). */
class FirmwareImage(
    val id: String,
    val chip: Chip,
    val offset: Int,
    val version: String,
    val data: ByteArray,
)

object FirmwareAssets {
    /** Empty when the APK was built without firmware (local builds). */
    fun load(ctx: Context): List<FirmwareImage> = try {
        val m = JSONObject(ctx.assets.open("firmware/manifest.json").bufferedReader().use { it.readText() })
        val version = m.optString("version", "unknown")
        val arr = m.getJSONArray("images")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val chip = Chip.fromId(o.getString("chip")) ?: return@mapNotNull null
            FirmwareImage(
                id = o.getString("id"),
                chip = chip,
                offset = o.optInt("offset", 0),
                version = version,
                data = ctx.assets.open("firmware/" + o.getString("file")).use { it.readBytes() },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
