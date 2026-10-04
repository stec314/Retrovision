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

/**
 * The wireless (BLE) link, in the Probe tab: live status, telemetry, pairing over the cable and
 * recovery actions. Pairing needs the cable: the key is set over USB, so it requires physical
 * access to the probe.
 */
@Composable
fun ProbeLinkCard() {
    val ctx = LocalContext.current
    val prefs = app.prefs
    val conn by Collector.connection.collectAsState()
    val ble by Collector.bleLink.collectAsState()
    val running by Collector.running.collectAsState()
    var pairedName by remember { mutableStateOf(prefs.blePairName) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteError by remember { mutableStateOf(false) }
    var pendingSeq by remember { mutableIntStateOf(0) }
    var pendingAt by remember { mutableLongStateOf(0L) }
    var confirmForget by remember { mutableStateOf(false) }
    // Re-read once a second: elapsed times and the session state change continuously.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }

    val session = conn.session
    val info = session?.info
    val usbUp = Collector.usbConnected && session?.phase == Phase.STREAMING
    val capable = info?.bleLinkCapable == true
    val paired = pairedName.isNotEmpty()

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Collector.bleKick.value = System.nanoTime()
    }
    fun askPerms() {
        if (Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
    }
    fun btSettings() = runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // Pairing outcome: the probe acks the command (then reboots), or refuses it.
    LaunchedEffect(session?.lastAck, pendingSeq) {
        val a = session?.lastAck ?: return@LaunchedEffect
        if (pendingSeq == 0 || a.commandSeq != pendingSeq) return@LaunchedEffect
        if (a.ok) {
            note = Texts.tr(
                "Probe accepted. It reboots now. Unplug the cable and power it from a power bank: it should connect over Bluetooth within a minute. On the first connection accept the Bluetooth pairing prompt.",
                "La sonda ha accettato e si riavvia. Scollega il cavo e alimentala con un power bank: dovrebbe collegarsi via Bluetooth entro un minuto. Alla prima connessione accetta la richiesta di abbinamento Bluetooth.",
            )
            noteError = false
        } else {
            prefs.clearBlePair(); pairedName = ""
            note = Texts.tr("The probe refused: ", "La sonda ha rifiutato: ") + "${a.result} ${a.message}"
            noteError = true
        }
        pendingSeq = 0
    }
    // No answer at all: firmware too old to know the command, or the cable dropped.
    LaunchedEffect(pendingSeq, tick) {
        if (pendingSeq != 0 && System.currentTimeMillis() - pendingAt > 6000) {
            note = Texts.tr(
                "No answer from the probe. If the probe does not show up over Bluetooth, update its firmware here and pair again.",
                "Nessuna risposta dalla sonda. Se non compare via Bluetooth, aggiorna il firmware qui sotto e abbina di nuovo.",
            )
            noteError = true
            pendingSeq = 0
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Texts.tr("Bluetooth link (no cable) · experimental", "Collegamento Bluetooth (senza cavo) · sperimentale"), style = MaterialTheme.typography.titleMedium)

            // ---- live status ----
            val stage = if (!running && paired) BleStage.STOPPED else if (!paired) BleStage.OFF else ble.stage
            val (dot, color) = when (stage) {
                BleStage.STREAMING -> "●" to MaterialTheme.colorScheme.primary
                BleStage.REJECTED, BleStage.NO_PERMISSION, BleStage.BT_OFF -> "●" to MaterialTheme.colorScheme.error
                BleStage.OFF, BleStage.CABLE, BleStage.STOPPED -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "◐" to MaterialTheme.colorScheme.tertiary
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dot, color = color)
                Text(
                    bleStageText(stage) + if (paired) " · RV-$pairedName" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (paired && running) {
                val parts = buildList {
                    if (ble.stage != BleStage.OFF && ble.stage != BleStage.CABLE) add(Texts.tr("for ", "da ") + ago(ble.sinceMs))
                    if (ble.attempts > 0) add(Texts.tr("attempts ", "tentativi ") + ble.attempts)
                    if (ble.rssi != 0) add(Texts.tr("phone hears ", "il telefono sente ") + "${ble.rssi} dBm")
                    if (ble.stage == BleStage.STREAMING && session != null) {
                        if (session.linkRssi != 0) add(Texts.tr("probe hears ", "la sonda sente ") + "${session.linkRssi} dBm")
                        if (ble.mtu > 0) add("MTU ${ble.mtu}")
                    }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Telemetry over the air: the same counters as on the cable.
                if (ble.stage == BleStage.STREAMING && session != null) {
                    val lost = session.lostFrames + session.probeDropped
                    val seen = session.wifiObs + session.bleObs
                    Text(
                        "Wi-Fi ${session.wifiObs}" + (if (session.channel > 0) " · ch ${session.channel}" else "") +
                            " · " + Texts.tr("lost", "persi") + " $lost" + (if (seen + lost > 0) " (%.1f%%)".format(100.0 * lost / (seen + lost)) else "") +
                            (if (session.chipTempC != 0f) " · ${"%.0f".format(session.chipTempC)} °C" else "") +
                            (if (ble.connectedSinceMs > 0) " · " + Texts.tr("up ", "attivo da ") + ago(ble.connectedSinceMs) else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (lost > 0 && seen > 0 && lost * 20 > seen) Text(
                        Texts.tr(
                            "Over 5% lost: Bluetooth cannot keep up here (busy area or weak signal). Keep the probe closer, or use the cable for this session.",
                            "Oltre il 5% perso: il Bluetooth non regge qui (zona affollata o segnale debole). Avvicina la sonda o usa il cavo per questa sessione.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (ble.lastError.isNotEmpty() && ble.stage != BleStage.STREAMING) {
                    Text(ble.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (ble.others.isNotEmpty() && ble.stage != BleStage.STREAMING) {
                    Text(
                        Texts.tr("Other probes heard: ", "Altre sonde sentite: ") +
                            ble.others.entries.take(4).joinToString { "${it.key} (${it.value} dBm)" } +
                            Texts.tr(". If one is yours, it was paired with a different key (e.g. before a reinstall): plug it in and pair again.",
                                ". Se una è la tua, è stata abbinata con un'altra chiave (es. prima di una reinstallazione): collegala col cavo e abbina di nuovo."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (ble.stage) {
                        BleStage.NO_PERMISSION -> Button(onClick = { askPerms() }) { Text(Texts.tr("Grant Bluetooth permission", "Concedi permesso Bluetooth")) }
                        BleStage.BT_OFF -> Button(onClick = { btSettings() }) { Text(Texts.tr("Turn Bluetooth on", "Attiva il Bluetooth")) }
                        BleStage.STREAMING, BleStage.CABLE -> Unit
                        else -> OutlinedButton(onClick = { Collector.bleKick.value = System.nanoTime() }) { Text(Texts.tr("Retry now", "Riprova ora")) }
                    }
                    if (ble.lastError.contains("Bluetooth settings")) TextButton(onClick = { btSettings() }) { Text(Texts.tr("Bluetooth settings", "Impostazioni Bluetooth")) }
                }
            }
            if (paired && !running) Text(
                Texts.tr("Start collecting on the Status tab: the Bluetooth link runs with the collection.", "Avvia la raccolta dalla scheda Stato: il collegamento Bluetooth gira insieme alla raccolta."),
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- what the probe on the cable says about itself ----
            if (info != null && Collector.usbConnected) {
                when {
                    !capable -> Text(
                        Texts.tr(
                            "This probe's firmware (${info.firmware}) has no Bluetooth link. Flash the firmware below, then pair.",
                            "Il firmware di questa sonda (${info.firmware}) non ha il collegamento Bluetooth. Flasha il firmware qui sotto, poi abbina.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    info.configuredLink == LinkKind.LINK_KIND_BLE && (!paired || info.linkName != pairedName) -> Text(
                        Texts.tr(
                            "The probe is set up for Bluetooth as “RV-${info.linkName}”, but this phone does not hold that key (for example after reinstalling the app). Pair again to replace it.",
                            "La sonda è impostata per il Bluetooth come “RV-${info.linkName}”, ma questo telefono non ha quella chiave (per esempio dopo aver reinstallato l'app). Abbina di nuovo per sostituirla.",
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    info.configuredLink == LinkKind.LINK_KIND_BLE -> Text(
                        Texts.tr("Probe set up for Bluetooth: unplug the cable to switch over.", "Sonda impostata per il Bluetooth: scollega il cavo per passare al wireless."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Unit
                }
            }

            // ---- pairing ----
            val needsRepair = info?.configuredLink == LinkKind.LINK_KIND_BLE && (!paired || info.linkName != pairedName)
            if (!paired || needsRepair) {
                Button(
                    enabled = usbUp && capable && pendingSeq == 0,
                    onClick = {
                        askPerms()
                        val key = java.security.SecureRandom().generateSeed(24)
                        val tail = info?.hardwareId?.takeLast(4) ?: "%04x".format(System.nanoTime() and 0xffff)
                        val name = "probe-$tail"
                        val seq = Collector.session?.setLink(LinkKind.LINK_KIND_BLE, name, key) ?: 0
                        if (seq != 0) {
                            // Stored now (the ack may race the reboot); cleared again if the probe refuses.
                            prefs.setBlePair(name, key); pairedName = name
                            pendingSeq = seq; pendingAt = System.currentTimeMillis()
                            note = Texts.tr("Sending the key to the probe…", "Invio la chiave alla sonda…"); noteError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (needsRepair) Texts.tr("Pair again (cable connected)", "Abbina di nuovo (cavo collegato)") else Texts.tr("Pair for Bluetooth (cable connected)", "Abbina per il Bluetooth (cavo collegato)")) }
                if (!usbUp) Text(
                    Texts.tr(
                        "Plug the probe into the phone with the cable first: the key is set over USB, so nobody can pair it from a distance.",
                        "Collega prima la sonda al telefono col cavo: la chiave viene impostata via USB, così nessuno può abbinarla a distanza.",
                    ),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    enabled = usbUp,
                    onClick = {
                        val key = prefs.blePairKey() ?: ByteArray(16)
                        Collector.session?.setLink(LinkKind.LINK_KIND_USB, pairedName, key)
                        prefs.clearBlePair(); pairedName = ""
                        note = Texts.tr("Wireless off. The probe reboots to cable-only.", "Wireless disattivato. La sonda si riavvia solo cavo."); noteError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Texts.tr("Back to cable only (cable connected)", "Torna solo cavo (cavo collegato)")) }
                TextButton(onClick = { confirmForget = true }) { Text(Texts.tr("Forget on this phone", "Dimentica su questo telefono")) }
            }
            note?.let {
                Text(it, color = if (noteError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                Texts.tr(
                    "Trade-offs: in Bluetooth mode the probe stops hearing Bluetooth devices (its radio carries the link; the phone's Bluetooth partly covers them), Wi-Fi keeps working, and it still needs power (a small USB power bank). Plugging the cable in always takes over.",
                    "Compromessi: in modalità Bluetooth la sonda smette di sentire i dispositivi Bluetooth (la sua radio porta il collegamento; il Bluetooth del telefono li copre in parte), il Wi-Fi resta attivo e serve comunque alimentazione (un piccolo power bank USB). Collegando il cavo, il cavo ha sempre la precedenza.",
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(Texts.tr("Forget the pairing?", "Dimenticare l'abbinamento?")) },
            text = {
                Text(
                    Texts.tr(
                        "This phone stops looking for the probe. The probe stays in Bluetooth mode with the old key until you plug it in and pair again or switch it back to cable-only.",
                        "Questo telefono smette di cercare la sonda. La sonda resta in modalità Bluetooth con la vecchia chiave finché non la colleghi e la abbini di nuovo o la riporti a solo cavo.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { prefs.clearBlePair(); pairedName = ""; confirmForget = false; Collector.bleKick.value = System.nanoTime() }) {
                    Text(Texts.tr("Forget", "Dimentica"))
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}
