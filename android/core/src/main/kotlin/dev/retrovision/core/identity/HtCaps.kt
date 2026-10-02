// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

/**
 * Decodes the 802.11 HT Capabilities element (IE 45) a Wi-Fi client advertises in its probe
 * requests. The 16-bit HT Capabilities Information field, the A-MPDU parameters byte and the
 * HT Extended Capabilities field together describe the device's PHY/driver — a coarse,
 * manufacturer-and-model-level fingerprint that survives MAC randomisation.
 *
 * Why Retrovision decodes it but does NOT use it to link MAC rotations:
 * Puig et al. (arXiv:2606.25788, 2026) show that *decomposing* this field into its subfields
 * (so devices are compared bit by bit, not by the raw 16-bit number) separates device classes
 * well enough for population-scale clustering. That is a tracking technique: its job is to tell
 * strangers apart. Retrovision's job is the opposite and narrower — decide whether ONE device
 * near you is the same across its rotations — and its [WifiFingerprint] already contains the HT
 * bytes exactly, so two candidates for the same rotation always share them. Decomposition cannot
 * separate two phones of the same model (they have identical HT caps), which is exactly the
 * false-merge case we worry about. So here the decoded profile is used only to *show* the user
 * what a device claims to be (transparency), and the Hamming helper is for display/analysis, not
 * for deciding links. The field is also unauthenticated and can be spoofed.
 *
 * Element content layout (IEEE 802.11-2016 §9.4.2.56), after the 2-byte element header:
 *   [0..1]  HT Capabilities Info (LE)   [2] A-MPDU Parameters
 *   [3..18] Supported MCS Set (16 B)    [19..20] HT Extended Capabilities (LE)
 *   [21..24] Tx Beamforming             [25] ASEL
 */
object HtCaps {
    /** Raw fields plus decoded HT Capabilities Information subfields. */
    class Profile(val caps: Int, val ampdu: Int, val extCaps: Int) {
        val ldpc get() = bit(0)
        /** false = 20 MHz only, true = 20/40 MHz. */
        val chWidth40 get() = bit(1)
        /** 0 static, 1 dynamic, 3 disabled (2 reserved). */
        val smPowerSave get() = (caps ushr 2) and 0x3
        val greenfield get() = bit(4)
        val shortGi20 get() = bit(5)
        val shortGi40 get() = bit(6)
        val txStbc get() = bit(7)
        /** 0..3 spatial streams. */
        val rxStbc get() = (caps ushr 8) and 0x3
        val delayedBlockAck get() = bit(10)
        /** false = 3839 B, true = 7935 B. */
        val maxAmsduLong get() = bit(11)
        val dsssCck40 get() = bit(12)
        val fortyIntolerant get() = bit(14)
        val lsigTxop get() = bit(15)

        private fun bit(n: Int) = (caps ushr n) and 1 == 1

        /** Short human-readable summary, e.g. "HT 20MHz · LDPC · SGI20 · 1×STBC". */
        fun summary(): String {
            val parts = ArrayList<String>(6)
            parts += if (chWidth40) "40MHz" else "20MHz"
            if (ldpc) parts += "LDPC"
            if (greenfield) parts += "GF"
            if (shortGi20) parts += "SGI20"
            if (shortGi40) parts += "SGI40"
            if (txStbc) parts += "TxSTBC"
            if (rxStbc > 0) parts += "${rxStbc}×RxSTBC"
            if (fortyIntolerant) parts += "40-intol"
            return "HT " + parts.joinToString(" · ")
        }

        override fun equals(other: Any?) = other is Profile && other.caps == caps && other.ampdu == ampdu && other.extCaps == extCaps
        override fun hashCode() = (caps * 31 + ampdu) * 31 + extCaps
    }

    private const val EID_HT_CAPABILITIES = 45

    /** Decodes the HT Capabilities element from a probe request's raw IEs, or null if absent/short. */
    fun fromIes(ies: ByteArray): Profile? {
        var pos = 0
        while (pos + 2 <= ies.size) {
            val id = ies[pos].toInt() and 0xFF
            val len = ies[pos + 1].toInt() and 0xFF
            val body = pos + 2
            if (body + len > ies.size) break
            if (id == EID_HT_CAPABILITIES && len >= 21) {
                val caps = u8(ies, body) or (u8(ies, body + 1) shl 8)
                val ampdu = u8(ies, body + 2)
                val ext = u8(ies, body + 19) or (u8(ies, body + 20) shl 8)
                return Profile(caps, ampdu, ext)
            }
            pos = body + len
        }
        return null
    }

    /**
     * Bit-difference count between two profiles (subfield decomposition, each bit weighted once).
     * For display and analysis only — see the class note on why it does not drive linking.
     */
    fun hamming(a: Profile, b: Profile): Int =
        Integer.bitCount(a.caps xor b.caps) + Integer.bitCount(a.extCaps xor b.extCaps) +
            Integer.bitCount(a.ampdu xor b.ampdu)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
