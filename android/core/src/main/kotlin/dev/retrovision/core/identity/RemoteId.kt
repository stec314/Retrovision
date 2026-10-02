// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * Drone Remote ID (ASTM F3411 / EU 2019/945 "direct remote identification", the OpenDroneID
 * message set). A legally mandated, unencrypted broadcast: drones over 250 g (and most new
 * consumer drones) must announce their serial, position, height and the pilot's position.
 *
 * Transports we can hear:
 *  - Bluetooth 4 legacy advertising: service data UUID 0xFFFA, app code 0x0D, counter, one message.
 *  - Wi-Fi Beacon: vendor IE OUI FA:0B:BC, type 0x0D, counter, message pack.
 * Not heard: Bluetooth 5 Long Range (Coded PHY; the probe scans 1M only) and Wi-Fi NAN action
 * frames. DJI's proprietary OcuSync "DroneID" is not Wi-Fi at all and needs an SDR.
 *
 * Message layout (25 bytes, header byte = type << 4 | version):
 *  0 Basic ID: [1] idType<<4 | uaType, [2..21] ASCII id
 *  1 Location: [1] status<<4 | heightType<<2 | ewDir<<1 | speedMult, [2] direction, [3] speed,
 *              [4] vspeed (int8 * 0.5), [5..8] lat, [9..12] lon (int32 LE * 1e-7),
 *              [13..14] baro alt, [15..16] geo alt, [17..18] height (u16 * 0.5 − 1000)
 *  3 Self ID:  [1] type, [2..24] ASCII text
 *  4 System:   [1] flags, [2..5] operator lat, [6..9] operator lon (int32 LE * 1e-7)
 *  5 Operator ID: [1] type, [2..21] ASCII
 *  F Message pack: [1] message size (25), [2] count, then count × 25 bytes
 */
object RemoteId {
    class Data(
        var uasId: String? = null,
        var uaType: Int? = null,
        var lat: Double? = null,
        var lon: Double? = null,
        /** Geodetic altitude, metres. */
        var altM: Double? = null,
        /** Height above take-off / ground, metres. */
        var heightM: Double? = null,
        var speedMps: Double? = null,
        var headingDeg: Double? = null,
        var operatorLat: Double? = null,
        var operatorLon: Double? = null,
        var selfId: String? = null,
        var operatorId: String? = null,
    )

    private const val BLE_UUID = 0xFFFA
    private const val APP_CODE = 0x0D
    private const val MSG = 25

    /** Decodes a sighting if it carries Remote ID, else null. */
    fun decode(s: Sighting): Data? {
        s.ble?.let { b ->
            val sd = AdvertisementInfo.of(b.advData).serviceData16[BLE_UUID] ?: return null
            if (sd.size < 2 + MSG || (sd[0].toInt() and 0xFF) != APP_CODE) return null
            return Data().also { message(sd, 2, it) }
        }
        val w = s.wifi ?: return null
        if (w.kind != WifiKind.BEACON && w.kind != WifiKind.PROBE_RESP) return null
        return fromIes(w.ies)
    }

    fun fromIes(ies: ByteArray): Data? {
        var i = 0
        while (i + 2 <= ies.size) {
            val id = ies[i].toInt() and 0xFF
            val len = ies[i + 1].toInt() and 0xFF
            if (i + 2 + len > ies.size) break
            if (id == 221 && len >= 5 &&
                u8(ies, i + 2) == 0xFA && u8(ies, i + 3) == 0x0B && u8(ies, i + 4) == 0xBC && u8(ies, i + 5) == APP_CODE
            ) {
                // OUI(3) + type(1) + counter(1), then the message pack
                val start = i + 2 + 5
                val end = i + 2 + len
                if (end - start >= MSG) return Data().also { message(ies.copyOfRange(start, end), 0, it) }
            }
            i += 2 + len
        }
        return null
    }

    /** Decodes one message (or a pack) at [off] into [d]. Malformed input is ignored, never thrown. */
    fun message(b: ByteArray, off: Int, d: Data) {
        if (off + MSG > b.size) return
        when (u8(b, off) ushr 4) {
            0 -> {
                d.uaType = u8(b, off + 1) and 0x0F
                ascii(b, off + 2, 20)?.let { d.uasId = it }
            }
            1 -> {
                val flags = u8(b, off + 1)
                val ew = (flags shr 1) and 1
                val mult = flags and 1
                val dir = u8(b, off + 2)
                if (dir <= 180) d.headingDeg = (dir + if (ew == 1) 180 else 0).toDouble() % 360
                val sp = u8(b, off + 3)
                if (sp != 255) d.speedMps = if (mult == 0) sp * 0.25 else sp * 0.75 + 255 * 0.25
                val lat = i32(b, off + 5) * 1e-7
                val lon = i32(b, off + 9) * 1e-7
                if ((lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0) { d.lat = lat; d.lon = lon }
                alt(u16(b, off + 15))?.let { d.altM = it }
                alt(u16(b, off + 17))?.let { d.heightM = it }
            }
            3 -> ascii(b, off + 2, 23)?.let { d.selfId = it }
            4 -> {
                val lat = i32(b, off + 2) * 1e-7
                val lon = i32(b, off + 6) * 1e-7
                if ((lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    d.operatorLat = lat; d.operatorLon = lon
                }
            }
            5 -> ascii(b, off + 2, 20)?.let { d.operatorId = it }
            0xF -> {
                val size = u8(b, off + 1)
                val n = u8(b, off + 2)
                if (size != MSG || n > 9) return
                for (k in 0 until n) {
                    val o = off + 3 + k * MSG
                    if (o + MSG > b.size) break
                    if (u8(b, o) ushr 4 != 0xF) message(b, o, d)
                }
            }
        }
    }

    /** UA type names (ASTM F3411 table). */
    fun uaTypeName(t: Int?): String = when (t) {
        1 -> "aeroplane"
        2 -> "multirotor"
        3 -> "gyroplane"
        4 -> "hybrid VTOL"
        5 -> "ornithopter"
        6 -> "glider"
        7 -> "kite"
        8 -> "free balloon"
        9 -> "captive balloon"
        10 -> "airship"
        11 -> "parachute"
        12 -> "rocket"
        13 -> "tethered aircraft"
        14 -> "ground obstacle"
        else -> "drone"
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun i32(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    private fun alt(raw: Int): Double? = if (raw == 0) null else raw * 0.5 - 1000.0
    private fun ascii(b: ByteArray, i: Int, n: Int): String? {
        val s = String(b, i, minOf(n, b.size - i), Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return s.takeIf { it.isNotEmpty() && it.all { c -> c in ' '..'~' } }
    }
}
