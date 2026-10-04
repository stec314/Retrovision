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
}

private fun linkName(k: LinkKind) = when (k) {
    LinkKind.LINK_KIND_BLE -> "Bluetooth"
    LinkKind.LINK_KIND_WIFI -> "Wi-Fi"
    else -> "USB"
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
    if (heap.size == 3) parts += Texts.tr("free memory ${heap[1]} KiB (lowest ${heap[2]})", "memoria libera ${heap[1]} KiB (minimo ${heap[2]})")
    return parts.joinToString(" · ") to (bad || low)
}

/** One line for the probe card: which transport the session runs on and its signal. */
internal fun linkLine(i: ProbeInfo, s: SessionState): String =
    Texts.tr("link ", "collegamento ") + linkName(i.link) +
        (if (i.configuredLink != i.link) Texts.tr(" (set up for ", " (impostata per ") + linkName(i.configuredLink) + ")" else "") +
        (if (i.link == LinkKind.LINK_KIND_BLE && s.linkRssi != 0) " · ${s.linkRssi} dBm" else "")

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
        BleStage.OFF, BleStage.CABLE, BleStage.STOPPED -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
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
        if (a.ok) {
            note = Texts.tr(
                "Probe accepted. It reboots now. Unplug the cable and power it from a power bank (or the car's USB): it should connect over Bluetooth within a minute. On the first connection accept the Bluetooth pairing prompt.",
                "La sonda ha accettato e si riavvia. Scollega il cavo e alimentala con un power bank (o l'USB dell'auto): dovrebbe collegarsi via Bluetooth entro un minuto. Alla prima connessione accetta la richiesta di abbinamento Bluetooth.",
            )
            noteError = false
        } else {
            prefs.removeBlePair(pendingName); pairs = prefs.blePairs().map { it.name }
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
                        stages.any { it != BleStage.STREAMING && it != BleStage.CABLE } ->
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
                val pairedHere = info.configuredLink == LinkKind.LINK_KIND_BLE && name in pairs
                val needsRepair = info.configuredLink == LinkKind.LINK_KIND_BLE && name !in pairs
                Text(probeModel(info.probeType) + " · " + info.firmware, style = MaterialTheme.typography.bodySmall)
                when {
                    !capable -> Text(
                        Texts.tr("This firmware has no Bluetooth link. Flash the firmware below, then pair.", "Questo firmware non ha il collegamento Bluetooth. Flasha il firmware qui sotto, poi abbina."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    pairedHere -> Text(
                        Texts.tr("Paired as “RV-$name”: unplug the cable to switch it to Bluetooth.", "Abbinata come “RV-$name”: scollega il cavo per passarla al Bluetooth."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    needsRepair -> Text(
                        Texts.tr(
                            "Set up for Bluetooth as “RV-$name”, but this phone does not hold that key (for example after reinstalling the app). Pair again to replace it.",
                            "Impostata per il Bluetooth come “RV-$name”, ma questo telefono non ha quella chiave (per esempio dopo aver reinstallato l'app). Abbina di nuovo per sostituirla.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                val r = usbState?.bleReport.orEmpty()
                if (info.configuredLink == LinkKind.LINK_KIND_BLE && r.isNotEmpty()) {
                    val (txt, bad) = bleReportText(r)
                    Text(Texts.tr("Its Bluetooth: ", "Il suo Bluetooth: ") + txt, style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (capable && !pairedHere) {
                    val full = !needsRepair && pairs.size >= MAX_PROBES
                    Button(
                        enabled = usbUp && pendingSeq == 0 && !full,
                        onClick = {
                            askPerms()
                            val key = java.security.SecureRandom().generateSeed(24)
                            // Re-pairing keeps the name, so the probe keeps its place in the list.
                            val newName = if (needsRepair && name.isNotEmpty()) name else "probe-" + info.hardwareId.takeLast(6)
                            val seq = usb?.setLink(LinkKind.LINK_KIND_BLE, newName, key) ?: 0
                            // Stored now (the ack may race the reboot); removed again if the probe refuses.
                            if (seq == 0) return@Button
                            prefs.putBlePair(newName, key); pairs = prefs.blePairs().map { it.name }
                            pendingSeq = seq; pendingName = newName; pendingAt = System.currentTimeMillis()
                            note = Texts.tr("Sending the key to the probe…", "Invio la chiave alla sonda…"); noteError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (needsRepair) Texts.tr("Pair again", "Abbina di nuovo")
                            else if (pairs.isEmpty()) Texts.tr("Pair this probe for Bluetooth", "Abbina questa sonda per il Bluetooth")
                            else Texts.tr("Add this probe (Bluetooth)", "Aggiungi questa sonda (Bluetooth)"),
                        )
                    }
                    if (full) Text(Texts.tr("At most $MAX_PROBES probes: forget one first.", "Al massimo $MAX_PROBES sonde: dimenticane prima una."), style = MaterialTheme.typography.labelSmall)
                }
                if (info.configuredLink == LinkKind.LINK_KIND_BLE) {
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
                    "Trade-offs: in Bluetooth mode a probe stops hearing Bluetooth devices (its radio carries the link; the phone's Bluetooth covers them), Wi-Fi keeps working, and it needs power (power bank or car USB). A probe left in a parked car only sends while the phone is within Bluetooth range (~10–30 m). Plugging the cable in always takes over.",
                    "Compromessi: in modalità Bluetooth una sonda smette di sentire i dispositivi Bluetooth (la sua radio porta il collegamento; li copre il Bluetooth del telefono), il Wi-Fi resta attivo e serve alimentazione (power bank o USB dell'auto). Una sonda lasciata nell'auto parcheggiata trasmette solo quando il telefono è nel raggio Bluetooth (~10–30 m). Collegando il cavo, il cavo ha sempre la precedenza.",
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
