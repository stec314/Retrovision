// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.wire

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof that the app knows the pairing key, for a wireless probe link. The probe sends a random
 * nonce in its Hello and streams nothing until the HelloAck carries this MAC. Must match the
 * firmware's rv_link_auth_check exactly: HMAC-SHA256(key, "RVAUTH1" || nonce(16) || bootId as 4
 * bytes little-endian).
 */
object LinkAuth {
    fun authMac(key: ByteArray, nonce: ByteArray, bootId: Int): ByteArray {
        val msg = ByteArray(7 + 16 + 4)
        "RVAUTH1".toByteArray(Charsets.US_ASCII).copyInto(msg, 0)
        require(nonce.size == 16) { "nonce must be 16 bytes" }
        nonce.copyInto(msg, 7)
        for (i in 0 until 4) msg[23 + i] = (bootId ushr (8 * i)).toByte()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }
}
