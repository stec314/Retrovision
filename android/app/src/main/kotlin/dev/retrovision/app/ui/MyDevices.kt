// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.IgnoreRow
import dev.retrovision.core.identity.MacTrust
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A nearby device that could be one of yours, as heard by the probe or the phone. */
private class Candidate(
    val entityId: String,
    val memberIds: Set<String>,
    val label: String,
    val category: String,
    val rssi: Int,
    val paired: String?,
    val rotating: Boolean,
)

/** A Wi-Fi network around you, from the phone's own scan. */
private class NetCandidate(val ssid: String, val bssids: Set<String>, val level: Int, val connected: Boolean)

/**
 * "Add my devices": lists what is heard right now, nearest first, so the user only has to tick
 * their own things. Devices paired with this phone are marked. Nothing is sent anywhere; the
 * selection becomes ignored devices, own network names and trusted access points.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
fun MyDevicesScanDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = RetrovisionApp.instance
    val scope = rememberCoroutineScope()
    val frame by Collector.liveRadar.collectAsState()
    val analysis by Collector.analysis.collectAsState()
    val ignores by remember { app.db.dao().ignores() }.collectAsState(initial = emptyList())
    val conn by Collector.wifiConnection.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var frozen by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    val picked = remember { mutableStateMapOf<String, String>() } // entityId -> name
    val pickedNets = remember { mutableStateMapOf<String, NetCandidate>() }
    var nets by remember { mutableStateOf<List<NetCandidate>>(emptyList()) }
    var saved by remember { mutableStateOf<String?>(null) }

    // Phone's paired devices: lowercase address -> name.
    val paired = remember {
        runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bondedDevices
                ?.associate { it.address.lowercase() to (runCatching { it.name }.getOrNull() ?: it.address) }
        }.getOrNull().orEmpty()
    }

    // The live list refreshes until the user ticks something (rows would jump under the finger).
    LaunchedEffect(frame, analysis, frozen) {
        if (frozen) return@LaunchedEffect
        val reports = analysis?.entities.orEmpty().associateBy { it.entityId }
        snapshot = frame.blips.map { b ->
            val rep = reports[b.entityId]
            val addrs = rep?.addresses?.map { it.toString() }.orEmpty() + b.entityId.substringAfter(':')
            Candidate(
                entityId = b.entityId,
                memberIds = rep?.memberIds ?: setOf(b.entityId),
                label = b.label,
                category = CategoryUi.icon(b.category) + " " + CategoryUi.label(b.category),
                rssi = b.rssi.toInt(),
                paired = addrs.firstNotNullOfOrNull { paired[it.lowercase()] },
                rotating = rep != null && rep.macTrust != MacTrust.STABLE,
            )
        }.sortedWith(compareByDescending<Candidate> { it.paired != null }.thenByDescending { it.rssi })
    }

    // Wi-Fi networks from the phone's scan (refreshed every 10 s while the tab is open).
    LaunchedEffect(tab) {
        if (tab != 1) return@LaunchedEffect
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return@LaunchedEffect
        while (true) {
            runCatching { wm.startScan() }
            delay(2_500)
            val list = runCatching { wm.scanResults }.getOrNull().orEmpty()
            nets = list.filter { !it.SSID.isNullOrBlank() }
                .groupBy { it.SSID }
                .map { (ssid, rs) ->
                    NetCandidate(ssid, rs.map { it.BSSID.lowercase() }.toSet(), rs.maxOf { it.level }, conn?.ssid == ssid)
                }
                .sortedWith(compareByDescending<NetCandidate> { it.connected }.thenByDescending { it.level })
            delay(10_000)
        }
    }

    val ignoredIds = ignores.map { it.entityId }.toSet()
    val ownNow = app.prefs.ownSsidSet()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Texts.tr("Add my devices", "Aggiungi i miei dispositivi"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    Texts.tr(
                        "Switch on your own phone, watch, earbuds, car or tags and keep them close: the strongest signals come first. Tick yours. They stop counting as suspicious.",
                        "Accendi telefono, orologio, auricolari, auto o tag e tienili vicini: i segnali più forti sono in cima. Spunta i tuoi. Smetteranno di contare come sospetti.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(Texts.tr("Devices", "Dispositivi") + if (picked.isNotEmpty()) " · ${picked.size}" else "") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(Texts.tr("My Wi-Fi networks", "Le mie reti Wi-Fi") + if (pickedNets.isNotEmpty()) " · ${pickedNets.size}" else "") })
                }

                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (frozen) Texts.tr("List paused while you choose", "Lista ferma mentre scegli")
                            else Texts.tr("Listening… ${snapshot.size} heard in the last 20 s", "In ascolto… ${snapshot.size} sentiti negli ultimi 20 s"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { frozen = !frozen }) { Text(if (frozen) Texts.tr("Refresh", "Aggiorna") else Texts.tr("Pause", "Ferma")) }
                    }
                    if (snapshot.isEmpty()) Text(
                        Texts.tr(
                            "Nothing heard yet. Start collecting (probe connected, or phone Bluetooth on) and wait a few seconds.",
                            "Ancora niente. Avvia la raccolta (sonda collegata o Bluetooth del telefono attivo) e attendi qualche secondo.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(snapshot, key = { it.entityId }) { c ->
                            val already = c.entityId in ignoredIds
                            val on = already || c.entityId in picked
                            Column(
                                Modifier.fillMaxWidth().clickable(enabled = !already) {
                                    frozen = true
                                    if (c.entityId in picked) picked.remove(c.entityId) else picked[c.entityId] = c.paired ?: c.label
                                }.padding(vertical = 4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = on, enabled = !already, onCheckedChange = null)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(c.paired ?: c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(
                                            c.category + " · ${c.rssi} dBm · " + proximity(c.rssi) +
                                                (if (c.paired != null) " · " + Texts.tr("paired with this phone", "associato a questo telefono") else "") +
                                                (if (already) " · " + Texts.tr("already yours", "già tuo") else ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (c.paired != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (c.rotating) Text(
                                            Texts.tr(
                                                "Changes address: recognised only until its next change.",
                                                "Cambia indirizzo: riconosciuto solo fino al prossimo cambio.",
                                            ),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                if (c.entityId in picked) {
                                    OutlinedTextField(
                                        value = picked[c.entityId].orEmpty(),
                                        onValueChange = { picked[c.entityId] = it },
                                        singleLine = true,
                                        label = { Text(Texts.tr("Name (e.g. my watch)", "Nome (es. il mio orologio)")) },
                                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        Texts.tr(
                            "Tick the networks that are yours (home, office, your hotspot). Their names stop counting as \"asked for your network\" noise and their access points become trusted: a different one with the same name will raise an alert.",
                            "Spunta le reti tue (casa, ufficio, il tuo hotspot). I loro nomi smettono di generare rumore e i loro access point diventano fidati: uno diverso con lo stesso nome farà scattare un'allerta.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (nets.isEmpty()) Text(Texts.tr("Scanning…", "Scansione…"), style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(nets, key = { it.ssid }) { n ->
                            val already = n.ssid in ownNow
                            val on = already || n.ssid in pickedNets
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !already) {
                                    if (n.ssid in pickedNets) pickedNets.remove(n.ssid) else pickedNets[n.ssid] = n
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, enabled = !already, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(n.ssid, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        "${n.bssids.size} " + Texts.tr("access point(s)", "access point") + " · ${n.level} dBm" +
                                            (if (n.connected) " · " + Texts.tr("connected now", "connesso ora") else "") +
                                            (if (already) " · " + Texts.tr("already yours", "già tua") else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (n.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                saved?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = picked.isNotEmpty() || pickedNets.isNotEmpty(),
                        onClick = {
                            val devs = picked.toMap()
                            val netsSel = pickedNets.values.toList()
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val dao = app.db.dao()
                                devs.forEach { (id, name) ->
                                    val c = snapshot.firstOrNull { it.entityId == id }
                                    // Every merged address of the device, so a later re-merge still matches.
                                    (c?.memberIds ?: setOf(id)).forEach { m -> dao.addIgnore(IgnoreRow(m, name.ifBlank { c?.label ?: id }, now)) }
                                }
                                if (netsSel.isNotEmpty()) {
                                    val names = (app.prefs.ownSsidSet() + netsSel.map { it.ssid }).toSortedSet()
                                    app.prefs.ownSsids = names.joinToString(", ")
                                    app.prefs.trustedAps = app.prefs.trustedAps + netsSel.flatMap { n -> n.bssids.map { "${n.ssid}|$it" } }
                                }
                                Collector.analyzeNow.value = System.nanoTime()
                                saved = Texts.tr(
                                    "Saved: ${devs.size} device(s), ${netsSel.size} network(s).",
                                    "Salvati: ${devs.size} dispositivi, ${netsSel.size} reti.",
                                )
                                picked.clear(); pickedNets.clear(); frozen = false
                            }
                        },
                    ) { Text(Texts.tr("Save as mine", "Salva come miei")) }
                    OutlinedButton(onClick = onClose) { Text(Texts.tr("Done", "Fatto")) }
                }
            }
        }
    }
}

private fun proximity(rssi: Int) = when {
    rssi >= -55 -> Texts.tr("very close", "vicinissimo")
    rssi >= -70 -> Texts.tr("near", "vicino")
    else -> Texts.tr("far", "lontano")
}
