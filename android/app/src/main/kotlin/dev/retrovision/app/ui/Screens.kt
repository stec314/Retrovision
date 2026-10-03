// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.Link
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.IgnoreRow
import dev.retrovision.app.enrich.EnrichException
import dev.retrovision.app.enrich.Enrichers
import dev.retrovision.app.enrich.Query
import dev.retrovision.app.probe.FirmwareAssets
import dev.retrovision.app.probe.FlashRunner
import dev.retrovision.app.probe.Phase
import dev.retrovision.app.service.CollectorService
import dev.retrovision.core.analysis.EntityKind
import dev.retrovision.core.analysis.EntityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val app get() = RetrovisionApp.instance

// ---------------------------------------------------------------- Status

@Composable
fun StatusScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val running by Collector.running.collectAsState()
    val conn by Collector.connection.collectAsState()
    val fix by Collector.location.collectAsState()
    val analysis by Collector.analysis.collectAsState()
    // Polled, not observed: a COUNT(*) re-run after every insert batch scanned millions of rows twice a second.
    var count by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            count = runCatching { app.db.dao().sightingEstimate() }.getOrDefault(count)
            kotlinx.coroutines.delay(15_000)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) CollectorService.start(ctx)
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Retrovision", style = MaterialTheme.typography.headlineMedium)
        Text(
            Texts.tr(
                "The probe only listens. It never transmits, and nothing leaves this phone unless you tap a lookup.",
                "La sonda ascolta soltanto: non trasmette mai e nulla lascia il telefono se non avvii tu una ricerca.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        val verdict = rememberVerdict()
        VerdictCard(verdict)
        Button(
            onClick = {
                if (running) CollectorService.stop(ctx) else {
                    val req = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 31) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                    permissions.launch(req.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) Texts.tr("Stop collecting", "Ferma la raccolta") else Texts.tr("Start collecting", "Avvia la raccolta")) }

        var crash by remember { mutableStateOf(dev.retrovision.app.CrashLog.read(ctx)) }
        crash?.let { text ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Texts.tr("The app crashed last time", "L'app si è chiusa per un errore l'ultima volta"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(text.lines().take(14).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Retrovision crash", text))
                        }) { Text(Texts.tr("Copy", "Copia")) }
                        OutlinedButton(onClick = { DiagNav.open.value = true }) { Text(Texts.tr("Details", "Dettagli")) }
                        TextButton(onClick = { dev.retrovision.app.CrashLog.clear(ctx); crash = null }) { Text(Texts.tr("Dismiss", "Chiudi")) }
                    }
                }
            }
        }

        val threats by Collector.threats.collectAsState()
        if (threats.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().clickable { AlertsNav.open.value = true },
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠ " + Texts.tr("Wi-Fi attacks nearby", "Attacchi Wi-Fi nelle vicinanze"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    threats.take(5).forEach {
                        Text("• " + Texts.threat(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Text(Texts.tr("Tap for the evidence ›", "Tocca per le prove ›"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        WhatToDoCard(verdict)
        val radar by Collector.liveRadar.collectAsState()
        if (running && radar.blips.isNotEmpty()) {
            Text(Texts.tr("Radar", "Radar"), style = MaterialTheme.typography.titleMedium)
            RadarView()
        }

        // Probe, phone and GPS: one line each by default; the full technical detail on tap.
        var showSensors by rememberSaveable { mutableStateOf(false) }
        SensorsSummary(expanded = showSensors) { showSensors = !showSensors }
        if (showSensors) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Texts.tr("Probe", "Sonda"), style = MaterialTheme.typography.titleMedium)
                Text(linkText(conn.link, conn.device, conn.error))
                conn.session?.let { s ->
                    s.info?.let {
                        Text(probeModel(it.probeType), style = MaterialTheme.typography.bodyLarge)
                        Text("fw ${it.firmware} · proto ${it.protocol} · id ${it.hardwareId}", style = MaterialTheme.typography.bodySmall)
                    }
                    val h = probeHealth(s)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(h.dot, color = h.color)
                        Text(h.text, color = h.color, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(phaseText(s.phase) + (if (s.clockUncertaintyUs >= 0) " · ±${s.clockUncertaintyUs} µs" else ""))
                    if (s.rejectReason.isNotEmpty()) Text(s.rejectReason, color = MaterialTheme.colorScheme.error)
                    val seen = s.wifiObs + s.bleObs
                    Text("Wi-Fi ${s.wifiObs} · BLE ${s.bleObs}" + (if (s.channel > 0) " · ch ${s.channel}" else ""), style = MaterialTheme.typography.bodySmall)
                    val lost = s.lostFrames + s.probeDropped
                    val lossPct = if (seen + lost > 0) 100.0 * lost / (seen + lost) else 0.0
                    Text(
                        Texts.tr("lost", "persi") + " $lost (%.2f%%)".format(lossPct) + " · CRC ${s.badFrames}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lossPct > 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (s.channel > 0) Text("${s.freeHeap / 1024} KiB free · ${"%.0f".format(s.chipTempC)} °C", style = MaterialTheme.typography.bodySmall)
                    if (s.lastLog.isNotEmpty()) Text(s.lastLog, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
        PhoneCard(conn.session?.phase == dev.retrovision.app.probe.Phase.STREAMING, running)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("GPS", style = MaterialTheme.typography.titleMedium)
                val f = fix
                Text(
                    if (f == null) Texts.tr("No fix yet", "Nessun fix")
                    else "%.5f, %.5f (±%.0f m)".format(f.lat, f.lon, f.accuracyM),
                )
                Text(Texts.tr("Stored sightings: ≈", "Avvistamenti salvati: ≈") + count)
            }
        }
        }
        RouteCheckCard(analysis)
        CompanionsCard()

        val drones by Collector.drones.collectAsState()
        if (drones.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().clickable { AlertsNav.open.value = true },
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🛸 " + Texts.tr("Drones nearby", "Droni nelle vicinanze"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    drones.take(5).forEach {
                        Text("• " + Texts.drone(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }

        val assoc by Collector.associations.collectAsState()
        if (assoc.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Texts.tr("Connected clients (data frames)", "Client connessi (frame di dati)"), style = MaterialTheme.typography.titleMedium)
                    assoc.take(6).forEach { ap ->
                        Text(
                            "• " + (ap.ssid ?: ap.bssid.toString()) + " — " + ap.clients.size + " " + Texts.tr("clients", "client"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

                RetrospectiveCard()
    }
}

private fun linkText(l: Link, device: String, err: String) = when (l) {
    Link.STOPPED -> Texts.tr("Collection stopped", "Raccolta ferma")
    Link.NO_DEVICE -> Texts.tr("Plug the probe into the USB-C port (OTG)", "Collega la sonda alla porta USB-C (OTG)")
    Link.NEED_PERMISSION -> Texts.tr("Waiting for USB permission…", "In attesa del permesso USB…") + " $device"
    Link.CONNECTING -> Texts.tr("Connecting…", "Connessione…") + " $device"
    Link.CONNECTED -> Texts.tr("Connected: ", "Connesso: ") + device
    Link.ERROR -> err
}

private fun phaseText(p: Phase) = when (p) {
    Phase.WAITING_HELLO -> Texts.tr("Waiting for the probe to introduce itself", "In attesa della sonda")
    Phase.SYNCING -> Texts.tr("Synchronising clocks", "Sincronizzazione orologi")
    Phase.STREAMING -> Texts.tr("Streaming", "Ricezione attiva")
    Phase.REJECTED -> Texts.tr("Rejected", "Rifiutata")
}

// ---------------------------------------------------------------- Devices

@Composable
internal fun DeviceDialog(r: EntityReport, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val enrichers = remember { Enrichers(app.prefs, app.db.dao()) }
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    val queries: List<Pair<String, Query>> = buildList {
        val first = r.addresses.first()
        when (r.kind) {
            EntityKind.WIFI_AP -> add("BSSID $first" to Query.WifiBssid(first))
            EntityKind.BLE_TRACKER, EntityKind.BLE_DEVICE -> add("BLE $first" to Query.BleAddress(first))
            EntityKind.WIFI_CLIENT -> r.ssids.take(4).forEach { add("SSID “$it”" to Query.WifiSsid(it)) }
        }
        if (r.kind == EntityKind.WIFI_AP && r.ssids.isNotEmpty()) add("SSID “${r.ssids.first()}”" to Query.WifiSsid(r.ssids.first()))
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Texts.entityLabel(r)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val lv = dev.retrovision.core.analysis.Levels.of(r)
                Text("${Texts.levelIcon(lv)} ${Texts.level(lv)} · ${r.placeIds.size} ${Texts.tr("places", "luoghi")}", style = MaterialTheme.typography.titleSmall)
                Text(
                    Texts.tr(
                        "Score %.2f: a sum of clues, not a probability. The alert threshold is %.2f.".format(r.score, app.prefs.alertScore),
                        "Punteggio %.2f: una somma di indizi, non una probabilità. La soglia di allerta è %.2f.".format(r.score, app.prefs.alertScore),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(Texts.tr("First seen ", "Primo avvistamento ") + fmt.format(Date(r.firstSeenMs)), style = MaterialTheme.typography.bodySmall)
                Text(Texts.tr("Last seen ", "Ultimo avvistamento ") + fmt.format(Date(r.lastSeenMs)), style = MaterialTheme.typography.bodySmall)
                r.reasons.forEach { Text("• " + Texts.reason(it), style = MaterialTheme.typography.bodySmall) }
                r.notable.filter { it.note.isNotBlank() }.forEach {
                    Text("👁 ${it.name}: ${it.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                DeviceDetails(r)
                queries.forEach { (label, q) ->
                    enrichers.all().filter { it.supports(q) }.forEach { e ->
                        val ready = enrichers.available(q).any { it.id == e.id }
                        OutlinedButton(
                            enabled = ready && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    output = try {
                                        withContext(Dispatchers.IO) { enrichers.lookup(e, q) }.let {
                                            listOf("${it.source}${if (it.fromCache) " (cache)" else ""} — $label") + it.lines
                                        }
                                    } catch (ex: EnrichException) {
                                        listOf(ex.message ?: "error")
                                    } catch (ex: Exception) {
                                        listOf("${e.label}: ${ex.message ?: ex.javaClass.simpleName}")
                                    }
                                    busy = false
                                }
                            },
                        ) { Text("${e.label}: $label" + if (!ready) Texts.tr("  (set up in Settings)", "  (configura in Impostazioni)") else "") }
                    }
                }
                var findIt by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { findIt = true }) { Text(Texts.tr("Find it (hot/cold)", "Trovalo (caldo/freddo)")) }
                if (findIt) FindItDialog(r.entityId, Texts.entityLabel(r)) { findIt = false }
                val isTarget = r.entityId in app.prefs.targets
                OutlinedButton(onClick = {
                    app.prefs.targets = if (isTarget) app.prefs.targets - r.entityId else app.prefs.targets + r.entityId
                    onClose()
                }) { Text(if (isTarget) Texts.tr("Unmark as test target", "Togli dai bersagli di prova") else Texts.tr("Mark as field-test target", "Segna come bersaglio di prova")) }
                FeedbackRowUi(r, onClose)
                output.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    for (m in r.memberIds) app.db.dao().addIgnore(IgnoreRow(m, Texts.entityLabel(r), System.currentTimeMillis()))
                    Collector.analyzeNow.value = System.nanoTime()
                    onClose()
                }
            }) { Text(Texts.tr("Ignore (mine)", "Ignora (è mio)")) }
        },
    )
}

// ---------------------------------------------------------------- Probe / flasher

@Composable
fun ProbeScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val conn by Collector.connection.collectAsState()
    val flash by Collector.flash.collectAsState()
    val images = remember { FirmwareAssets.load(ctx) }
    var confirmAll by remember { mutableStateOf(false) }
    var allowExperimental by remember { mutableStateOf(false) }
    val hasExperimental = images.any { it.experimental }

    val view = LocalView.current
    DisposableEffect(flash.running) {
        view.keepScreenOn = flash.running
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Texts.tr("Probe firmware", "Firmware della sonda"), style = MaterialTheme.typography.headlineSmall)
        Text(linkText(conn.link, conn.device, conn.error))
        conn.session?.info?.let { Text("${Texts.tr("Installed", "Installato")}: ${it.firmware} (${it.probeType})") }

        var ledOn by remember { mutableStateOf(app.prefs.probeLedOn) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.tr("Probe status LED", "LED di stato della sonda"))
                    Switch(
                        checked = ledOn,
                        onCheckedChange = {
                            ledOn = it
                            app.prefs.probeLedOn = it
                            Collector.probeLedOn.value = it
                            Collector.session?.setLedEnabled(it)
                        },
                    )
                }
                Text(
                    Texts.tr(
                        "Off = the probe runs dark. Applied immediately to the connected probe, and remembered for the next one.",
                        "Spento = la sonda resta al buio. Applicato subito alla sonda collegata e ricordato per la prossima.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (images.isEmpty()) {
            Text(
                Texts.tr(
                    "This build has no firmware bundled. Use the release APK from GitHub, or the web flasher.",
                    "Questa build non include il firmware. Usa l'APK di release da GitHub o il flasher web.",
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (images.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Texts.tr("Firmware ", "Firmware ") + images.first().version, style = MaterialTheme.typography.titleMedium)
                    images.forEach { img ->
                        Text(
                            "• ${img.chip.label} · ${img.data.size / 1024} KiB" +
                                if (img.experimental) Texts.tr(" · EXPERIMENTAL", " · SPERIMENTALE") else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (img.experimental) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    }
                    Text(
                        Texts.tr(
                            "The board is detected automatically: XIAO ESP32-S3, classic ESP32 (NodeMCU-32S, DevKitC)" +
                                (if (hasExperimental) ", or ESP32-C5 (experimental)." else "."),
                            "La scheda viene riconosciuta da sola: XIAO ESP32-S3, ESP32 classica (NodeMCU-32S, DevKitC)" +
                                (if (hasExperimental) " oppure ESP32-C5 (sperimentale)." else "."),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasExperimental) {
                        Text(
                            Texts.tr(
                                "ESP32-C5: in-app flashing is EXPERIMENTAL and has never been tested on a real C5. " +
                                    "The browser flasher (ESP Web Tools, from a computer) remains the safe way. " +
                                    "Without this switch a C5 is detected and left untouched.",
                                "ESP32-C5: il flash dall'app è SPERIMENTALE e non è mai stato provato su una C5 reale. " +
                                    "Il flasher web (ESP Web Tools, dal computer) resta la via sicura. " +
                                    "Senza questo interruttore una C5 viene riconosciuta e lasciata intatta.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(Texts.tr("Allow experimental ESP32-C5 flashing", "Consenti flash sperimentale ESP32-C5"), Modifier.weight(1f))
                            Switch(checked = allowExperimental, enabled = !flash.running, onCheckedChange = { allowExperimental = it })
                        }
                    }
                    Button(enabled = !flash.running, onClick = { confirmAll = true }) { Text(Texts.tr("Flash the connected board", "Flasha la scheda collegata")) }
                }
            }
        }
        Text(
            Texts.tr(
                "Use the phone's USB-C port (OTG) and keep this screen open while flashing. The chip is verified before anything is erased, and the written image is checked by MD5. " +
                    "If the board does not come back, hold BOOT, press RESET, release BOOT, and flash again.",
                "Usa la porta USB-C del telefono (OTG) e tieni aperta questa schermata. Il chip viene verificato prima di cancellare e l'immagine scritta è controllata con MD5. " +
                    "Se la scheda non riparte: tieni BOOT, premi RESET, rilascia BOOT e riprova.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (flash.stage.isNotEmpty()) {
            Text(flash.stage, style = MaterialTheme.typography.titleSmall)
            if (flash.total > 0) LinearProgressIndicator(progress = { flash.done.toFloat() / flash.total }, modifier = Modifier.fillMaxWidth())
            else if (flash.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (flash.error.isNotEmpty()) Text(flash.error, color = MaterialTheme.colorScheme.error)
        if (flash.success) Text(Texts.tr("Done. Unplug and replug the probe if it does not reconnect in a few seconds.", "Fatto. Se la sonda non si riconnette in pochi secondi, scollega e ricollega."))
        flash.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(Texts.tr("Flash the probe?", "Flashare la sonda?")) },
            text = {
                Text(
                    Texts.tr("This overwrites the firmware on the connected board.", "Sovrascrive il firmware della scheda collegata.") +
                        if (allowExperimental) {
                            Texts.tr(
                                "\n\nExperimental ESP32-C5 flashing is ON: untested on hardware. If it fails, use the browser flasher.",
                                "\n\nFlash sperimentale ESP32-C5 ATTIVO: non testato su hardware. Se fallisce, usa il flasher web.",
                            )
                        } else "",
                )
            },
            confirmButton = { TextButton(onClick = { FlashRunner.start(ctx, images, allowExperimental); confirmAll = false }) { Text(Texts.tr("Flash", "Flasha")) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier) {
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val ignores by remember { app.db.dao().ignores() }.collectAsState(initial = emptyList())
    var alertScore by remember { mutableFloatStateOf(prefs.alertScore) }
    var minPlaces by remember { mutableIntStateOf(prefs.alertMinPlaces) }
    var lookback by remember { mutableIntStateOf(prefs.lookbackMin) }
    var retention by remember { mutableIntStateOf(prefs.retentionDays) }
    var gpsAcc by remember { mutableIntStateOf(prefs.maxFixAccuracyM) }
    var own by remember { mutableStateOf(prefs.ownSsids) }
    var wName by remember { mutableStateOf(prefs.wigleName) }
    var wToken by remember { mutableStateOf(prefs.wigleToken) }
    var beacon by remember { mutableStateOf(prefs.beaconDbEnabled) }
    var wipe by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Texts.tr("Settings", "Impostazioni"), style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = { WikiNav.open.value = true }, modifier = Modifier.fillMaxWidth()) {
            Text(Texts.tr("📖 Guide: how it works, heuristics, limits", "📖 Guida: come funziona, euristiche, limiti"))
        }
        OutlinedButton(onClick = { DiagNav.open.value = true }, modifier = Modifier.fillMaxWidth()) {
            Text(Texts.tr("Diagnostics (errors, performance, report)", "Diagnostica (errori, prestazioni, report)"))
        }

        Text(Texts.tr("Alert when score ≥ ", "Allerta con punteggio ≥ ") + "%.0f%%".format(alertScore * 100))
        Slider(value = alertScore, onValueChange = { alertScore = it }, onValueChangeFinished = { prefs.alertScore = alertScore }, valueRange = 0.3f..0.95f)
        Text(Texts.tr("…and seen at ≥ $minPlaces places", "…e visto in ≥ $minPlaces luoghi"))
        Slider(value = minPlaces.toFloat(), onValueChange = { minPlaces = it.toInt() }, onValueChangeFinished = { prefs.alertMinPlaces = minPlaces }, valueRange = 2f..6f, steps = 3)
        Text(Texts.tr("Analysis window: $lookback min", "Finestra di analisi: $lookback min"))
        Slider(value = lookback.toFloat(), onValueChange = { lookback = (it / 15).toInt() * 15 }, onValueChangeFinished = { prefs.lookbackMin = lookback }, valueRange = 30f..720f)
        Text(Texts.tr("Ignore GPS fixes worse than ±$gpsAcc m", "Ignora posizioni GPS peggiori di ±$gpsAcc m"))
        Text(
            Texts.tr(
                "Indoors or in a car the GPS drifts and fakes movement. Lower = stricter (fewer false alerts, fewer fixes).",
                "In casa o in auto il GPS deriva e simula spostamenti. Più basso = più severo (meno falsi allarmi, meno posizioni).",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(value = gpsAcc.toFloat(), onValueChange = { gpsAcc = (it / 5).toInt() * 5 }, onValueChangeFinished = { prefs.maxFixAccuracyM = gpsAcc }, valueRange = 20f..150f)
        Text(Texts.tr("Keep data for $retention days", "Conserva i dati per $retention giorni"))
        Slider(value = retention.toFloat(), onValueChange = { retention = it.toInt() }, onValueChangeFinished = { prefs.retentionDays = retention }, valueRange = 1f..30f)

        NotificationsSection()

        TrustedApsSection()
        OwnPhoneSection()
        FeedbackStatsSection()

        Text(Texts.tr("Phone sensors", "Sensori del telefono"), style = MaterialTheme.typography.titleMedium)
        var bleMode by remember { mutableIntStateOf(prefs.phoneBleMode) }
        Text(Texts.tr("Use the phone's Bluetooth as a receiver", "Usa il Bluetooth del telefono come ricevitore"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to Texts.tr("Off", "No"), 1 to Texts.tr("Without probe", "Senza sonda"), 2 to Texts.tr("Always", "Sempre")).forEach { (v, l) ->
                FilterChip(selected = bleMode == v, onClick = { bleMode = v; prefs.phoneBleMode = v }, label = { Text(l) })
            }
        }
        var drift by remember { mutableStateOf(prefs.driftGuard) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                Texts.tr("Reject GPS drift while the phone is still (accelerometer)", "Scarta la deriva GPS quando il telefono è fermo (accelerometro)"),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = drift, onCheckedChange = { drift = it; prefs.driftGuard = it })
        }

        OutlinedTextField(
            value = own, onValueChange = { own = it; prefs.ownSsids = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(Texts.tr("Your own Wi-Fi names (comma separated)", "Nomi delle tue reti Wi-Fi (separati da virgola)")) },
        )

        Text("WiGLE", style = MaterialTheme.typography.titleMedium)
        Text(
            Texts.tr(
                "Free account at wigle.net → Account → API token. Lookups send only the identifier you tap.",
                "Account gratuito su wigle.net → Account → token API. Le ricerche inviano solo l'identificativo che tocchi.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(value = wName, onValueChange = { wName = it; prefs.wigleName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API name") }, singleLine = true)
        OutlinedTextField(
            value = wToken, onValueChange = { wToken = it; prefs.wigleToken = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("API token") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BeaconDB (Wi-Fi BSSID)")
            Switch(checked = beacon, onCheckedChange = { beacon = it; prefs.beaconDbEnabled = it })
        }

        var dataFrames by remember { mutableStateOf(prefs.captureDataFrames) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Capture data frames (connected clients)", "Cattura frame di dati (client connessi)"), modifier = Modifier.weight(1f))
            Switch(checked = dataFrames, onCheckedChange = {
                dataFrames = it; prefs.captureDataFrames = it
                Collector.captureDataFrames.value = it
                Collector.session?.resendConfig()
            })
        }
        Text(
            Texts.tr(
                "Invasive: reveals devices connected to nearby networks that never send probe requests. More radio load and more data. Off by default.",
                "Invasivo: mostra i dispositivi connessi alle reti vicine che non inviano probe request. Più carico radio e più dati. Spento di default.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(Texts.tr("Ignored devices", "Dispositivi ignorati") + " (${ignores.size})", style = MaterialTheme.typography.titleMedium)
        ignores.forEach { ig ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ig.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { app.db.dao().removeIgnore(ig.entityId) } }) { Text(Texts.tr("Remove", "Rimuovi")) }
            }
        }

        SessionsSection()
        FieldTestSection()

        OutlinedButton(onClick = { wipe = true }, modifier = Modifier.fillMaxWidth()) { Text(Texts.tr("Delete all collected data", "Elimina tutti i dati raccolti")) }
    }

    if (wipe) {
        AlertDialog(
            onDismissRequest = { wipe = false },
            title = { Text(Texts.tr("Delete everything?", "Eliminare tutto?")) },
            text = { Text(Texts.tr("Sightings, GPS track and cached lookups will be erased.", "Avvistamenti, tracce GPS e ricerche in cache verranno cancellati.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val dao = app.db.dao()
                        dao.wipeSightings(); dao.wipeFixes(); dao.wipeEnrichments(); dao.wipeFamiliar(); dao.wipeBaseline()
                        dao.wipeCompanions(); dao.wipeFeedback()
                        dev.retrovision.app.data.SessionRecorder.dir(app).listFiles()?.forEach { it.delete() }
                        prefs.trustedAps = emptySet(); prefs.ownFingerprints = emptySet()
                        prefs.targets = emptySet(); prefs.testFirstAlerts = emptySet()
                        Collector.analysis.value = null
                        wipe = false
                    }
                }) { Text(Texts.tr("Delete", "Elimina")) }
            },
            dismissButton = { TextButton(onClick = { wipe = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}


/** Lets the user decide how and when alerts notify them. */
@Composable
private fun NotificationsSection() {
    val ctx = LocalContext.current
    val prefs = app.prefs
    var enabled by remember { mutableStateOf(prefs.alertsEnabled) }
    var once by remember { mutableStateOf(prefs.alertOncePerDevice) }
    var cooldown by remember { mutableIntStateOf(prefs.alertCooldownMin) }
    var rises by remember { mutableStateOf(prefs.alertOnlyIfScoreRises) }
    var silent by remember { mutableStateOf(prefs.alertSilent) }
    var quiet by remember { mutableStateOf(prefs.quietHoursEnabled) }
    var qStart by remember { mutableIntStateOf(prefs.quietStartHour) }
    var qEnd by remember { mutableIntStateOf(prefs.quietEndHour) }

    Text(Texts.tr("Alerts", "Allarmi"), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Texts.tr("Notify when a device may be following you", "Avvisa quando un dispositivo potrebbe seguirti"), modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.alertsEnabled = it })
    }
    if (enabled) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Only once per device (this session)", "Solo una volta per dispositivo (questa sessione)"), modifier = Modifier.weight(1f))
            Switch(checked = once, onCheckedChange = { once = it; prefs.alertOncePerDevice = it })
        }
        if (!once) {
            Text(Texts.tr("Wait between repeats for the same device: $cooldown min", "Attesa tra ripetizioni per lo stesso dispositivo: $cooldown min"))
            Slider(
                value = cooldown.toFloat(),
                onValueChange = { cooldown = (it / 5).toInt() * 5 },
                onValueChangeFinished = { prefs.alertCooldownMin = cooldown },
                valueRange = 5f..360f,
            )
            Text(
                Texts.tr(
                    "A clear jump in score (+15%) always gets through, whatever the wait.",
                    "Un salto netto del punteggio (+15%) passa sempre, qualunque sia l'attesa.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Repeat only if the score went up", "Ripeti solo se il punteggio è salito"), modifier = Modifier.weight(1f))
            Switch(checked = rises, onCheckedChange = { rises = it; prefs.alertOnlyIfScoreRises = it })
        }
        var discreet by remember { mutableStateOf(prefs.discreetAlerts) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Discreet notifications", "Notifiche discrete"), modifier = Modifier.weight(1f))
            Switch(checked = discreet, onCheckedChange = { discreet = it; prefs.discreetAlerts = it })
        }
        Text(
            Texts.tr(
                "Notifications only say “Something to check”, even with the phone unlocked. On the lock screen they always do.",
                "Le notifiche dicono solo “Qualcosa da controllare”, anche a telefono sbloccato. Sulla schermata di blocco è sempre così.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Silent (no sound or vibration)", "Silenzioso (niente suono o vibrazione)"), modifier = Modifier.weight(1f))
            Switch(checked = silent, onCheckedChange = { silent = it; prefs.alertSilent = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Quiet hours", "Ore di silenzio"), modifier = Modifier.weight(1f))
            Switch(checked = quiet, onCheckedChange = { quiet = it; prefs.quietHoursEnabled = it })
        }
        if (quiet) {
            Text(Texts.tr("From $qStart:00 to $qEnd:00 (alerts held until it ends)", "Dalle $qStart:00 alle $qEnd:00 (gli allarmi attendono la fine)"))
            Text(Texts.tr("Start", "Inizio") + " $qStart:00", style = MaterialTheme.typography.bodySmall)
            Slider(value = qStart.toFloat(), onValueChange = { qStart = it.toInt() }, onValueChangeFinished = { prefs.quietStartHour = qStart }, valueRange = 0f..23f, steps = 22)
            Text(Texts.tr("End", "Fine") + " $qEnd:00", style = MaterialTheme.typography.bodySmall)
            Slider(value = qEnd.toFloat(), onValueChange = { qEnd = it.toInt() }, onValueChangeFinished = { prefs.quietEndHour = qEnd }, valueRange = 0f..23f, steps = 22)
        }
        var away by remember { mutableStateOf(prefs.alertsOnlyAwayFromFamiliar) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Only when away from routine places", "Solo quando sei fuori dai luoghi di routine"), modifier = Modifier.weight(1f))
            Switch(checked = away, onCheckedChange = { away = it; prefs.alertsOnlyAwayFromFamiliar = it })
        }
        var droneOn by remember { mutableStateOf(prefs.droneAlerts) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Warn me when a drone is nearby", "Avvisami quando c'è un drone vicino"), modifier = Modifier.weight(1f))
            Switch(checked = droneOn, onCheckedChange = { droneOn = it; prefs.droneAlerts = it })
        }
        var disc by remember { mutableStateOf(prefs.probeDisconnectAlert) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Texts.tr("Warn me if the probe disconnects", "Avvisami se la sonda si scollega"), modifier = Modifier.weight(1f))
            Switch(checked = disc, onCheckedChange = { disc = it; prefs.probeDisconnectAlert = it })
        }
        TextButton(onClick = {
            val i = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }) { Text(Texts.tr("Sound & vibration (system settings)", "Suono e vibrazione (impostazioni di sistema)")) }
    }
}


internal fun probeModel(probeType: String): String = when (probeType) {
    "dev.retrovision.esp32s3" -> "ESP32-S3 (XIAO)"
    "dev.retrovision.esp32" -> Texts.tr("Classic ESP32 (NodeMCU/DevKitC)", "ESP32 classica (NodeMCU/DevKitC)")
    "dev.retrovision.esp32c5" -> "ESP32-C5 (dual-band, Wi-Fi 6)"
    else -> probeType.removePrefix("dev.retrovision.")
}

internal class Health(val dot: String, val text: String, val color: androidx.compose.ui.graphics.Color)

@Composable
internal fun probeHealth(s: dev.retrovision.app.probe.SessionState): Health {
    val ok = androidx.compose.ui.graphics.Color(0xFF7CF29A)
    val warn = androidx.compose.ui.graphics.Color(0xFFFFC857)
    val bad = MaterialTheme.colorScheme.error
    val lost = s.lostFrames + s.probeDropped
    val seen = s.wifiObs + s.bleObs
    val lossPct = if (seen + lost > 0) 100.0 * lost / (seen + lost) else 0.0
    return when {
        s.phase == dev.retrovision.app.probe.Phase.REJECTED -> Health("●", Texts.tr("Rejected", "Rifiutata"), bad)
        s.chipTempC >= 80f -> Health("●", Texts.tr("Hot: ${"%.0f".format(s.chipTempC)} °C — give it air", "Calda: ${"%.0f".format(s.chipTempC)} °C — dalle aria"), bad)
        s.freeHeap in 1..20480 -> Health("●", Texts.tr("Low memory", "Memoria bassa"), warn)
        lossPct > 5 -> Health("●", Texts.tr("Dropping frames (%.1f%%)".format(lossPct), "Perde frame (%.1f%%)".format(lossPct)), warn)
        s.chipTempC >= 70f -> Health("●", Texts.tr("Warm: ${"%.0f".format(s.chipTempC)} °C", "Tiepida: ${"%.0f".format(s.chipTempC)} °C"), warn)
        s.phase == dev.retrovision.app.probe.Phase.STREAMING -> Health("●", Texts.tr("Healthy", "In salute"), ok)
        else -> Health("●", Texts.tr("Connecting…", "Connessione…"), warn)
    }
}


/** The phone's own receivers, and what "phone only" can and cannot do. */
@Composable
private fun PhoneCard(probeStreaming: Boolean, running: Boolean) {
    val bleOn by Collector.phoneBleActive.collectAsState()
    val heard by Collector.phoneBleHeard.collectAsState()
    val coded by Collector.phoneCodedPhy.collectAsState()
    val motion by Collector.phoneMotion.collectAsState()
    val drift by Collector.driftRejected.collectAsState()
    var classic by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Texts.tr("Phone sensors", "Sensori del telefono"), style = MaterialTheme.typography.titleMedium)
            Text(
                "Bluetooth LE: " + (if (bleOn) Texts.tr("listening", "in ascolto") + " · $heard" else Texts.tr("off", "spento")) +
                    (if (bleOn && coded) " · Long Range" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                Texts.tr("Motion: ", "Movimento: ") + when (motion) {
                    dev.retrovision.app.phone.MotionState.RESTING -> Texts.tr("still (resting)", "fermo (appoggiato)")
                    dev.retrovision.app.phone.MotionState.HANDHELD -> Texts.tr("still (in hand)", "fermo (in mano)")
                    dev.retrovision.app.phone.MotionState.MOVING -> Texts.tr("walking / moving", "cammini / in movimento")
                    dev.retrovision.app.phone.MotionState.UNKNOWN -> Texts.tr("measuring…", "misuro…")
                } +
                    (if (drift > 0) Texts.tr(" · $drift drifting GPS fixes rejected", " · $drift posizioni GPS in deriva scartate") else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (running && !probeStreaming && bleOn) {
                Text(
                    Texts.tr(
                        "Phone-only mode: trackers, Bluetooth drones, BLE spam and notable Bluetooth devices work. Wi-Fi probe requests, Wi-Fi attacks and Wi-Fi Remote ID need the probe.",
                        "Modalità solo telefono: tracker, droni via Bluetooth, BLE spam e dispositivi Bluetooth notevoli funzionano. Probe request, attacchi Wi-Fi e Remote ID via Wi-Fi richiedono la sonda.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedButton(onClick = { classic = true }) { Text(Texts.tr("Scan Classic Bluetooth (~12 s)", "Scansione Bluetooth Classic (~12 s)")) }
        }
    }
    if (classic) ClassicScanDialog(onClose = { classic = false })
}


/** Route check: who stayed with you through your recent changes of direction. */
@Composable
private fun RouteCheckCard(analysis: dev.retrovision.core.analysis.AnalysisResult?) {
    val a = analysis ?: return
    if (a.turns < 2) return
    // Devices already judged fixed (stays put / one area) are not "staying with you": left out.
    val stayed = a.entities.mapNotNull { e ->
        if (e.reasons.any { it is dev.retrovision.core.analysis.Reason.StaysPut || it is dev.retrovision.core.analysis.Reason.OneAreaOnly }) return@mapNotNull null
        e.reasons.filterIsInstance<dev.retrovision.core.analysis.Reason.StayedThroughTurns>().firstOrNull()?.let { e to it.turns }
    }.sortedByDescending { it.second }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Texts.tr("Route check", "Verifica percorso"), style = MaterialTheme.typography.titleMedium)
            Text(
                Texts.tr(
                    "${a.turns} turns in the window. On a straight road everyone \"follows\" you; what stays through turns matters.",
                    "${a.turns} svolte nella finestra. Su una strada dritta tutti ti \"seguono\": conta chi resta anche dopo le svolte.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (stayed.isEmpty()) Text(Texts.tr("Nothing stayed with you through 2+ turns.", "Niente è rimasto con te per 2 o più svolte."))
            stayed.take(6).forEach { (e, n) -> Text("• ${Texts.entityLabel(e)} — $n/${a.turns}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** "Is this yours?" — devices that travelled with you on several days. Never proposes trackers. */
@Composable
private fun CompanionsCard() {
    val dao = app.db.dao()
    val list by remember { dao.companionSuggestions() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    if (list.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Texts.tr("Is this yours?", "È tuo?"), style = MaterialTheme.typography.titleMedium)
            Text(
                Texts.tr(
                    "These travelled with you on ${list.first().days}+ days. If they're yours (watch, earbuds, car), ignore them to cut false alerts. Trackers are never proposed here.",
                    "Hanno viaggiato con te per ${list.first().days}+ giorni. Se sono tuoi (orologio, cuffie, auto) ignorali per ridurre i falsi allarmi. I tracker non vengono mai proposti qui.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            list.take(5).forEach { c ->
                Text(c.label, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            dao.addIgnore(IgnoreRow(c.entityId, c.label, System.currentTimeMillis()))
                            dao.putCompanion(dev.retrovision.app.data.CompanionRow(c.entityId, c.days, c.lastDay, 2, c.label, System.currentTimeMillis()))
                            dao.addFeedback(dev.retrovision.app.data.FeedbackRow(entityId = c.entityId, label = 1, score = 0.0, reasons = "Companion", timeMs = System.currentTimeMillis()))
                        }
                    }) { Text(Texts.tr("Yes, mine", "Sì, è mio")) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            dao.putCompanion(dev.retrovision.app.data.CompanionRow(c.entityId, c.days, c.lastDay, 3, c.label, System.currentTimeMillis()))
                        }
                    }) { Text(Texts.tr("No", "No")) }
                }
            }
        }
    }
}

/** Your phone's Wi-Fi association and the access points trusted for your own networks. */
@Composable
private fun TrustedApsSection() {
    val prefs = app.prefs
    val conn by Collector.wifiConnection.collectAsState()
    var trusted by remember { mutableStateOf(prefs.trustedAps) }
    Text(Texts.tr("Your network's access points", "Access point della tua rete"), style = MaterialTheme.typography.titleMedium)
    Text(
        Texts.tr(
            "The first access point your phone uses for each of your networks is trusted. Any other one raises an alert until you confirm it here (mesh nodes and extenders included, once each): an unknown one may be an evil twin that got your phone.",
            "Il primo access point usato dal telefono per ogni tua rete è fidato. Ogni altro fa scattare un'allerta finché non lo confermi qui (anche nodi mesh e ripetitori, una volta ciascuno): uno sconosciuto potrebbe essere un evil twin che ha agganciato il telefono.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    conn?.let { c ->
        Text(
            Texts.tr("Connected: ", "Connesso: ") + "${c.ssid} · ${c.bssid}" +
                if (!c.own) "" else if (c.trusted) Texts.tr(" · trusted", " · fidato") else Texts.tr(" · UNKNOWN", " · SCONOSCIUTO"),
            style = MaterialTheme.typography.bodySmall,
            color = if (c.own && !c.trusted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (c.own && !c.trusted) {
            OutlinedButton(onClick = {
                prefs.trustedAps = prefs.trustedAps + "${c.ssid}|${c.bssid}"
                trusted = prefs.trustedAps
                Collector.wifiConnection.value = c.copy(trusted = true)
            }) { Text(Texts.tr("It's mine: trust this access point", "È mio: fidati di questo access point")) }
        }
    }
    if (trusted.isNotEmpty()) {
        Text(trusted.sorted().joinToString("\n") { "• " + it.replace("|", " · ") }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { prefs.trustedAps = emptySet(); trusted = emptySet() }) {
            Text(Texts.tr("Forget trusted access points", "Dimentica gli access point fidati"))
        }
    }
}


/** Ground truth: what you say about a device trains nothing automatically, but makes tuning possible. */
@Composable
private fun FeedbackRowUi(r: EntityReport, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dao = app.db.dao()
    fun send(label: Int) = scope.launch {
        // One row per merged id, so the verdict still applies if the merge changes later.
        for (m in r.memberIds) {
            dao.addFeedback(
                dev.retrovision.app.data.FeedbackRow(
                    entityId = m, label = label, score = r.score,
                    reasons = r.reasons.joinToString(",") { it::class.simpleName ?: "?" }, timeMs = System.currentTimeMillis(),
                ),
            )
            if (label == 1) dao.addIgnore(IgnoreRow(m, Texts.entityLabel(r), System.currentTimeMillis()))
        }
        Collector.analyzeNow.value = System.nanoTime()
        onClose()
    }
    Text(Texts.tr("Your verdict (helps tune the thresholds):", "Il tuo giudizio (serve a tarare le soglie):"), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { send(0) }) { Text(Texts.tr("False alarm", "Falso allarme")) }
        OutlinedButton(onClick = { send(2) }) { Text(Texts.tr("Suspicious", "Sospetto")) }
    }
    Text(
        Texts.tr("False alarm silences its alerts for 24 h. “Ignore (mine)” below records it as yours.", "Falso allarme silenzia le sue allerte per 24 h. “Ignora (è mio)” qui sotto lo registra come tuo."),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Teach the app which probe requests are your own phone's, so "asked for your network" ignores it. */
@Composable
private fun OwnPhoneSection() {
    val prefs = app.prefs
    val ctx = LocalContext.current
    val got by Collector.calibrated.collectAsState()
    var saved by remember { mutableStateOf(prefs.ownFingerprints) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text(Texts.tr("Your phone's Wi-Fi fingerprint", "Impronta Wi-Fi del tuo telefono"), style = MaterialTheme.typography.titleMedium)
    Text(
        Texts.tr(
            "Your own phone asks for your networks by name too. With the probe connected and close, tap below: the phone runs a Wi-Fi scan and the loudest probe requests are recorded as yours. Same-model phones share the fingerprint, so theirs won't trigger \"asked for your network\" either.",
            "Anche il tuo telefono cerca le tue reti per nome. Con la sonda collegata e vicina, tocca qui sotto: il telefono fa una scansione Wi-Fi e le probe request più forti vengono registrate come tue. I telefoni dello stesso modello hanno la stessa impronta, quindi anche i loro non faranno scattare \"ha cercato la tua rete\".",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !running, onClick = {
            running = true
            Collector.calibrated.value = emptySet()
            Collector.calibrateUntilMs = System.currentTimeMillis() + 8_000L
            @Suppress("DEPRECATION")
            runCatching { ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.startScan() }
            scope.launch {
                kotlinx.coroutines.delay(8_500)
                running = false
                val fps = Collector.calibrated.value
                if (fps.isNotEmpty()) { prefs.ownFingerprints = prefs.ownFingerprints + fps; saved = prefs.ownFingerprints }
            }
        }) { Text(if (running) Texts.tr("Listening…", "In ascolto…") else Texts.tr("Identify my phone", "Riconosci il mio telefono")) }
        if (saved.isNotEmpty()) TextButton(onClick = { prefs.ownFingerprints = emptySet(); saved = emptySet() }) { Text(Texts.tr("Forget", "Dimentica")) }
    }
    Text(
        when {
            running -> Texts.tr("Heard ${got.size} so far…", "Sentite ${got.size} finora…")
            saved.isEmpty() -> Texts.tr("Not identified yet.", "Non ancora riconosciuto.")
            else -> Texts.tr("${saved.size} fingerprint(s) saved.", "${saved.size} impronte salvate.")
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Counts of your verdicts and which reasons show up most in false alarms: where tuning should start. */
@Composable
private fun FeedbackStatsSection() {
    val list by remember { app.db.dao().feedback() }.collectAsState(initial = emptyList())
    if (list.isEmpty()) return
    Text(Texts.tr("Your verdicts", "I tuoi giudizi"), style = MaterialTheme.typography.titleMedium)
    // A verdict on a merged device is stored once per member id: count each verdict once.
    val verdicts = list.distinctBy { it.timeMs to it.label }
    val n = IntArray(3)
    verdicts.forEach { if (it.label in 0..2) n[it.label]++ }
    Text(
        Texts.tr("False alarms ${n[0]} · yours ${n[1]} · suspicious ${n[2]}", "Falsi allarmi ${n[0]} · tuoi ${n[1]} · sospetti ${n[2]}"),
        style = MaterialTheme.typography.bodySmall,
    )
    val top = verdicts.filter { it.label == 0 }.flatMap { it.reasons.split(',') }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
    if (top.isNotEmpty()) {
        Text(
            Texts.tr("Most common reasons in false alarms: ", "Motivi più frequenti nei falsi allarmi: ") +
                top.joinToString { "${it.key} (${it.value})" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


/** One line per receiver; the full technical cards open on tap. */
@Composable
private fun SensorsSummary(expanded: Boolean, onToggle: () -> Unit) {
    val conn by Collector.connection.collectAsState()
    val bleOn by Collector.phoneBleActive.collectAsState()
    val fix by Collector.location.collectAsState()
    val running by Collector.running.collectAsState()
    Card(Modifier.fillMaxWidth().clickable { onToggle() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Texts.tr("Sensors", "Sensori"), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) Texts.tr("Hide details ▴", "Nascondi dettagli ▴") else Texts.tr("Details ▾", "Dettagli ▾"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            val s = conn.session
            if (s != null) {
                val h = probeHealth(s)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(h.dot, color = h.color)
                    Text(Texts.tr("Probe: ", "Sonda: ") + h.text, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(Texts.tr("Probe: ", "Sonda: ") + linkText(conn.link, "", conn.error), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                Texts.tr("Phone Bluetooth: ", "Bluetooth del telefono: ") + if (bleOn) Texts.tr("listening", "in ascolto") else Texts.tr("off", "spento"),
                style = MaterialTheme.typography.bodyMedium,
            )
            val maxAcc = app.prefs.maxFixAccuracyM
            Text(
                "GPS: " + when {
                    !running -> Texts.tr("off", "spento")
                    fix == null -> Texts.tr("no fix yet", "nessuna posizione")
                    fix!!.accuracyM > maxAcc -> Texts.tr("imprecise (±${fix!!.accuracyM.toInt()} m), places paused", "impreciso (±${fix!!.accuracyM.toInt()} m), luoghi in pausa")
                    else -> Texts.tr("good (±${fix!!.accuracyM.toInt()} m)", "buono (±${fix!!.accuracyM.toInt()} m)")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
