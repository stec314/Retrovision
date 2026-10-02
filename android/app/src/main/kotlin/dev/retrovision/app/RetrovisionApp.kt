// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app

import android.app.Application
import dev.retrovision.app.data.Db
import dev.retrovision.app.data.Prefs

class RetrovisionApp : Application() {
    val db: Db by lazy { Db.open(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread { dev.retrovision.app.enrich.Vendors.load(this) }.start()
        Thread { dev.retrovision.app.map.OfflineMaps.init(this) }.start()
    }

    companion object {
        lateinit var instance: RetrovisionApp
            private set
    }
}
