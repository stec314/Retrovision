// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context

/** User settings. Plain SharedPreferences in app-private storage. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- sensitive settings: encrypted with a Keystore key (network names, tokens, access points) ----
    private val secretCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun secret(key: String): String {
        secretCache[key]?.let { return it }
        val sealed = p.getString("enc_$key", null)
        val v = if (sealed != null) {
            KeyVault.open(sealed) ?: ""
        } else {
            // Migrate a value stored in clear by an older version.
            val plain = p.getString(key, null)
            if (plain != null) {
                p.edit().remove(key).apply()
                // Keep the old value even if the Keystore fails right now (it is re-sealed on next write).
                KeyVault.seal(plain)?.let { p.edit().putString("enc_$key", it).apply() }
                    ?: p.edit().putString(key, plain).apply()
            }
            plain ?: ""
        }
        secretCache[key] = v
        return v
    }

    private fun setSecret(key: String, v: String) {
        secretCache[key] = v
        val sealed = KeyVault.seal(v)
        if (sealed != null) p.edit().putString("enc_$key", sealed).remove(key).apply()
        else p.edit().putString(key, v).apply() // Keystore unavailable: better than losing the setting
    }

    private fun secretSet(key: String): Set<String> {
        // Sets stored in clear by older versions live under the same key as a StringSet.
        if (p.getString("enc_$key", null) == null && p.contains(key)) {
            val old = runCatching { p.getStringSet(key, emptySet()) }.getOrNull() ?: emptySet()
            p.edit().remove(key).apply()
            setSecret(key, old.joinToString(SEP))
        }
        return secret(key).split(SEP).filter { it.isNotEmpty() }.toSet()
    }

    private fun setSecretSet(key: String, v: Set<String>) = setSecret(key, v.joinToString(SEP))

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
        get() = secretSet("trustedAps")
        set(v) = setSecretSet("trustedAps", v)

    /** Probe-request fingerprints of this phone (from "identify my phone"). */
    var ownFingerprints: Set<String>
        get() = secretSet("ownFingerprints")
        set(v) = setSecretSet("ownFingerprints", v)

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

    /** Devices kept with a full report after each analysis (the rest stay searchable as stubs). */
    var maxReports: Int
        get() = p.getInt("maxReports", 5000)
        set(v) = p.edit().putInt("maxReports", v).apply()

    /** Rows shown in the Devices list before "search or filter". */
    var devicesShown: Int
        get() = p.getInt("devicesShown", 300)
        set(v) = p.edit().putInt("devicesShown", v).apply()

    /** BLE wireless link: the probe's name and the pairing key (16..32 bytes), stored encrypted. */
    /** A probe paired for the wireless link: its advertised name ("RV-<name>") and the pairing key. */
    class BlePair(val name: String, val key: ByteArray)

    /** Paired wireless probes, kept Keystore-sealed as "name:base64key" lines. */
    fun blePairs(): List<BlePair> {
        // One pairing stored by r104-r109 (before multi-probe): carry it over once.
        val oldName = p.getString("blePairName", "") ?: ""
        if (oldName.isNotEmpty()) {
            val oldKey = secret("blePairKey")
            p.edit().remove("blePairName").apply()
            setSecret("blePairKey", "")
            if (oldKey.isNotEmpty()) setSecret("blePairs", (secret("blePairs").split('\n').filter { it.isNotBlank() } + "$oldName:$oldKey").joinToString("\n"))
        }
        return secret("blePairs").split('\n').mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val key = runCatching { android.util.Base64.decode(line.substring(i + 1), android.util.Base64.NO_WRAP) }.getOrNull() ?: return@mapNotNull null
            BlePair(line.substring(0, i), key)
        }
    }

    fun blePairKey(name: String): ByteArray? = blePairs().firstOrNull { it.name == name }?.key

    /** Adds or replaces the pairing for [name]. */
    fun putBlePair(name: String, key: ByteArray) {
        val rest = blePairs().filter { it.name != name }
        val all = rest + BlePair(name, key)
        setSecret("blePairs", all.joinToString("\n") { "${it.name}:" + android.util.Base64.encodeToString(it.key, android.util.Base64.NO_WRAP) })
    }

    fun removeBlePair(name: String) {
        val all = blePairs().filter { it.name != name }
        setSecret("blePairs", all.joinToString("\n") { "${it.name}:" + android.util.Base64.encodeToString(it.key, android.util.Base64.NO_WRAP) })
    }

    /** Wireless probes paused from Status (by pairing name): not connected until resumed. */
    var blePaused: Set<String>
        get() = p.getStringSet("blePaused", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("blePaused", v).apply()

    /** Split the Wi-Fi channels between probes when several stream at once. */
    var splitChannels: Boolean
        get() = p.getBoolean("splitChannels", true)
        set(v) = p.edit().putBoolean("splitChannels", v).apply()

    // ---- automatic backup to a folder outside the app (survives an uninstall) ----
    /** Folder picked by the user (SAF tree URI), "" = automatic backup off. */
    var autoBackupTree: String
        get() = p.getString("autoBackupTree", "") ?: ""
        set(v) = p.edit().putString("autoBackupTree", v).apply()

    /** Password the automatic backups are sealed with (kept Keystore-sealed here). */
    var autoBackupPassword: String
        get() = secret("autoBackupPassword")
        set(v) = setSecret("autoBackupPassword", v)

    /** Days between automatic backups (1 or 7). */
    var autoBackupDays: Int
        get() = p.getInt("autoBackupDays", 1)
        set(v) = p.edit().putInt("autoBackupDays", v).apply()

    var autoBackupMaps: Boolean
        get() = p.getBoolean("autoBackupMaps", false)
        set(v) = p.edit().putBoolean("autoBackupMaps", v).apply()

    var lastAutoBackupMs: Long
        get() = p.getLong("lastAutoBackupMs", 0L)
        set(v) = p.edit().putLong("lastAutoBackupMs", v).apply()

    var lastAutoBackupError: String
        get() = p.getString("lastAutoBackupError", "") ?: ""
        set(v) = p.edit().putString("lastAutoBackupError", v).apply()

    /** Wi-Fi channel plan sent to the probe: 0 focused (default), 1 balanced, 2 full sweep. */
    var channelPlan: Int
        get() = p.getInt("channelPlan", 0)
        set(v) = p.edit().putInt("channelPlan", v).apply()

    /** True once the inflated day counts from before the day-count fix were reset (one time). */
    var dayCountFixed: Boolean
        get() = p.getBoolean("dayCountFixed", false)
        set(v) = p.edit().putBoolean("dayCountFixed", v).apply()

    /** Local day the routine-place baseline started (0 = not yet). */
    var baselineStartDay: Long
        get() = p.getLong("baselineStartDay", 0L)
        set(v) = p.edit().putLong("baselineStartDay", v).apply()

    /** Devices list as one-line rows instead of cards. */
    var devicesCompact: Boolean
        get() = p.getBoolean("devicesCompact", false)
        set(v) = p.edit().putBoolean("devicesCompact", v).apply()

    /** Map: show where alerting devices were heard (only places on your own track). */
    var mapAlertDevices: Boolean
        get() = p.getBoolean("mapAlertDevices", true)
        set(v) = p.edit().putBoolean("mapAlertDevices", v).apply()

    var retentionDays: Int
        get() = p.getInt("retentionDays", 7)
        set(v) = p.edit().putInt("retentionDays", v).apply()

    /** Dashboard widget order (ids, comma separated) and hidden widgets. */
    var dashboardOrder: String
        get() = p.getString("dashOrder", "") ?: ""
        set(v) = p.edit().putString("dashOrder", v).apply()

    var dashboardHidden: Set<String>
        get() = p.getStringSet("dashHidden", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("dashHidden", v).apply()

    /** Comma/newline separated SSIDs of your own networks: access points with these names are ignored. */
    var ownSsids: String
        get() = secret("ownSsids")
        set(v) = setSecret("ownSsids", v)

    var wigleName: String
        get() = secret("wigleName")
        set(v) = setSecret("wigleName", v.trim())

    var wigleToken: String
        get() = secret("wigleToken")
        set(v) = setSecret("wigleToken", v.trim())

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
    /** Notifications say only "Something to check", even when the phone is unlocked. */
    var discreetAlerts: Boolean
        get() = p.getBoolean("discreetAlerts", false)
        set(v) = p.edit().putBoolean("discreetAlerts", v).apply()

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

    private companion object {
        const val SEP = "\u0000"
    }
}
