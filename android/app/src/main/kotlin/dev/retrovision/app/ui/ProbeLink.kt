// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.retrovision.app.BleLinkUi
import dev.retrovision.app.BleStage
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.probe.Phase
import dev.retrovision.app.probe.ProbeInfo
import dev.retrovision.app.probe.SessionState
import dev.retrovision.proto.v1.LinkKind

private val app get() = RetrovisionApp.instance

fun bleStageText(s: BleStage): String = when (s) {
    BleStage.OFF -> Texts.tr("Bluetooth link not paired", "Collegamento Bluetooth non abbinato")
    BleStage.CABLE -> Texts.tr("on the cable (Bluetooth stands by)", "sul cavo (Bluetooth in attesa)")
    BleStage.STOPPED -> Texts.tr("collection stopped: start it to connect over Bluetooth", "raccolta ferma: avviala per collegarti via Bluetooth")
    BleStage.NO_PERMISSION -> Texts.tr("Bluetooth permission missing", "manca il permesso Bluetooth")
    BleStage.BT_OFF -> Texts.tr("phone Bluetooth is off", "Bluetooth del telefono spento")
    BleStage.SCANNING -> Texts.tr("looking for the probe over Bluetooth…", "cerco la sonda via Bluetooth…")
    BleStage.BONDING -> Texts.tr("Bluetooth pairing… accept the prompt", "abbinamento Bluetooth… accetta la richiesta")
    BleStage.CONNECTING -> Texts.tr("connecting over Bluetooth…", "connessione via Bluetooth…")
    BleStage.HANDSHAKE -> Texts.tr("connected, authenticating…", "connesso, autenticazione…")
    BleStage.STREAMING -> Texts.tr("streaming over Bluetooth", "ricezione via Bluetooth")
    BleStage.REJECTED -> Texts.tr("refused: pairing key mismatch", "rifiutata: chiave di abbinamento diversa")
    BleStage.RETRY_WAIT -> Texts.tr("not reached, retrying", "non raggiunta, riprovo")
    BleStage.PAUSED -> Texts.tr("paused: not connecting until you resume it", "in pausa: non si collega finché non la riprendi")
}

private fun linkName(k: LinkKind) = when (k) {
    LinkKind.LINK_KIND_BLE -> "Bluetooth"
    LinkKind.LINK_KIND_RELAY -> Texts.tr("Bluetooth via relay", "Bluetooth via ponte")
    LinkKind.LINK_KIND_WIFI -> "Wi-Fi"
    else -> "USB"
}

/** A probe set up for a wireless link that this phone authenticates (own BLE, or through a relay). */
internal fun isWireless(k: LinkKind?) = k == LinkKind.LINK_KIND_BLE || k == LinkKind.LINK_KIND_RELAY

/** Reads the relay board's report ("rssi=-61 mtu=247 fwd=120 drop=0 bad=0 conns=2 disc=8 probe=0s heap=210"). */
internal fun relayReportText(r: String): Pair<String, Boolean> {
    val kv = r.split(' ').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
    val parts = mutableListOf<String>()
    val fwd = kv["fwd"]?.toLongOrNull() ?: 0
    val drop = kv["drop"]?.toLongOrNull() ?: 0
    val quiet = kv["probe"]?.removeSuffix("s")?.toLongOrNull() ?: 0
    kv["rssi"]?.toIntOrNull()?.takeIf { it != 0 }?.let { parts += Texts.tr("relay hears the phone at $it dBm", "il ponte sente il telefono a $it dBm") }
    if (drop > 0) parts += Texts.tr("relay dropped $drop of ${fwd + drop} frames", "il ponte ha scartato $drop frame su ${fwd + drop}")
    if (quiet >= 10) parts += Texts.tr("nothing from the probe for $quiet s (wires or probe power?)", "nulla dalla sonda da $quiet s (fili o alimentazione della sonda?)")
    val bad = quiet >= 10 || (drop > 0 && drop * 20 > fwd + drop)
    return parts.joinToString(" · ").ifEmpty { Texts.tr("relay OK", "ponte OK") } to bad
}

/** Reads the probe's BLE self-report ("st=adv rc=0 name=RV-x at=0 conns=0 disc=-1 heap=a/b/c"). */
internal fun bleReportText(r: String): Pair<String, Boolean> {
    val kv = r.split(' ').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
    val st = kv["st"].orEmpty()
    val heap = kv["heap"]?.split('/')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    val low = heap.size == 3 && heap[2] < 16
    val parts = mutableListOf<String>()
    val bad = when {
        st == "adv" -> { parts += Texts.tr("advertising as ${kv["name"]}", "in advertising come ${kv["name"]}"); false }
        st == "connected" -> { parts += Texts.tr("a phone is connected", "un telefono è connesso"); false }
        st == "nosync" -> { parts += Texts.tr("the Bluetooth stack did not start", "lo stack Bluetooth non è partito"); true }
        st.startsWith("fail-") -> { parts += Texts.tr("advertising fails at “${st.removePrefix("fail-")}” (code ${kv["rc"]})", "l'advertising fallisce in “${st.removePrefix("fail-")}” (codice ${kv["rc"]})"); true }
        else -> { parts += st; true }
    }
    kv["conns"]?.toIntOrNull()?.takeIf { it > 0 }?.let { parts += Texts.tr("$it connections since boot", "$it connessioni dall'avvio") }
    // Why the probe last restarted: a crash or a power dip explains a link that drops for no reason.
    val boot = kv["boot"].orEmpty()
    val badBoot = boot in setOf("panic", "intwdt", "taskwdt", "wdt", "brownout", "pwrglitch", "cpulock")
    when (boot) {
        "brownout", "pwrglitch" -> parts += Texts.tr("last restart: power dip (weak power bank or cable?)", "ultimo riavvio: calo di alimentazione (power bank o cavo deboli?)")
        "panic", "cpulock" -> parts += Texts.tr("last restart: firmware crash", "ultimo riavvio: crash del firmware")
        "intwdt", "taskwdt", "wdt" -> parts += Texts.tr("last restart: watchdog (firmware stuck)", "ultimo riavvio: watchdog (firmware bloccato)")
        "", "poweron", "sw", "usb", "jtag", "ext", "unknown" -> Unit
        else -> parts += Texts.tr("last restart: $boot", "ultimo riavvio: $boot")
    }
    if (heap.size == 3) parts += Texts.tr("free memory ${heap[1]} KiB (lowest ${heap[2]})", "memoria libera ${heap[1]} KiB (minimo ${heap[2]})")
    return parts.joinToString(" · ") to (bad || low || badBoot)
}

/** One line for the probe card: which transport the session runs on and its signal. */
internal fun linkLine(i: ProbeInfo, s: SessionState): String =
    Texts.tr("link ", "collegamento ") + linkName(i.link) +
        (if (i.configuredLink != i.link) Texts.tr(" (set up for ", " (impostata per ") + linkName(i.configuredLink) + ")" else "") +
        (if (i.link == LinkKind.LINK_KIND_BLE && s.linkRssi != 0) " · ${s.linkRssi} dBm" else "") +
        (if (i.link == LinkKind.LINK_KIND_RELAY) s.relayReport.split(' ').firstOrNull { it.startsWith("rssi=") && it != "rssi=0" }?.let { " · ${it.removePrefix("rssi=")} dBm" }.orEmpty() else "")

private fun ago(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 90 -> "${s}s"
        s < 5400 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}

private const val MAX_PROBES = 4

/** One paired wireless probe: live status, telemetry over the air, errors, forget. */
@Composable
private fun PairedProbeRow(name: String, ui: BleLinkUi?, session: SessionState?, running: Boolean, onForget: () -> Unit) {
    val stage = if (!running) BleStage.STOPPED else ui?.stage ?: BleStage.SCANNING
    val (dot, color) = when (stage) {
        BleStage.STREAMING -> "●" to MaterialTheme.colorScheme.primary
        BleStage.REJECTED, BleStage.NO_PERMISSION, BleStage.BT_OFF -> "●" to MaterialTheme.colorScheme.error
        BleStage.OFF, BleStage.CABLE, BleStage.STOPPED, BleStage.PAUSED -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "◐" to MaterialTheme.colorScheme.tertiary
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dot, color = color)
            Text("RV-$name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onForget) { Text(Texts.tr("Forget", "Dimentica")) }
        }
        Text(bleStageText(stage), style = MaterialTheme.typography.bodySmall)
        if (ui != null && running) {
            val parts = buildList {
                if (stage != BleStage.CABLE) add(Texts.tr("for ", "da ") + ago(ui.sinceMs))
                if (ui.attempts > 0) add(Texts.tr("attempts ", "tentativi ") + ui.attempts)
                if (ui.rssi != 0) add(Texts.tr("phone hears ", "il telefono sente ") + "${ui.rssi} dBm")
                if (stage == BleStage.STREAMING && session != null) {
                    if (session.linkRssi != 0) add(Texts.tr("probe hears ", "la sonda sente ") + "${session.linkRssi} dBm")
                    if (ui.mtu > 0) add("MTU ${ui.mtu}")
                }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stage == BleStage.STREAMING && session != null) {
                val lost = session.lostFrames + session.probeDropped
                val seen = session.wifiObs + session.bleObs
                Text(
                    (session.info?.let { probeModel(it.probeType) + " · " } ?: "") +
                        "Wi-Fi ${session.wifiObs}" + (if (session.channel > 0) " · ch ${session.channel}" else "") +
                        (if (session.bleObs > 0) " · BLE ${session.bleObs}" else "") +
                        " · " + Texts.tr("lost", "persi") + " $lost" + (if (seen + lost > 0) " (%.1f%%)".format(100.0 * lost / (seen + lost)) else "") +
                        (if (session.chipTempC != 0f) " · ${"%.0f".format(session.chipTempC)} °C" else "") +
                        (if (session.freeHeap > 0) " · " + Texts.tr("memory ", "memoria ") + "${session.freeHeap / 1024}/${session.minFreeHeap / 1024} KiB" else "") +
                        (if (ui.connectedSinceMs > 0) " · " + Texts.tr("up ", "attivo da ") + ago(ui.connectedSinceMs) else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (lost > 0 && seen > 0 && lost * 20 > seen) Text(
                    Texts.tr("Over 5% lost: Bluetooth cannot keep up here (busy area or weak signal).", "Oltre il 5% perso: il Bluetooth non regge qui (zona affollata o segnale debole)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                if (session.bleReport.isNotEmpty()) {
                    val (txt, bad) = bleReportText(session.bleReport)
                    Text(txt, style = MaterialTheme.typography.labelSmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (session.relayReport.isNotEmpty()) {
                    val (txt, bad) = relayReportText(session.relayReport)
                    Text(txt, style = MaterialTheme.typography.labelSmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.lastError.isNotEmpty() && stage != BleStage.STREAMING && stage != BleStage.CABLE) {
                Text(ui.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Wireless (BLE) probes, in the Probe tab: one row per paired probe with live status and telemetry,
 * pairing of the probe on the cable, recovery actions. Pairing needs the cable: the key is set over
 * USB, so it requires physical access to the probe. Several probes can stream at once.
 */
@Composable
fun ProbeLinkCard() {
    val ctx = LocalContext.current
    val prefs = app.prefs
    val conn by Collector.connection.collectAsState()
    val bleLinks by Collector.bleLinks.collectAsState()
    val links by Collector.links.collectAsState()
    val running by Collector.running.collectAsState()
    var pairs by remember { mutableStateOf(prefs.blePairs().map { it.name }) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteError by remember { mutableStateOf(false) }
    var pendingSeq by remember { mutableIntStateOf(0) }
    var pendingName by remember { mutableStateOf("") }
    var pendingRelay by remember { mutableStateOf(false) }
    var pendingReuse by remember { mutableStateOf(false) }
    var pendingAt by remember { mutableLongStateOf(0L) }
    var confirmForget by remember { mutableStateOf<String?>(null) }
    var split by remember { mutableStateOf(prefs.splitChannels) }
    // Re-read once a second: elapsed times and the session state change continuously.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }

    @Suppress("UNUSED_VARIABLE") val c = conn // recompose with the connection ticks
    val usb = Collector.usbSession
    val usbState = usb?.state?.value
    val info = usbState?.info
    val usbUp = Collector.usbConnected && usbState?.phase == Phase.STREAMING
    val capable = info?.bleLinkCapable == true
    val relayCapable = info?.relayLinkCapable == true

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Collector.bleKick.value = System.nanoTime()
    }
    fun askPerms() {
        if (Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
    }
    fun btSettings() = runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // Pairing outcome: the probe acks the command (then reboots), or refuses it.
    LaunchedEffect(usbState?.lastAck, pendingSeq) {
        val a = usbState?.lastAck ?: return@LaunchedEffect
        if (pendingSeq == 0 || a.commandSeq != pendingSeq) return@LaunchedEffect
        if (a.ok && pendingRelay) {
            note = Texts.tr(
                "Probe accepted, set up for the relay. It reboots now. Wire it to the relay board (probe GPIO23 → relay D7/RX, probe GPIO24 ← relay D6/TX, GND ↔ GND), power both from the power bank and unplug the phone: the relay advertises as “RV-$pendingName” within a few seconds. On the first connection accept the Bluetooth pairing prompt.",
                "La sonda ha accettato, impostata per il ponte. Si riavvia. Collegala alla scheda ponte (GPIO23 sonda → D7/RX ponte, GPIO24 sonda ← D6/TX ponte, GND ↔ GND), alimenta entrambe dal power bank e scollega il telefono: il ponte si annuncia come “RV-$pendingName” in pochi secondi. Alla prima connessione accetta la richiesta di abbinamento Bluetooth.",
            )
            noteError = false
        } else if (a.ok) {
            note = Texts.tr(
                "Probe accepted. It reboots now. Unplug the cable and power it from a power bank (or the car's USB): it should connect over Bluetooth within a minute. On the first connection accept the Bluetooth pairing prompt.",
                "La sonda ha accettato e si riavvia. Scollega il cavo e alimentala con un power bank (o l'USB dell'auto): dovrebbe collegarsi via Bluetooth entro un minuto. Alla prima connessione accetta la richiesta di abbinamento Bluetooth.",
            )
            noteError = false
        } else {
            // A refused switch keeps the existing pairing; a refused new pairing is undone.
            if (!pendingReuse) { prefs.removeBlePair(pendingName); pairs = prefs.blePairs().map { it.name } }
            note = Texts.tr("The probe refused: ", "La sonda ha rifiutato: ") + "${a.result} ${a.message}"
            noteError = true
        }
        pendingSeq = 0
    }
    LaunchedEffect(pendingSeq, tick) {
        if (pendingSeq != 0 && System.currentTimeMillis() - pendingAt > 6000) {
            note = Texts.tr(
                "No answer from the probe. If it does not show up over Bluetooth, update its firmware here and pair again.",
                "Nessuna risposta dalla sonda. Se non compare via Bluetooth, aggiorna il firmware qui sotto e abbina di nuovo.",
            )
            noteError = true
            pendingSeq = 0
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Texts.tr("Wireless probes (Bluetooth) · experimental", "Sonde wireless (Bluetooth) · sperimentale"), style = MaterialTheme.typography.titleMedium)
            if (pairs.isEmpty()) Text(
                Texts.tr("No probe paired for Bluetooth yet.", "Nessuna sonda abbinata per il Bluetooth."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pairs.isNotEmpty() && !running) Text(
                Texts.tr("Start collecting on the Status tab: the Bluetooth links run with the collection.", "Avvia la raccolta dalla scheda Stato: i collegamenti Bluetooth girano insieme alla raccolta."),
                style = MaterialTheme.typography.bodySmall,
            )
            pairs.forEach { n ->
                PairedProbeRow(n, bleLinks[n], links["ble:$n"]?.session?.state?.value, running) { confirmForget = n }
            }
            if (running && pairs.isNotEmpty()) {
                val stages = pairs.mapNotNull { bleLinks[it]?.stage }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        BleStage.NO_PERMISSION in stages -> Button(onClick = { askPerms() }) { Text(Texts.tr("Grant Bluetooth permission", "Concedi permesso Bluetooth")) }
                        BleStage.BT_OFF in stages -> Button(onClick = { btSettings() }) { Text(Texts.tr("Turn Bluetooth on", "Attiva il Bluetooth")) }
                        stages.any { it != BleStage.STREAMING && it != BleStage.CABLE && it != BleStage.PAUSED } ->
                            OutlinedButton(onClick = { Collector.bleKick.value = System.nanoTime() }) { Text(Texts.tr("Retry now", "Riprova ora")) }
                    }
                    if (pairs.any { bleLinks[it]?.lastError?.contains("Bluetooth settings") == true }) TextButton(onClick = { btSettings() }) { Text(Texts.tr("Bluetooth settings", "Impostazioni Bluetooth")) }
                }
                val others = pairs.flatMap { bleLinks[it]?.others?.entries.orEmpty() }.associate { it.key to it.value }
                    .filterKeys { k -> pairs.none { "RV-$it" == k } }
                if (others.isNotEmpty() && pairs.any { bleLinks[it]?.stage != BleStage.STREAMING }) Text(
                    Texts.tr("Other probes heard: ", "Altre sonde sentite: ") + others.entries.take(4).joinToString { "${it.key} (${it.value} dBm)" } +
                        Texts.tr(". If one is yours, it was paired with a different key (e.g. before a reinstall): plug it in and pair again.",
                            ". Se una è tua, è stata abbinata con un'altra chiave (es. prima di una reinstallazione): collegala col cavo e abbina di nuovo."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            // ---- the probe on the cable ----
            Text(Texts.tr("Probe on the cable", "Sonda sul cavo"), style = MaterialTheme.typography.titleSmall)
            if (info == null || !Collector.usbConnected) {
                Text(
                    Texts.tr(
                        "Plug a probe into the phone with the cable to pair it: the key is set over USB, so nobody can pair it from a distance. Pair each probe once; up to $MAX_PROBES stream at the same time.",
                        "Collega una sonda al telefono col cavo per abbinarla: la chiave viene impostata via USB, così nessuno può abbinarla a distanza. Abbina ogni sonda una volta; fino a $MAX_PROBES trasmettono insieme.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val name = info.linkName
                val wireless = isWireless(info.configuredLink)
                val viaRelay = info.configuredLink == LinkKind.LINK_KIND_RELAY
                val pairedHere = wireless && name in pairs
                val needsRepair = wireless && name !in pairs
                // Sends the key with the chosen mode; re-pairing keeps the name (its place in the list).
                fun pair(relay: Boolean, reuseKey: Boolean) {
                    askPerms()
                    val key = (if (reuseKey) prefs.blePairKey(name) else null) ?: java.security.SecureRandom().generateSeed(24)
                    val newName = if ((needsRepair || reuseKey) && name.isNotEmpty()) name else "probe-" + info.hardwareId.takeLast(6)
                    val seq = usb?.setLink(if (relay) LinkKind.LINK_KIND_RELAY else LinkKind.LINK_KIND_BLE, newName, key) ?: 0
                    // Stored now (the ack may race the reboot); removed again if the probe refuses.
                    if (seq == 0) return
                    prefs.putBlePair(newName, key); pairs = prefs.blePairs().map { it.name }
                    pendingSeq = seq; pendingName = newName; pendingAt = System.currentTimeMillis(); pendingRelay = relay; pendingReuse = reuseKey
                    note = Texts.tr("Sending the key to the probe…", "Invio la chiave alla sonda…"); noteError = false
                }
                Text(probeModel(info.probeType) + " · " + info.firmware, style = MaterialTheme.typography.bodySmall)
                when {
                    !capable -> Text(
                        Texts.tr("This firmware has no Bluetooth link. Flash the firmware below, then pair.", "Questo firmware non ha il collegamento Bluetooth. Flasha il firmware qui sotto, poi abbina."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    pairedHere && viaRelay -> Text(
                        Texts.tr(
                            "Paired as “RV-$name” through the relay board: wire the relay, unplug the cable, and the probe keeps scanning Bluetooth while the relay talks to the phone.",
                            "Abbinata come “RV-$name” tramite la scheda ponte: collega il ponte, scollega il cavo, e la sonda continua a scansionare il Bluetooth mentre il ponte parla col telefono.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pairedHere -> Text(
                        Texts.tr("Paired as “RV-$name”: unplug the cable to switch it to Bluetooth.", "Abbinata come “RV-$name”: scollega il cavo per passarla al Bluetooth."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    needsRepair -> Text(
                        Texts.tr(
                            "Set up for " + (if (viaRelay) "the relay" else "Bluetooth") + " as “RV-$name”, but this phone does not hold that key (for example after reinstalling the app). Pair again to replace it.",
                            "Impostata per " + (if (viaRelay) "il ponte" else "il Bluetooth") + " come “RV-$name”, ma questo telefono non ha quella chiave (per esempio dopo aver reinstallato l'app). Abbina di nuovo per sostituirla.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                val r = usbState?.bleReport.orEmpty()
                if (info.configuredLink == LinkKind.LINK_KIND_BLE && r.isNotEmpty()) { // own BLE link only
                    val (txt, bad) = bleReportText(r)
                    Text(Texts.tr("Its Bluetooth: ", "Il suo Bluetooth: ") + txt, style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if ((capable || relayCapable) && !pairedHere) {
                    val full = !needsRepair && pairs.size >= MAX_PROBES
                    if (capable) Button(
                        enabled = usbUp && pendingSeq == 0 && !full,
                        onClick = { pair(relay = needsRepair && viaRelay, reuseKey = false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (needsRepair) Texts.tr("Pair again", "Abbina di nuovo")
                            else if (pairs.isEmpty()) Texts.tr("Pair this probe for Bluetooth", "Abbina questa sonda per il Bluetooth")
                            else Texts.tr("Add this probe (Bluetooth)", "Aggiungi questa sonda (Bluetooth)"),
                        )
                    }
                    if (relayCapable && !needsRepair) OutlinedButton(
                        enabled = usbUp && pendingSeq == 0 && !full,
                        onClick = { pair(relay = true, reuseKey = false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Texts.tr("Pair for a relay board (keeps scanning Bluetooth)", "Abbina per una scheda ponte (continua a scansionare il Bluetooth)")) }
                    if (full) Text(Texts.tr("At most $MAX_PROBES probes: forget one first.", "Al massimo $MAX_PROBES sonde: dimenticane prima una."), style = MaterialTheme.typography.labelSmall)
                }
                // Switch a paired probe between its own Bluetooth and the relay, keeping name and key.
                if (pairedHere && relayCapable && capable) OutlinedButton(
                    enabled = usbUp && pendingSeq == 0,
                    onClick = { pair(relay = !viaRelay, reuseKey = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (viaRelay) Texts.tr("Switch to its own Bluetooth (no relay)", "Passa al suo Bluetooth (senza ponte)")
                        else Texts.tr("Switch to a relay board (keeps scanning Bluetooth)", "Passa a una scheda ponte (continua a scansionare il Bluetooth)"),
                    )
                }
                if (wireless) {
                    OutlinedButton(
                        enabled = usbUp,
                        onClick = {
                            usb?.setLink(LinkKind.LINK_KIND_USB, name, prefs.blePairKey(name) ?: ByteArray(16))
                            prefs.removeBlePair(name); pairs = prefs.blePairs().map { it.name }
                            note = Texts.tr("Wireless off for this probe. It reboots to cable-only.", "Wireless disattivato per questa sonda. Si riavvia solo cavo."); noteError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Texts.tr("Back to cable only", "Torna solo cavo")) }
                }
            }
            note?.let {
                Text(it, color = if (noteError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(Texts.tr("Split Wi-Fi channels between probes", "Dividi i canali Wi-Fi tra le sonde"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Texts.tr(
                            "With two or more probes streaming, each listens to part of the channels and stays longer on each (a C5 takes 5 GHz, the others 2.4 GHz). When one drops out, the others take its channels back.",
                            "Con due o più sonde attive, ognuna ascolta una parte dei canali e resta più a lungo su ciascuno (una C5 prende i 5 GHz, le altre i 2.4 GHz). Quando una si scollega, le altre riprendono i suoi canali.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = split, onCheckedChange = { split = it; prefs.splitChannels = it; Collector.reconfigureAll() })
            }
            Text(
                Texts.tr(
                    "Trade-offs: in Bluetooth mode a probe stops hearing Bluetooth devices (its radio carries the link; the phone's Bluetooth covers them, and less well with the screen off), Wi-Fi keeps working, and it needs power (power bank or car USB). With a relay board (a second XIAO ESP32-S3 wired to the probe's UART, see the wiki) the probe keeps scanning Bluetooth too. A probe left in a parked car only sends while the phone is within Bluetooth range (~10–30 m). Plugging the cable in always takes over.",
                    "Compromessi: in modalità Bluetooth una sonda smette di sentire i dispositivi Bluetooth (la sua radio porta il collegamento; li copre il Bluetooth del telefono, peggio a schermo spento), il Wi-Fi resta attivo e serve alimentazione (power bank o USB dell'auto). Con una scheda ponte (un secondo XIAO ESP32-S3 collegato alla UART della sonda, vedi wiki) la sonda continua a scansionare anche il Bluetooth. Una sonda lasciata nell'auto parcheggiata trasmette solo quando il telefono è nel raggio Bluetooth (~10–30 m). Collegando il cavo, il cavo ha sempre la precedenza.",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirmForget?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text(Texts.tr("Forget RV-$n?", "Dimenticare RV-$n?")) },
            text = {
                Text(
                    Texts.tr(
                        "This phone stops connecting to it. The probe stays in Bluetooth mode with the old key until you plug it in and pair it again or switch it back to cable-only.",
                        "Questo telefono smette di collegarsi. La sonda resta in modalità Bluetooth con la vecchia chiave finché non la colleghi e la abbini di nuovo o la riporti a solo cavo.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { prefs.removeBlePair(n); pairs = prefs.blePairs().map { it.name }; confirmForget = null; Collector.bleKick.value = System.nanoTime() }) {
                    Text(Texts.tr("Forget", "Dimentica"))
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}

/** Asks the app to show a bottom tab (0 Status … 4 Probe); reset to -1 once shown. */
object TabNav {
    val tab = kotlinx.coroutines.flow.MutableStateFlow(-1)
}

/** Pauses or resumes one wireless probe (saved, so it survives a restart of the collection). */
private fun setPaused(name: String, paused: Boolean) {
    val now = if (paused) Collector.blePaused.value + name else Collector.blePaused.value - name
    Collector.blePaused.value = now
    app.prefs.blePaused = now
    Collector.bleKick.value = System.nanoTime()
}

/** Drops the open link of one probe and lets the loop reconnect it straight away. */
private fun reconnect(name: String) {
    Collector.bleReconnect.value = Collector.bleReconnect.value + (name to System.currentTimeMillis())
    Collector.bleKick.value = System.nanoTime()
}

/**
 * Status → wireless probes: where each paired Bluetooth probe stands, whether data is flowing,
 * and the actions you need in the field (pause, resume, reconnect, retry, permission, Bluetooth on).
 * Pairing and forgetting stay on the Probe tab, which needs the cable.
 */
@Composable
fun ProbeStatusPanel() {
    val ctx = LocalContext.current
    val bleLinks by Collector.bleLinks.collectAsState()
    val links by Collector.links.collectAsState()
    val running by Collector.running.collectAsState()
    val paused by Collector.blePaused.collectAsState()
    // Pairings are read from settings; re-read when the link map changes (pair/forget elsewhere).
    val pairs = remember(bleLinks.keys) { app.prefs.blePairs().map { it.name } }
    LaunchedEffect(Unit) { if (Collector.blePaused.value.isEmpty()) Collector.blePaused.value = app.prefs.blePaused }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Collector.bleKick.value = System.nanoTime()
    }
    fun askPerms() {
        if (Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
    }
    fun btSettings() = runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val streaming = pairs.count { bleLinks[it]?.stage == BleStage.STREAMING }
    val silent = pairs.filter { n ->
        val u = bleLinks[n]
        u != null && u.stage == BleStage.STREAMING && u.lastDataMs > 0 &&
            System.currentTimeMillis() - u.lastDataMs > dev.retrovision.app.service.CollectorService.SILENT_PROBE_MS
    }
    val tint = when {
        pairs.isEmpty() -> null
        silent.isNotEmpty() || pairs.any { n -> bleLinks[n]?.stage.let { s -> s == BleStage.REJECTED || s == BleStage.NO_PERMISSION || s == BleStage.BT_OFF } } -> MaterialTheme.colorScheme.error
        else -> null
    }
    Panel(
        title = Texts.tr("Bluetooth probes", "Sonde Bluetooth"),
        tint = tint,
        trailing = {
            if (pairs.isNotEmpty()) Text(
                "$streaming/${pairs.size} " + Texts.tr("streaming", "attive"),
                style = MaterialTheme.typography.labelLarge,
                color = if (running && streaming < pairs.size - paused.count { it in pairs }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (pairs.isEmpty()) {
            Text(
                Texts.tr("No probe paired for Bluetooth. Pairing needs the cable once.", "Nessuna sonda abbinata per il Bluetooth. L'abbinamento richiede il cavo una volta."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { TabNav.tab.value = 4 }) { Text(Texts.tr("Pair a probe ›", "Abbina una sonda ›")) }
            return@Panel
        }
        if (!running) Text(
            Texts.tr("Collection is stopped: start it and the probes connect on their own.", "La raccolta è ferma: avviala e le sonde si collegano da sole."),
            style = MaterialTheme.typography.bodySmall,
        )
        pairs.forEach { n ->
            val ui = bleLinks[n]
            val isPaused = n in paused
            val stage = when {
                !running -> BleStage.STOPPED
                isPaused && ui?.stage != BleStage.CABLE -> BleStage.PAUSED
                else -> ui?.stage ?: BleStage.SCANNING
            }
            val session = links["ble:$n"]?.session?.state?.value
            val (dot, color) = when (stage) {
                BleStage.STREAMING -> if (n in silent) "●" to MaterialTheme.colorScheme.error else "●" to MaterialTheme.colorScheme.primary
                BleStage.REJECTED, BleStage.NO_PERMISSION, BleStage.BT_OFF -> "●" to MaterialTheme.colorScheme.error
                BleStage.OFF, BleStage.CABLE, BleStage.STOPPED, BleStage.PAUSED -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "◐" to MaterialTheme.colorScheme.tertiary
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(dot, color = color)
                    Column(Modifier.weight(1f)) {
                        Text("RV-$n", style = MaterialTheme.typography.bodyMedium)
                        Text(bleStageText(stage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (running && stage != BleStage.CABLE) {
                        when {
                            isPaused -> TextButton(onClick = { setPaused(n, false) }) { Text(Texts.tr("Resume", "Riprendi")) }
                            stage == BleStage.STREAMING || stage == BleStage.HANDSHAKE -> TextButton(onClick = { reconnect(n) }) { Text(Texts.tr("Reconnect", "Riconnetti")) }
                            stage == BleStage.RETRY_WAIT || stage == BleStage.SCANNING || stage == BleStage.REJECTED ->
                                TextButton(onClick = { Collector.bleKick.value = System.nanoTime() }) { Text(Texts.tr("Retry", "Riprova")) }
                        }
                        if (!isPaused) TextButton(onClick = { setPaused(n, true) }) { Text(Texts.tr("Pause", "Pausa")) }
                    }
                }
                if (ui != null && running && stage == BleStage.STREAMING) {
                    val lost = (session?.lostFrames ?: 0L) + (session?.probeDropped ?: 0L)
                    val seen = (session?.wifiObs ?: 0L) + (session?.bleObs ?: 0L)
                    val parts = buildList {
                        add(Texts.tr("${ui.obsPerMin}/min", "${ui.obsPerMin}/min"))
                        if (ui.lastDataMs > 0) add(Texts.tr("last ", "ultimo dato ") + ago(ui.lastDataMs) + Texts.tr(" ago", " fa"))
                        if (ui.rssi != 0) add(Texts.tr("phone hears ", "il telefono sente ") + "${ui.rssi} dBm")
                        if (session != null && session.linkRssi != 0) add(Texts.tr("probe hears ", "la sonda sente ") + "${session.linkRssi} dBm")
                        if (seen + lost > 0) add(Texts.tr("lost ", "persi ") + "%.1f%%".format(100.0 * lost / (seen + lost)))
                        if (ui.connectedSinceMs > 0) add(Texts.tr("up ", "attiva da ") + ago(ui.connectedSinceMs))
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n in silent) Text(
                        Texts.tr(
                            "Connected, but no data for ${ago(ui.lastDataMs)}. A very quiet place, or the probe is stuck: try Reconnect.",
                            "Connessa, ma nessun dato da ${ago(ui.lastDataMs)}. Zona molto silenziosa, o la sonda è bloccata: prova Riconnetti.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    if (lost > 0 && seen > 0 && lost * 20 > seen) Text(
                        Texts.tr("Over 5% lost: move the phone closer to the probe.", "Oltre il 5% perso: avvicina il telefono alla sonda."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                } else if (ui != null && running && !isPaused && ui.lastError.isNotEmpty() && stage != BleStage.CABLE) {
                    Text(ui.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (running) {
            val stages = pairs.mapNotNull { bleLinks[it]?.stage }
            when {
                BleStage.NO_PERMISSION in stages -> Button(onClick = { askPerms() }) { Text(Texts.tr("Grant Bluetooth permission", "Concedi permesso Bluetooth")) }
                BleStage.BT_OFF in stages -> Button(onClick = { btSettings() }) { Text(Texts.tr("Turn Bluetooth on", "Attiva il Bluetooth")) }
            }
        }
        TextButton(onClick = { TabNav.tab.value = 4 }) { Text(Texts.tr("Pairing and probe details ›", "Abbinamenti e dettagli sonda ›")) }
    }
}
