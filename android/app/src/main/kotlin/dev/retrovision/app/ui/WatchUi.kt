// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.WatchRow
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.watch.Watchlist
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One way to follow a device, offered in the "tell me" dialog. */
class WatchTarget(val kind: Watchlist.Kind, val pattern: String, val title: String, val note: String)

/** Ways to follow a device from what is known about it, best first. */
fun watchTargets(
    entityId: String,
    addresses: List<String>,
    stable: Boolean,
    linked: Boolean,
    signature: String?,
    name: String?,
): List<WatchTarget> = buildList {
    val addr = addresses.firstOrNull()
    if (addr != null && stable) add(
        WatchTarget(
            Watchlist.Kind.ADDRESS, addr, Texts.tr("This address", "Questo indirizzo") + " ($addr)",
            Texts.tr("Precise. Its address does not change.", "Preciso. Il suo indirizzo non cambia."),
        ),
    )
    if (linked || !stable) add(
        WatchTarget(
            Watchlist.Kind.ENTITY, entityId, Texts.tr("This device as linked by the app", "Questo dispositivo come l'ha collegato l'app"),
            Texts.tr(
                "Follows the addresses the app linked to it. Its address rotates: after a long absence it usually comes back under an address the app cannot tie to this one.",
                "Segue gli indirizzi che l'app gli ha collegato. Il suo indirizzo cambia: dopo una lunga assenza di solito torna con un indirizzo che l'app non sa ricollegare a questo.",
            ),
        ),
    )
    if (signature != null) add(
        WatchTarget(
            Watchlist.Kind.SIGNATURE, signature,
            Texts.tr("Its advert (“", "Il suo annuncio (“") + dev.retrovision.core.identity.BleSignature.displayName(signature) + Texts.tr("” and its shape)", "” e la sua forma)"),
            Texts.tr(
                "Survives address changes. Any device with the same name and advert matches too.",
                "Regge ai cambi di indirizzo. Corrisponde anche ogni altro dispositivo con lo stesso nome e annuncio.",
            ),
        ),
    )
    if (!name.isNullOrBlank()) add(
        WatchTarget(
            Watchlist.Kind.NAME, name, Texts.tr("Its name: “$name”", "Il suo nome: “$name”"),
            Texts.tr("Any device or network with this name.", "Qualunque dispositivo o rete con questo nome."),
        ),
    )
    if (addr != null && !stable) add(
        WatchTarget(
            Watchlist.Kind.ADDRESS, addr, Texts.tr("Only this address", "Solo questo indirizzo") + " ($addr)",
            Texts.tr("Its address rotates: this one will not come back. Use it only for the next minutes.", "Il suo indirizzo cambia: questo non tornerà. Usalo solo per i prossimi minuti."),
        ),
    )
}

fun watchTargets(r: EntityReport): List<WatchTarget> = watchTargets(
    r.entityId, r.addresses.map { it.toString() }, r.macTrust == MacTrust.STABLE, r.addresses.size > 1 || r.memberIds.size > 1,
    r.bleSignature, r.bleName ?: r.ssids.firstOrNull()?.takeIf { r.kind == dev.retrovision.core.analysis.EntityKind.WIFI_AP },
)

private val ABSENCES = listOf(10, 30, 60, 180, 720)
private val RSSI_STEPS = listOf(0, -85, -75, -65)

private fun rssiText(v: Int) = when (v) {
    0 -> Texts.tr("any", "qualsiasi")
    -85 -> Texts.tr("in range", "in portata")
    -75 -> Texts.tr("near", "vicino")
    else -> Texts.tr("very near", "molto vicino")
}

/**
 * "Tell me when it comes back": pick what to follow and when to be told. [lastSeenMs] seeds the
 * absence so creating a rule for a device in sight does not alert at once.
 */
@Composable
fun WatchDialog(label: String, targets: List<WatchTarget>, lastSeenMs: Long, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(label) }
    var onReturn by remember { mutableStateOf(true) }
    var absence by remember { mutableIntStateOf(30) }
    var rssi by remember { mutableIntStateOf(0) }
    var awayOnly by remember { mutableStateOf(false) }
    var onGone by remember { mutableStateOf(false) }
    var goneMin by remember { mutableIntStateOf(5) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Texts.tr("Tell me when it comes back", "Avvisami quando torna")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(Texts.tr("Name for the alert", "Nome per l'avviso")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Overline(Texts.tr("Follow", "Segui"))
                targets.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().clickable { pick = i }, verticalAlignment = Alignment.Top) {
                        androidx.compose.material3.RadioButton(selected = pick == i, onClick = { pick = i })
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium)
                            Text(t.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onReturn, { onReturn = it })
                    Text(Texts.tr("When it is back after", "Quando torna dopo"), style = MaterialTheme.typography.bodyMedium)
                }
                if (onReturn) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ABSENCES.forEach { m -> FilterChip(absence == m, { absence = m }, label = { Text(Texts.span(m * 60_000L)) }) }
                    }
                    Text(Texts.tr("of silence, and its signal is", "di silenzio, con un segnale"), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RSSI_STEPS.forEach { v -> FilterChip(rssi == v, { rssi = v }, label = { Text(rssiText(v)) }) }
                    }
                    if (rssi != 0) Text(
                        Texts.tr(
                            "At least $rssi dBm. Signal is a rough guide to distance: walls, pockets and bodies change it a lot.",
                            "Almeno $rssi dBm. Il segnale indica la distanza solo a grandi linee: muri, tasche e corpi lo cambiano molto.",
                        ),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(awayOnly, { awayOnly = it })
                        Text(Texts.tr("Only away from my routine places", "Solo lontano dai miei luoghi abituali"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onGone, { onGone = it })
                    Text(Texts.tr("When it is no longer heard (something of mine I might leave behind)", "Quando non si sente più (una cosa mia che potrei dimenticare)"), style = MaterialTheme.typography.bodySmall)
                }
                if (onGone) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 5, 10, 30).forEach { m -> FilterChip(goneMin == m, { goneMin = m }, label = { Text("$m min") }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = targets.isNotEmpty() && (onReturn || onGone), onClick = {
                val t = targets[pick]
                scope.launch {
                    RetrovisionApp.instance.db.dao().putWatch(
                        WatchRow(
                            label = name.ifBlank { label }, kind = t.kind.name, pattern = t.pattern, onReturn = onReturn,
                            minAbsenceMin = absence, minRssi = if (onReturn) rssi else 0, onAbsence = onGone, absenceMin = goneMin,
                            awayOnly = awayOnly, enabled = true, lastHeardMs = lastSeenMs, lastAlertMs = 0, createdMs = System.currentTimeMillis(),
                        ),
                    )
                    onClose()
                }
            }) { Text(Texts.tr("Watch", "Avvisami")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(Texts.tr("Cancel", "Annulla")) } },
    )
}

private fun watchKindText(k: String) = when (k) {
    Watchlist.Kind.ADDRESS.name -> Texts.tr("address", "indirizzo")
    Watchlist.Kind.ENTITY.name -> Texts.tr("linked device", "dispositivo collegato")
    Watchlist.Kind.SIGNATURE.name -> Texts.tr("advert", "annuncio")
    else -> Texts.tr("name", "nome")
}

private fun watchPatternText(row: WatchRow) =
    if (row.kind == Watchlist.Kind.SIGNATURE.name) "“" + dev.retrovision.core.identity.BleSignature.displayName(row.pattern) + "”" else row.pattern

/**
 * The watchlist: every rule with its switch, what it follows and when it was last heard; adding a
 * rule by name or address; the latest events.
 */
@Composable
fun WatchlistSection() {
    val dao = RetrovisionApp.instance.db.dao()
    val rows by remember { dao.watches() }.collectAsState(initial = emptyList())
    val events by Collector.watchEvents.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<WatchRow?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Text(
        Texts.tr(
            "Be told when a device, an advert or a name is heard again after a while, or when something of yours is no longer heard. Works while collection runs; alerts follow the notification settings (on/off, quiet hours, discreet).",
            "Ricevi un avviso quando un dispositivo, un annuncio o un nome si sente di nuovo dopo un po', o quando una tua cosa non si sente più. Funziona mentre la raccolta è attiva; gli avvisi seguono le impostazioni delle notifiche (attive, ore di silenzio, discrete).",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (rows.isEmpty()) Text(
        Texts.tr("Nothing watched yet. Add a name here, or open a device and tap “Tell me”.", "Ancora niente. Aggiungi un nome qui, oppure apri un dispositivo e tocca “Avvisami”."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rows.forEach { w ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                val parts = buildList {
                    add(watchKindText(w.kind) + " " + watchPatternText(w))
                    if (w.onReturn) add(Texts.tr("back after ", "torna dopo ") + Texts.span(w.minAbsenceMin * 60_000L) + if (w.minRssi != 0) " · ≥ ${w.minRssi} dBm" else "")
                    if (w.onAbsence) add(Texts.tr("gone ", "assente ") + "${w.absenceMin} min")
                    if (w.awayOnly) add(Texts.tr("away only", "solo fuori"))
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text(
                    if (w.lastHeardMs > 0) Texts.tr("last heard ", "ultima volta ") + fmt.format(Date(w.lastHeardMs)) else Texts.tr("not heard yet", "non ancora sentito"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(w.enabled, { on -> scope.launch { dao.setWatchEnabled(w.id, on) } })
            TextButton(onClick = { confirmDelete = w }) { Text("✕") }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(Texts.tr("Add a name or address…", "Aggiungi un nome o un indirizzo…")) }
    if (events.isNotEmpty()) {
        HorizontalDivider()
        Overline(Texts.tr("Latest", "Ultimi"))
        events.take(10).forEach { e ->
            val (title, text) = Texts.watchEvent(e.label, Watchlist.Event(Watchlist.Rule(e.ruleId, e.label, Watchlist.Kind.NAME, ""), e.kind, e.timeMs, e.rssi, e.absentMs, e.what))
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall)
                Text(fmt.format(Date(e.timeMs)) + " · " + text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (adding) AddWatchDialog { adding = false }
    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(Texts.tr("Stop watching “${w.label}”?", "Smettere di seguire “${w.label}”?")) },
            confirmButton = { TextButton(onClick = { scope.launch { dao.deleteWatch(w.id) }; confirmDelete = null }) { Text(Texts.tr("Remove", "Rimuovi")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}

/** Adds a rule typed by hand: an address (any notation) or a name / pattern. */
@Composable
private fun AddWatchDialog(onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var next by remember { mutableStateOf(false) }
    val t = text.trim()
    val addr = Watchlist.parseAddress(t)
    if (next && t.isNotEmpty()) {
        val target = if (addr != null) WatchTarget(
            Watchlist.Kind.ADDRESS, addr.toString(), Texts.tr("Address ", "Indirizzo ") + addr,
            Texts.tr("Works for addresses that do not rotate.", "Funziona con indirizzi che non cambiano."),
        ) else WatchTarget(
            Watchlist.Kind.NAME, t, Texts.tr("Name “$t”", "Nome “$t”"),
            Texts.tr(
                "Bluetooth names, network names announced by access points, networks phones ask for. Case and accents ignored; * matches anything (RV-* = names starting with RV-).",
                "Nomi Bluetooth, nomi delle reti annunciate dagli access point, reti cercate dai telefoni. Maiuscole e accenti ignorati; * vale qualunque cosa (RV-* = nomi che iniziano con RV-).",
            ),
        )
        WatchDialog(t, listOf(target), lastSeenMs = 0L, onClose = onClose)
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Texts.tr("Watch a name or address", "Segui un nome o un indirizzo")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(Texts.tr("Name, pattern or MAC", "Nome, schema o MAC")) })
                Text(
                    when {
                        t.isEmpty() -> Texts.tr("e.g. “Flipper”, “RV-*”, “aa:bb:cc:dd:ee:ff”", "es. “Flipper”, “RV-*”, “aa:bb:cc:dd:ee:ff”")
                        addr != null -> Texts.tr("An address.", "Un indirizzo.")
                        else -> Texts.tr("A name or pattern.", "Un nome o uno schema.")
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = t.isNotEmpty(), onClick = { next = true }) { Text(Texts.tr("Next", "Avanti")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(Texts.tr("Cancel", "Annulla")) } },
    )
}
