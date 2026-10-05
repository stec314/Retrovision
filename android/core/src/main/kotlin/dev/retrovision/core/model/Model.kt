// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.model

/** 48-bit IEEE MAC / BLE address, stored in a Long (MSB first, as written). */
@JvmInline
value class MacAddress(val bits: Long) : Comparable<MacAddress> {
    init {
        require(bits in 0..0xFFFF_FFFF_FFFFL) { "not a 48-bit address" }
    }

    fun octet(i: Int): Int = ((bits ushr (8 * (5 - i))) and 0xFF).toInt()

    /** U/L bit set: randomised (or otherwise not factory-assigned) MAC. */
    val isLocallyAdministered: Boolean get() = octet(0) and 0x02 != 0
    val isMulticast: Boolean get() = octet(0) and 0x01 != 0
    val isBroadcast: Boolean get() = bits == 0xFFFF_FFFF_FFFFL

    /** Organisationally Unique Identifier (first 3 octets), only meaningful when globally administered. */
    val oui: Int get() = (bits ushr 24).toInt()

    fun toBytes(): ByteArray = ByteArray(6) { octet(it).toByte() }

    // Hot path (analysis labels, catalogue matching): no String.format per octet.
    override fun toString(): String {
        val c = CharArray(17)
        for (i in 0 until 6) {
            val o = octet(i)
            c[i * 3] = HEX[o ushr 4]; c[i * 3 + 1] = HEX[o and 0xF]
            if (i < 5) c[i * 3 + 2] = ':'
        }
        return String(c)
    }

    override fun compareTo(other: MacAddress): Int = bits.compareTo(other.bits)

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun of(bytes: ByteArray): MacAddress {
            require(bytes.size == 6) { "address must be 6 bytes, got ${bytes.size}" }
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            return MacAddress(v)
        }

        fun parse(s: String): MacAddress {
            val hex = s.filter { it.isLetterOrDigit() }
            require(hex.length == 12) { "bad address: $s" }
            return MacAddress(hex.toLong(16))
        }
    }
}

enum class Radio { WIFI, BLE }

enum class WifiKind { PROBE_REQ, PROBE_RESP, BEACON, ASSOC_REQ, REASSOC_REQ, AUTH, DEAUTH, DISASSOC, DATA, ACTION, OTHER }

enum class BleAddressKind { PUBLIC, RANDOM_STATIC, RANDOM_RESOLVABLE, RANDOM_NON_RESOLVABLE, UNKNOWN }

/** Retrovision BleAdvType values (see retrovision.proto). */
object BleAdvType {
    const val ADV_IND = 1
    const val ADV_DIRECT_IND = 2
    const val ADV_SCAN_IND = 3
    const val ADV_NONCONN_IND = 4
    const val SCAN_RSP = 5
    const val EXTENDED = 6
}

/** A position of the phone. */
data class GeoFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float = 0f,
    val speedMps: Float? = null,
)

class WifiDetail(
    val kind: WifiKind,
    val channel: Int,
    /** Raw SSID bytes, not necessarily UTF-8. Empty = wildcard/hidden. */
    val ssid: ByteArray,
    val bssid: MacAddress?,
    /** 12-bit 802.11 sequence number. */
    val seq: Int,
    /** Raw information elements (may be empty if the probe does not forward them). */
    val ies: ByteArray,
    val iesTruncated: Boolean = false,
    /** Beacon/probe-response timestamp: the AP's uptime in µs (-1 = unknown). */
    val tsfUs: Long = -1,
) {
    val ssidText: String get() = ssidToText(ssid)
}

class BleDetail(
    val addressKind: BleAddressKind,
    val advType: Int,
    /** Raw AD structures; for a SCAN_RSP this holds the scan response data. */
    val advData: ByteArray,
    val txPowerDbm: Int = 0,
)

/** One observation of one transmitter, already mapped to host wall-clock time. */
class Sighting(
    val timeMs: Long,
    val radio: Radio,
    val address: MacAddress,
    val rssi: Int,
    val mergedCount: Int = 1,
    val wifi: WifiDetail? = null,
    val ble: BleDetail? = null,
    /** Which probe saw it (Hello.hardware_id hex), for multi-probe setups. */
    val probeId: String = "",
) {
    init {
        require((radio == Radio.WIFI) == (wifi != null) && (radio == Radio.BLE) == (ble != null)) {
            "detail does not match radio"
        }
    }
}

/**
 * Printable SSID: UTF-8 when valid and printable, otherwise hex with a marker,
 * so non-UTF-8 SSIDs never collide with or masquerade as text ones.
 */
fun ssidToText(ssid: ByteArray): String {
    if (ssid.isEmpty()) return ""
    val decoder = Charsets.UTF_8.newDecoder()
    val text = try {
        decoder.decode(java.nio.ByteBuffer.wrap(ssid)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
    return if (text != null && text.none { it.isISOControl() }) text
    else "<hex:" + ssid.joinToString("") { "%02x".format(it) } + ">"
}
