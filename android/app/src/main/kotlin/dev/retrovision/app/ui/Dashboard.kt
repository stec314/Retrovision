// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import dev.retrovision.app.data.Prefs

/** The blocks of the Status dashboard. Ids are stored: don't rename them. */
enum class Widget(val id: String, private val en: String, private val itText: String) {
    VERDICT("verdict", "Verdict", "Verdetto"),
    CONTROLS("controls", "Start / stop", "Avvia / ferma"),
    ATTACKS("attacks", "Radio attacks", "Attacchi radio"),
    OVERVIEW("overview", "Overview", "Panoramica"),
    RADAR("radar", "Radar", "Radar"),
    SENSORS("sensors", "Sensors", "Sensori"),
    PROBES("probes", "Bluetooth probes", "Sonde Bluetooth"),
    ROUTE("route", "Route check", "Verifica percorso"),
    DRONES("drones", "Drones", "Droni"),
    COMPANIONS("companions", "Is this yours?", "È tuo?"),
    CLIENTS("clients", "Connected clients", "Client connessi"),
    REVIEW("review", "Review saved data", "Rivedi i dati salvati"),
    ;

    val label: String get() = Texts.tr(en, itText)
}

/** Order and visibility of the dashboard, saved in preferences. */
object Dashboard {
    class Layout(val order: List<Widget>, val hidden: Set<Widget>) {
        fun move(i: Int, by: Int): Layout {
            val j = (i + by).coerceIn(0, order.size - 1)
            val m = order.toMutableList()
            val w = m.removeAt(i)
            m.add(j, w)
            return Layout(m, hidden)
        }

        fun toggle(w: Widget) = Layout(order, if (w in hidden) hidden - w else hidden + w)

        companion object {
            val DEFAULT = Layout(
                listOf(
                    Widget.VERDICT, Widget.CONTROLS, Widget.PROBES, Widget.ATTACKS, Widget.OVERVIEW, Widget.RADAR, Widget.SENSORS,
                    Widget.ROUTE, Widget.DRONES, Widget.COMPANIONS, Widget.CLIENTS, Widget.REVIEW,
                ),
                emptySet(),
            )
        }
    }

    fun load(p: Prefs): Layout {
        val saved = p.dashboardOrder.split(',').mapNotNull { id -> Widget.entries.firstOrNull { it.id == id } }
        if (saved.isEmpty()) return Layout.DEFAULT
        // Widgets added in a later version go where the default layout has them (after the widget
        // that precedes them there), visible; at the end if that one is not in the saved layout.
        val order = saved.distinct().toMutableList()
        for (w in Layout.DEFAULT.order) {
            if (w in order) continue
            val before = Layout.DEFAULT.order.takeWhile { it != w }.lastOrNull { it in order }
            if (before == null) order.add(0, w) else order.add(order.indexOf(before) + 1, w)
        }
        Widget.entries.filter { it !in order }.forEach { order += it }
        val hidden = p.dashboardHidden.mapNotNull { id -> Widget.entries.firstOrNull { it.id == id } }.toSet()
        return Layout(order, hidden)
    }

    fun save(p: Prefs, l: Layout) {
        p.dashboardOrder = l.order.joinToString(",") { it.id }
        p.dashboardHidden = l.hidden.map { it.id }.toSet()
    }
}
