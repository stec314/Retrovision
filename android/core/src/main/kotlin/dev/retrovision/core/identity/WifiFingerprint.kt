// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

/**
 * Device fingerprint from the Information Elements of 802.11 probe requests.
 *
 * MAC randomisation changes the address but a given device model/OS/driver
 * keeps sending the same capability elements in the same order (Vanhoef et al.,
 * "Why MAC Address Randomization is not Enough", AsiaCCS 2016). We hash the
 * elements that describe the *device*, and skip the ones that describe the
 * *request* (SSID, channel) or are per-frame random (WPS UUID-E).
 *
 * IMPORTANT: the fingerprint is not unique. Every phone of the same model and
 * OS version shares it. It is only used to *link* MAC rotations together with
 * other evidence (time adjacency, sequence-number continuity), never as an
 * identity on its own. See [EntityResolver].
 */
object WifiFingerprint {
    private const val EID_SSID = 0
    private const val EID_DS_PARAMS = 3
    private const val EID_REQUEST = 10
    private const val EID_VENDOR = 221

    // Microsoft WPS vendor element: contains UUID-E, which is per-device-random
    // on modern OSes but can also rotate; keep only its presence.
    private val WPS_OUI_TYPE = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04)

    /** Returns null when there are no usable IEs (probe did not forward them). */
    fun of(ies: ByteArray): String? {
        if (ies.isEmpty()) return null
        var h = FNV_INIT
        var pos = 0
        var used = 0
        while (pos + 2 <= ies.size) {
            val id = ies[pos].toInt() and 0xFF
            val len = ies[pos + 1].toInt() and 0xFF
            if (pos + 2 + len > ies.size) break
            val body = pos + 2
            when (id) {
                EID_SSID, EID_DS_PARAMS, EID_REQUEST -> Unit // request-specific
                EID_VENDOR -> {
                    // Vendor elements: OUI + type identify the feature; content may vary.
                    h = fnv(h, id)
                    val n = minOf(len, 4)
                    val head = ies.copyOfRange(body, body + n)
                    h = fnv(h, head)
                    if (!head.contentEquals(WPS_OUI_TYPE)) {
                        // Heuristic: non-WPS vendor content is usually a stable capability
                        // blob. Revisit with real captures if it causes rotation misses.
                        h = fnv(h, ies.copyOfRange(body + n, body + len))
                    }
                    used++
                }
                else -> {
                    // Includes rates (1, 50), HT caps (45), ext caps (127),
                    // VHT caps (191), extensions (255: HE/EHT caps).
                    h = fnv(h, id)
                    h = fnv(h, len)
                    h = fnv(h, ies.copyOfRange(body, body + len))
                    used++
                }
            }
            pos = body + len
        }
        return if (used == 0) null else "%016x".format(h)
    }

    private const val FNV_INIT = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private fun fnv(h0: Long, b: Int): Long = (h0 xor (b.toLong() and 0xFF)) * FNV_PRIME

    private fun fnv(h0: Long, bytes: ByteArray): Long {
        var h = h0
        for (b in bytes) h = fnv(h, b.toInt())
        return h
    }
}
