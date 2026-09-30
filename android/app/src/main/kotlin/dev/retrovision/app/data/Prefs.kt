package dev.retrovision.app.data

import android.content.Context

/** User settings. Plain SharedPreferences in app-private storage. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var alertScore: Float
        get() = p.getFloat("alertScore", 0.7f)
        set(v) = p.edit().putFloat("alertScore", v).apply()

    var alertMinPlaces: Int
        get() = p.getInt("alertMinPlaces", 3)
        set(v) = p.edit().putInt("alertMinPlaces", v).apply()

    var lookbackMin: Int
        get() = p.getInt("lookbackMin", 120)
        set(v) = p.edit().putInt("lookbackMin", v).apply()

    var retentionDays: Int
        get() = p.getInt("retentionDays", 7)
        set(v) = p.edit().putInt("retentionDays", v).apply()

    /** Comma/newline separated SSIDs of your own networks: access points with these names are ignored. */
    var ownSsids: String
        get() = p.getString("ownSsids", "") ?: ""
        set(v) = p.edit().putString("ownSsids", v).apply()

    var wigleName: String
        get() = p.getString("wigleName", "") ?: ""
        set(v) = p.edit().putString("wigleName", v.trim()).apply()

    var wigleToken: String
        get() = p.getString("wigleToken", "") ?: ""
        set(v) = p.edit().putString("wigleToken", v.trim()).apply()

    var beaconDbEnabled: Boolean
        get() = p.getBoolean("beaconDb", false)
        set(v) = p.edit().putBoolean("beaconDb", v).apply()

    fun ownSsidSet(): Set<String> =
        ownSsids.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
