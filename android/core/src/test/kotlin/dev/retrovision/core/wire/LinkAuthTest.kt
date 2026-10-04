// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.wire

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkAuthTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun matchesReferenceVector() {
        // Reference computed independently (python hmac-sha256) to pin the byte layout the
        // firmware's rv_link_auth_check also implements.
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(16) { (it + 16).toByte() }
        val mac = LinkAuth.authMac(key, nonce, 0x01020304)
        assertEquals("16831d5bd061526aef4ad487b490681b8a9756d5b69b986c8ef325fcb03d3bc5", hex(mac))
    }
}
