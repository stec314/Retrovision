// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.enrich

import android.content.Context
import dev.retrovision.core.identity.CompanyDb
import dev.retrovision.core.identity.OuiDb
import dev.retrovision.core.model.MacAddress

/**
 * Offline vendor names. The tables (IEEE OUI registry, Bluetooth SIG company ids) are
 * downloaded by CI into the APK assets; a build without them simply shows no vendors.
 */
object Vendors {
    private var oui: OuiDb? = null
    private var companies: CompanyDb? = null
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        oui = readLines(ctx, "vendors/oui.tsv")?.let { OuiDb.parse(it) }
        companies = readLines(ctx, "vendors/ble_companies.tsv")?.let { CompanyDb.parse(it) }
    }

    private fun readLines(ctx: Context, path: String): Sequence<String>? = try {
        ctx.assets.open(path).bufferedReader().readLines().asSequence()
    } catch (_: Exception) {
        null
    }

    fun forMac(mac: MacAddress): String? = oui?.lookup(mac)

    fun forCompany(id: Int): String? = companies?.name(id)
}
