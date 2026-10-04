// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.FixRow
import dev.retrovision.core.analysis.DeviceVisit

/** Your positions while one device was heard; consecutive minutes within 30 m become one point. */
internal suspend fun heardPlaces(ids: List<String>): List<DeviceVisit> {
    val dao = RetrovisionApp.instance.db.dao()
    val rows = dao.heardBuckets(ids, 60_000L, 20_000)
    if (rows.isEmpty()) return emptyList()
    // Fixes for the whole span, one per 15 s; each bucket takes the nearest one within a minute.
    val fixes = dao.fixesThinned(rows.first().t0 - 60_000L, rows.last().t1 + 60_000L, 15_000L)
    val times = LongArray(fixes.size) { fixes[it].timeMs }
    fun nearest(t: Long): FixRow? {
        if (times.isEmpty()) return null
        var k = java.util.Arrays.binarySearch(times, t)
        if (k < 0) k = -k - 1
        val cand = listOfNotNull(fixes.getOrNull(k - 1), fixes.getOrNull(k))
        return cand.minByOrNull { kotlin.math.abs(it.timeMs - t) }?.takeIf { kotlin.math.abs(it.timeMs - t) <= 60_000L }
    }
    val out = ArrayList<DeviceVisit>()
    for (r in rows) {
        val f = nearest(r.t0) ?: continue
        val lat = f.lat
        val lon = f.lon
        val last = out.lastOrNull()
        if (last != null && r.t0 - last.endMs <= 10 * 60_000L &&
            dev.retrovision.core.analysis.Geo.distanceM(last.lat!!, last.lon!!, lat, lon) < 30.0
        ) {
            out[out.size - 1] = DeviceVisit(-1, last.lat, last.lon, last.startMs, r.t1, last.sightings + r.n, maxOf(last.maxRssi, r.rssi))
        } else {
            out += DeviceVisit(-1, lat, lon, r.t0, r.t1, r.n, r.rssi)
        }
    }
    return out
}


/** Your own GPS track over a span (one fix per 15 s), for drawing next to a device's places. */
internal suspend fun trackBetween(from: Long, to: Long): List<dev.retrovision.core.model.GeoFix> =
    RetrovisionApp.instance.db.dao().fixesThinned(from, to, 15_000L).map { dev.retrovision.core.model.GeoFix(it.timeMs, it.lat, it.lon, it.accuracyM) }

/**
 * The map at the top of a device's detail: every place it was heard (your position then, from all
 * stored data) over your own track for the same period. Shown for devices that may follow you.
 */
@androidx.compose.runtime.Composable
internal fun DeviceMapPanel(r: dev.retrovision.core.analysis.EntityReport, level: dev.retrovision.core.analysis.Level, onOpenPlaces: () -> Unit) {
    val state = androidx.compose.runtime.remember(r.entityId) {
        MapUiState().apply { showStays = false; showRoutine = false; deviceFocus = r.entityId }
    }
    var marks by androidx.compose.runtime.remember(r.entityId) { androidx.compose.runtime.mutableStateOf<DeviceMarks?>(null) }
    var track by androidx.compose.runtime.remember(r.entityId) { androidx.compose.runtime.mutableStateOf<List<dev.retrovision.core.model.GeoFix>>(emptyList()) }
    val here by Collector.location.collectAsState()
    val label = Texts.entityLabel(r)
    androidx.compose.runtime.LaunchedEffect(r.entityId) {
        val pts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { heardPlaces((r.memberIds + r.entityId).toList()) }.getOrDefault(emptyList())
        }.ifEmpty { r.visits.filter { it.lat != null } }
        if (pts.isEmpty()) { marks = DeviceMarks(r.entityId, label, level, DEVICE_COLORS[0], emptyList()); return@LaunchedEffect }
        val from = pts.minOf { it.startMs } - 10 * 60_000L
        val to = pts.maxOf { it.endMs } + 10 * 60_000L
        track = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { trackBetween(from, to) }.getOrDefault(emptyList()) }
        val m = DeviceMarks(r.entityId, label, level, DEVICE_COLORS[0], pts)
        marks = m
        fitDevice(state, m)
    }
    val fmt = androidx.compose.runtime.remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val m = marks
    Panel(
        title = Texts.tr("Where it was heard", "Dove è stato sentito"),
        trailing = {
            androidx.compose.material3.Text(
                Texts.tr("Open in Places", "Apri in Luoghi"),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = androidx.compose.ui.Modifier.clickable { onOpenPlaces() },
            )
        },
    ) {
        if (m == null) {
            androidx.compose.material3.LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth())
            return@Panel
        }
        if (m.visits.isEmpty()) {
            androidx.compose.material3.Text(
                Texts.tr("No GPS position was recorded while it was heard.", "Nessuna posizione GPS registrata mentre veniva sentito."),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            return@Panel
        }
        val map: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit = { mod ->
            TrackMap(
                fixes = track, visits = emptyList(), routine = emptyList(), here = here, state = state,
                onSaveRoutine = {}, onDeleteRoutine = {}, modifier = mod, devices = listOf(m),
            )
        }
        if (state.fullscreen) {
            FullscreenMap(content = { map(androidx.compose.ui.Modifier.fillMaxSize()) }, onClose = { state.fullscreen = false })
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(320.dp))
        } else {
            map(androidx.compose.ui.Modifier.fillMaxWidth().height(320.dp))
        }
        androidx.compose.material3.Text(
            "${m.visits.size} " + Texts.tr("places", "luoghi") + " · " + fmt.format(java.util.Date(m.visits.minOf { it.startMs })) + " → " +
                fmt.format(java.util.Date(m.visits.maxOf { it.endMs })) + " · " +
                Texts.tr("◆ = where you were when it was heard", "◆ = dove eri quando è stato sentito"),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * Stakeout check: devices that recently started turning up at your routine places (home, work) on
 * several days. Neighbours change phones and cars too, so this is a list to look at, not an alarm.
 * Rotating addresses cannot be followed across days, so it sees stable ones: cars, hotspots, tags,
 * many wearables. Fixed routers are left out.
 */
@androidx.compose.runtime.Composable
internal fun NewAtRoutinePanel(onOpen: ((String) -> Unit)? = null) {
    val app = RetrovisionApp.instance
    val today = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
    val start = app.prefs.baselineStartDay
    val learnUntil = if (start == 0L) Long.MAX_VALUE else start + 7
    val since = maxOf(today - 14, if (start == 0L) today else start + 7)
    val rows by androidx.compose.runtime.remember(since) { app.db.dao().newAtRoutine(since, 2) }.collectAsState(initial = emptyList())
    val fmt = androidx.compose.runtime.remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    Panel(title = Texts.tr("New near your routine places", "Nuovi vicino ai tuoi luoghi di routine")) {
        if (today < learnUntil) {
            androidx.compose.material3.Text(
                Texts.tr(
                    "Learning what is normal around your routine places: ready in ${if (start == 0L) 7 else (learnUntil - today)} day(s).",
                    "Sto imparando cosa è normale attorno ai tuoi luoghi di routine: pronto tra ${if (start == 0L) 7 else (learnUntil - today)} giorni.",
                ),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            return@Panel
        }
        if (rows.isEmpty()) {
            androidx.compose.material3.Text(
                Texts.tr("Nothing new in the last two weeks.", "Niente di nuovo nelle ultime due settimane."),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        rows.forEach { b ->
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.fillMaxWidth()
                    .then(if (onOpen != null) androidx.compose.ui.Modifier.clickable { onOpen(b.entityId) } else androidx.compose.ui.Modifier),
            ) {
                androidx.compose.material3.Text(b.label.ifEmpty { b.entityId }, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 1)
                val first = java.util.Date((b.firstDay * 86_400_000L) - java.util.TimeZone.getDefault().getOffset(b.firstDay * 86_400_000L) + 12 * 3_600_000L)
                androidx.compose.material3.Text(
                    Texts.tr("${b.days} days since ", "${b.days} giorni dal ") + fmt.format(first) + " · " +
                        Texts.tr("last ", "ultimo ") + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(b.lastMs)),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Text(
            Texts.tr(
                "Devices first heard at your routine places in the last two weeks and back on at least 2 days. Usually a neighbour's new phone or car; worth a look if it matches when you come and go, or you do not recognise it. Mark yours as mine.",
                "Dispositivi sentiti per la prima volta nei tuoi luoghi di routine nelle ultime due settimane e tornati in almeno 2 giorni. Di solito il telefono o l'auto nuova di un vicino; vale un'occhiata se coincide con i tuoi orari o non lo riconosci. Segna i tuoi come miei.",
            ),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
