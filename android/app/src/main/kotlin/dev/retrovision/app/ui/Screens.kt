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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val count by remember { app.db.dao().sightingCount() }.collectAsState(initial = 0L)

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
        Button(
            onClick = {
                if (running) CollectorService.stop(ctx) else {
                    val req = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(req.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) Texts.tr("Stop collecting", "Ferma la raccolta") else Texts.tr("Start collecting", "Avvia la raccolta")) }

        val radar by Collector.liveRadar.collectAsState()
        if (running && radar.blips.isNotEmpty()) {
            Text(Texts.tr("Radar", "Radar"), style = MaterialTheme.typography.titleMedium)
            RadarView()
        }

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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("GPS", style = MaterialTheme.typography.titleMedium)
                val f = fix
                Text(
                    if (f == null) Texts.tr("No fix yet", "Nessun fix")
                    else "%.5f, %.5f (±%.0f m)".format(f.lat, f.lon, f.accuracyM),
                )
                Text(Texts.tr("Stored sightings: ", "Avvistamenti salvati: ") + count)
            }
        }
        val alerts = analysis?.alerts.orEmpty()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Texts.tr("Alerts", "Allerte"), style = MaterialTheme.typography.titleMedium)
                if (alerts.isEmpty()) Text(Texts.tr("Nothing suspicious in the analysed window.", "Niente di sospetto nella finestra analizzata."))
                alerts.take(5).forEach { Text("• ${Texts.entityLabel(it)}  ${"%.0f".format(it.score * 100)}%") }
            }
        }

        val threats by Collector.threats.collectAsState()
        if (threats.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠ " + Texts.tr("Wi-Fi attacks nearby", "Attacchi Wi-Fi nelle vicinanze"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    threats.take(5).forEach {
                        Text("• " + Texts.threat(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
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
                Text("%.0f%%".format(r.score * 100) + " · ${r.placeIds.size} ${Texts.tr("places", "luoghi")}")
                Text(Texts.tr("First seen ", "Primo avvistamento ") + fmt.format(Date(r.firstSeenMs)), style = MaterialTheme.typography.bodySmall)
                Text(Texts.tr("Last seen ", "Ultimo avvistamento ") + fmt.format(Date(r.lastSeenMs)), style = MaterialTheme.typography.bodySmall)
                r.reasons.forEach { Text("• " + Texts.reason(it), style = MaterialTheme.typography.bodySmall) }
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
                output.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    app.db.dao().addIgnore(IgnoreRow(r.entityId, Texts.entityLabel(r), System.currentTimeMillis()))
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
                        Text("• ${img.chip.label} · ${img.data.size / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        Texts.tr(
                            "The board is detected automatically: XIAO ESP32-S3, or classic ESP32 (NodeMCU-32S, DevKitC).",
                            "La scheda viene riconosciuta da sola: XIAO ESP32-S3 oppure ESP32 classica (NodeMCU-32S, DevKitC).",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            text = { Text(Texts.tr("This overwrites the firmware on the connected board.", "Sovrascrive il firmware della scheda collegata.")) },
            confirmButton = { TextButton(onClick = { FlashRunner.start(ctx, images); confirmAll = false }) { Text(Texts.tr("Flash", "Flasha")) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}

// ---------------------------------------------------------------- Settings

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
