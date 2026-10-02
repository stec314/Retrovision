// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

import dev.retrovision.core.model.MacAddress

/**
 * IEEE OUI registry lookup. Input is the compact form built by tools/build_vendors.py:
 * one `PREFIX<TAB>NAME` per line, PREFIX being 6, 7 or 9 upper-case hex digits
 * (MA-L, MA-M and MA-S assignments). The longest matching prefix wins.
 */
class OuiDb(private val map: Map<String, String>) {
    val size: Int get() = map.size

    fun lookup(mac: MacAddress): String? {
        if (mac.isLocallyAdministered) return null // randomised: the prefix means nothing
        val hex = "%012X".format(mac.bits)
        return map[hex.substring(0, 9)] ?: map[hex.substring(0, 7)] ?: map[hex.substring(0, 6)]
    }

    companion object {
        fun parse(lines: Sequence<String>): OuiDb {
            val m = HashMap<String, String>(40_000)
            for (l in lines) {
                val t = l.indexOf('\t')
                if (t < 6) continue
                m[l.substring(0, t).uppercase()] = l.substring(t + 1).trim()
            }
            return OuiDb(m)
        }
    }
}

/** Bluetooth SIG company identifiers. Input: `CODE<TAB>NAME`, CODE decimal. */
class CompanyDb(private val map: Map<Int, String>) {
    val size: Int get() = map.size
    fun name(id: Int): String? = map[id]

    companion object {
        fun parse(lines: Sequence<String>): CompanyDb {
            val m = HashMap<Int, String>(4_000)
            for (l in lines) {
                val t = l.indexOf('\t')
                if (t < 1) continue
                val code = l.substring(0, t).toIntOrNull() ?: continue
                m[code] = l.substring(t + 1).trim()
            }
            return CompanyDb(m)
        }
    }
}
