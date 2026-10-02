package dev.retrovision.app.data

import android.content.Context

/** User settings. Plain SharedPreferences in app-private storage. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** GPS fixes less accurate than this (metres) are ignored by the analysis (indoor/car drift). */
    /** Phone Bluetooth as a receiver: 0 off, 1 only while no probe is streaming, 2 always. */
    var phoneBleMode: Int
        get() = p.getInt("phoneBleMode", 2)
        set(v) = p.edit().putInt("phoneBleMode", v).apply()

    /** Reject GPS drift while the accelerometer says the phone is still. */
    var driftGuard: Boolean
        get() = p.getBoolean("driftGuard", true)
        set(v) = p.edit().putBoolean("driftGuard", v).apply()

    /** "ssid|bssid" pairs your phone has joined for your own networks (trust on first use). */
    var trustedAps: Set<String>
        get() = p.getStringSet("trustedAps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("trustedAps", v).apply()

    /** Probe-request fingerprints of this phone (from "identify my phone"). */
    var ownFingerprints: Set<String>
        get() = p.getStringSet("ownFingerprints", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("ownFingerprints", v).apply()

    /** Field test: "entityId|firstAlertMs" for targets that crossed the alert threshold. */
    var testFirstAlerts: Set<String>
        get() = p.getStringSet("testFirstAlerts", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("testFirstAlerts", v).apply()

    /** Notify when a drone is heard nearby. */
    var droneAlerts: Boolean
        get() = p.getBoolean("droneAlerts", true)
        set(v) = p.edit().putBoolean("droneAlerts", v).apply()

    var maxFixAccuracyM: Int
        get() = p.getInt("maxFixAccuracyM", 50)
        set(v) = p.edit().putInt("maxFixAccuracyM", v).apply()

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

    /** Keep the probe's status LED lit. Off = the probe runs dark. */
    var probeLedOn: Boolean
        get() = p.getBoolean("probeLedOn", true)
        set(v) = p.edit().putBoolean("probeLedOn", v).apply()

    // ---- Alert notifications -------------------------------------------------
    var alertsEnabled: Boolean
        get() = p.getBoolean("alertsEnabled", true)
        set(v) = p.edit().putBoolean("alertsEnabled", v).apply()

    /** Minutes to wait before notifying the same device again. */
    var alertCooldownMin: Int
        get() = p.getInt("alertCooldownMin", 30)
        set(v) = p.edit().putInt("alertCooldownMin", v).apply()

    /** Notify a device at most once per collection session (overrides the cooldown). */
    var alertOncePerDevice: Boolean
        get() = p.getBoolean("alertOncePerDevice", false)
        set(v) = p.edit().putBoolean("alertOncePerDevice", v).apply()

    /** After the first alert, only notify the same device again if its score went up. */
    var alertOnlyIfScoreRises: Boolean
        get() = p.getBoolean("alertRises", true)
        set(v) = p.edit().putBoolean("alertRises", v).apply()

    /** Post alerts silently (no sound/vibration/heads-up). */
    var alertSilent: Boolean
        get() = p.getBoolean("alertSilent", false)
        set(v) = p.edit().putBoolean("alertSilent", v).apply()

    var quietHoursEnabled: Boolean
        get() = p.getBoolean("quietOn", false)
        set(v) = p.edit().putBoolean("quietOn", v).apply()

    /** Local hour [0..23] when quiet hours start. */
    var quietStartHour: Int
        get() = p.getInt("quietStart", 22)
        set(v) = p.edit().putInt("quietStart", v).apply()

    /** Local hour [0..23] when quiet hours end. */
    var quietEndHour: Int
        get() = p.getInt("quietEnd", 7)
        set(v) = p.edit().putInt("quietEnd", v).apply()

    /** Notify if the probe was streaming and then disconnects. */
    var probeDisconnectAlert: Boolean
        get() = p.getBoolean("probeDisconnectAlert", true)
        set(v) = p.edit().putBoolean("probeDisconnectAlert", v).apply()

    /** Suppress following-alerts while you are at a confirmed routine place (home, work). */
    var alertsOnlyAwayFromFamiliar: Boolean
        get() = p.getBoolean("alertsAwayOnly", false)
        set(v) = p.edit().putBoolean("alertsAwayOnly", v).apply()

    /** Capture Wi-Fi DATA frames to see connected (silent) clients. Invasive; off by default. */
    var captureDataFrames: Boolean
        get() = p.getBoolean("captureDataFrames", false)
        set(v) = p.edit().putBoolean("captureDataFrames", v).apply()

    /** Entities marked as field-test targets (a device you carry on purpose). */
    var targets: Set<String>
        get() = p.getStringSet("targets", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("targets", v).apply()

    fun ownSsidSet(): Set<String> =
        ownSsids.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
