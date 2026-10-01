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

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Texts.tr("Probe", "Sonda"), style = MaterialTheme.typography.titleMedium)
                Text(linkText(conn.link, conn.device, conn.error))
                conn.session?.let { s ->
                    s.info?.let { Text("${it.probeType} · fw ${it.firmware} · proto ${it.protocol}") }
                    Text(phaseText(s.phase) + (if (s.clockUncertaintyUs >= 0) " · ±${s.clockUncertaintyUs} µs" else ""))
                    if (s.rejectReason.isNotEmpty()) Text(s.rejectReason, color = MaterialTheme.colorScheme.error)
                    Text("Wi-Fi ${s.wifiObs} · BLE ${s.bleObs} · ${Texts.tr("lost", "persi")} ${s.lostFrames + s.probeDropped} · CRC ${s.badFrames}")
                    if (s.channel > 0) Text("ch ${s.channel} · ${s.freeHeap / 1024} KiB free · ${"%.0f".format(s.chipTempC)} °C")
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
fun DevicesScreen(modifier: Modifier) {
    val analysis by Collector.analysis.collectAsState()
    var selected by remember { mutableStateOf<EntityReport?>(null) }
    val list = analysis?.entities.orEmpty()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Texts.tr("Devices", "Dispositivi") + " (${list.size})", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { Collector.analyzeNow.value = System.nanoTime() }) { Text(Texts.tr("Analyse now", "Analizza ora")) }
        }
        if (list.isEmpty()) Text(Texts.tr("Nothing analysed yet. Start collecting and wait a minute.", "Ancora nulla. Avvia la raccolta e attendi un minuto."))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.take(200), key = { it.entityId }) { r ->
                EntityCard(r) { selected = r }
            }
        }
    }
    selected?.let { DeviceDialog(it) { selected = null } }
}

@Composable
fun EntityCard(r: EntityReport, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Texts.entityLabel(r), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("%.0f%%".format(r.score * 100), color = if (r.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            LinearProgressIndicator(progress = { r.score.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(
                "${r.placeIds.size} ${Texts.tr("places", "luoghi")} · ${r.sightings} ${Texts.tr("sightings", "avvistamenti")} · ${r.maxRssi} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
            r.reasons.forEach { Text("• " + Texts.reason(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DeviceDialog(r: EntityReport, onClose: () -> Unit) {
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
                if (r.addresses.size > 1) Text(r.addresses.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
    var confirm by remember { mutableStateOf<dev.retrovision.app.probe.FirmwareImage?>(null) }

    val view = LocalView.current
    DisposableEffect(flash.running) {
        view.keepScreenOn = flash.running
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Texts.tr("Probe firmware", "Firmware della sonda"), style = MaterialTheme.typography.headlineSmall)
        Text(linkText(conn.link, conn.device, conn.error))
        conn.session?.info?.let { Text("${Texts.tr("Installed", "Installato")}: ${it.firmware} (${it.probeType})") }

        if (images.isEmpty()) {
            Text(
                Texts.tr(
                    "This build has no firmware bundled. Use the release APK from GitHub, or the web flasher.",
                    "Questa build non include il firmware. Usa l'APK di release da GitHub o il flasher web.",
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        images.forEach { img ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${img.chip.label} · ${img.version}", style = MaterialTheme.typography.titleMedium)
                    Text("${img.data.size / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !flash.running, onClick = { confirm = img }) { Text(Texts.tr("Flash this firmware", "Flasha questo firmware")) }
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

    confirm?.let { img ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(Texts.tr("Flash ${img.chip.label}?", "Flashare ${img.chip.label}?")) },
            text = { Text(Texts.tr("This overwrites the firmware on the connected board.", "Sovrascrive il firmware della scheda collegata.")) },
            confirmButton = { TextButton(onClick = { FlashRunner.start(ctx, img); confirm = null }) { Text(Texts.tr("Flash", "Flasha")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(Texts.tr("Cancel", "Annulla")) } },
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
    var own by remember { mutableStateOf(prefs.ownSsids) }
    var wName by remember { mutableStateOf(prefs.wigleName) }
    var wToken by remember { mutableStateOf(prefs.wigleToken) }
    var beacon by remember { mutableStateOf(prefs.beaconDbEnabled) }
    var wipe by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Texts.tr("Settings", "Impostazioni"), style = MaterialTheme.typography.headlineSmall)

        Text(Texts.tr("Alert when score ≥ ", "Allerta con punteggio ≥ ") + "%.0f%%".format(alertScore * 100))
        Slider(value = alertScore, onValueChange = { alertScore = it }, onValueChangeFinished = { prefs.alertScore = alertScore }, valueRange = 0.3f..0.95f)
        Text(Texts.tr("…and seen at ≥ $minPlaces places", "…e visto in ≥ $minPlaces luoghi"))
        Slider(value = minPlaces.toFloat(), onValueChange = { minPlaces = it.toInt() }, onValueChangeFinished = { prefs.alertMinPlaces = minPlaces }, valueRange = 2f..6f, steps = 3)
        Text(Texts.tr("Analysis window: $lookback min", "Finestra di analisi: $lookback min"))
        Slider(value = lookback.toFloat(), onValueChange = { lookback = (it / 15).toInt() * 15 }, onValueChangeFinished = { prefs.lookbackMin = lookback }, valueRange = 30f..720f)
        Text(Texts.tr("Keep data for $retention days", "Conserva i dati per $retention giorni"))
        Slider(value = retention.toFloat(), onValueChange = { retention = it.toInt() }, onValueChangeFinished = { prefs.retentionDays = retention }, valueRange = 1f..30f)

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
                        dao.wipeSightings(); dao.wipeFixes(); dao.wipeEnrichments(); dao.wipeFamiliar()
                        Collector.analysis.value = null
                        wipe = false
                    }
                }) { Text(Texts.tr("Delete", "Elimina")) }
            },
            dismissButton = { TextButton(onClick = { wipe = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}
