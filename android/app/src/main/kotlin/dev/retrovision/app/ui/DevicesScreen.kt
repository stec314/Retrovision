// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.Collector
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.analysis.EntitySearch
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.model.WifiKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Icon, colour and name for each device category. Emoji keep the APK free of an icon font. */
object CategoryUi {
    fun icon(c: DeviceCategory) = when (c) {
        DeviceCategory.ROUTER -> "📶"
        DeviceCategory.HOTSPOT -> "📡"
        DeviceCategory.VEHICLE -> "🚗"
        DeviceCategory.CAMERA -> "📷"
        DeviceCategory.WIFI_DIRECT -> "🖨"
        DeviceCategory.WIFI_CLIENT -> "📲"
        DeviceCategory.PHONE -> "📱"
        DeviceCategory.COMPUTER -> "💻"
        DeviceCategory.WATCH -> "⌚"
        DeviceCategory.AUDIO -> "🎧"
        DeviceCategory.TV -> "📺"
        DeviceCategory.INPUT -> "⌨"
        DeviceCategory.HEALTH -> "❤"
        DeviceCategory.HOME -> "💡"
        DeviceCategory.TRACKER -> "🏷"
        DeviceCategory.BEACON -> "🔆"
        DeviceCategory.BLE_OTHER -> "ᛒ"
        DeviceCategory.DRONE -> "🛸"
    }

    fun label(c: DeviceCategory) = when (c) {
        DeviceCategory.ROUTER -> Texts.tr("Wi-Fi network", "Rete Wi-Fi")
        DeviceCategory.HOTSPOT -> Texts.tr("Phone hotspot", "Hotspot di un telefono")
        DeviceCategory.VEHICLE -> Texts.tr("Vehicle", "Veicolo")
        DeviceCategory.CAMERA -> Texts.tr("Camera / dashcam", "Videocamera / dashcam")
        DeviceCategory.WIFI_DIRECT -> Texts.tr("Wi-Fi Direct (printer, TV…)", "Wi-Fi Direct (stampante, TV…)")
        DeviceCategory.WIFI_CLIENT -> Texts.tr("Wi-Fi device (phone, laptop…)", "Dispositivo Wi-Fi (telefono, PC…)")
        DeviceCategory.PHONE -> Texts.tr("Phone / tablet", "Telefono / tablet")
        DeviceCategory.COMPUTER -> Texts.tr("Computer", "Computer")
        DeviceCategory.WATCH -> Texts.tr("Watch / band", "Orologio / braccialetto")
        DeviceCategory.AUDIO -> Texts.tr("Headphones / speaker", "Cuffie / cassa")
        DeviceCategory.TV -> Texts.tr("TV / media", "TV / media")
        DeviceCategory.INPUT -> Texts.tr("Keyboard, mouse, controller", "Tastiera, mouse, controller")
        DeviceCategory.HEALTH -> Texts.tr("Health sensor", "Sensore salute")
        DeviceCategory.HOME -> Texts.tr("Smart home", "Domotica")
        DeviceCategory.TRACKER -> Texts.tr("Tracker tag", "Tracker")
        DeviceCategory.BEACON -> Texts.tr("Beacon", "Beacon")
        DeviceCategory.BLE_OTHER -> Texts.tr("Bluetooth device", "Dispositivo Bluetooth")
        DeviceCategory.DRONE -> Texts.tr("Drone", "Drone")
    }

    fun color(c: DeviceCategory): Color = when (c) {
        DeviceCategory.TRACKER, DeviceCategory.DRONE -> Color(0xFFFF5C7A)
        DeviceCategory.ROUTER, DeviceCategory.HOTSPOT, DeviceCategory.WIFI_DIRECT -> Color(0xFF3DDCFF)
        DeviceCategory.VEHICLE, DeviceCategory.CAMERA -> Color(0xFFFFC857)
        DeviceCategory.WIFI_CLIENT, DeviceCategory.PHONE, DeviceCategory.COMPUTER -> Color(0xFF7CF29A)
        else -> Color(0xFFB89CFF)
    }
}

/** Filter groups shown as chips above the list. */
enum class DeviceFilter(val emoji: String, val en: String, val itText: String, val match: (EntityReport) -> Boolean) {
    ALL("", "All", "Tutti", { true }),
    SEARCHING("🔍", "Looking for a network", "Cercano una rete", { it.probedSsids.isNotEmpty() || it.joinAttempts.isNotEmpty() }),
    TRACKERS("🏷", "Trackers", "Tracker", { it.category == DeviceCategory.TRACKER }),
    DRONES("🛸", "Drones", "Droni", { it.isDrone }),
    NOTABLE("👁", "Notable", "Notevoli", { it.notable.isNotEmpty() }),
    PHONES("📱", "Phones & PCs", "Telefoni e PC", {
        it.category in setOf(DeviceCategory.PHONE, DeviceCategory.COMPUTER, DeviceCategory.WIFI_CLIENT)
    }),
    NETWORKS("📶", "Networks & hotspots", "Reti e hotspot", {
        it.category in setOf(DeviceCategory.ROUTER, DeviceCategory.HOTSPOT, DeviceCategory.WIFI_DIRECT)
    }),
    VEHICLES("🚗", "Vehicles & cameras", "Veicoli e camere", { it.category == DeviceCategory.VEHICLE || it.category == DeviceCategory.CAMERA }),
    WEARABLES("⌚", "Wearables & audio", "Indossabili e audio", {
        it.category in setOf(DeviceCategory.WATCH, DeviceCategory.AUDIO, DeviceCategory.HEALTH)
    }),
    OTHER("ᛒ", "Other Bluetooth", "Altro Bluetooth", {
        it.category in setOf(DeviceCategory.TV, DeviceCategory.INPUT, DeviceCategory.HOME, DeviceCategory.BEACON, DeviceCategory.BLE_OTHER)
    }),
    LINKED("🔗", "Linked addresses", "Indirizzi collegati", { it.addresses.size > 1 }),
    RANDOM_MAC("⚠", "Unreliable MAC", "MAC non affidabile", { it.macTrust != MacTrust.STABLE }),
    ;

    val label: String get() = Texts.tr(en, itText)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(modifier: Modifier) {
    val analysis by Collector.analysis.collectAsState()
    var selected by remember { mutableStateOf<EntityReport?>(null) }
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    val all = analysis?.entities.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    // A city centre yields tens of thousands of devices: indexing, searching, filtering and counting
    // all run off the UI thread (field report: typing in the search box froze the app).
    val others = analysis?.others.orEmpty()
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var shownMax by rememberSaveable { mutableStateOf(RetrovisionApp.instance.prefs.devicesShown) }
    var listMenu by remember { mutableStateOf(false) }
    val index by produceState<EntitySearch.Index<EntityReport>?>(null, all) {
        value = withContext(Dispatchers.Default) {
            EntitySearch.reports(all) { listOf(Texts.entityLabel(it), CategoryUi.label(it.category)) }
        }
    }
    // The trimmed devices are indexed only when asked for (search "all devices").
    val stubIndex by produceState<EntitySearch.Index<dev.retrovision.core.analysis.EntityStub>?>(null, others, searchAll) {
        value = if (!searchAll || others.isEmpty()) null else withContext(Dispatchers.Default) {
            EntitySearch.stubs(others) { listOf(stubLabel(it), CategoryUi.label(it.category)) }
        }
    }
    val view by produceState(DevicesView(), index, stubIndex, query, filter) {
        val idx = index ?: return@produceState
        delay(150) // typing: wait for a pause; a new key press cancels this
        value = withContext(Dispatchers.Default) {
            val matched = idx.search(query)
            DevicesView(
                list = matched.filter(filter.match),
                counts = DeviceFilter.entries.associateWith { f -> matched.count(f.match) },
                networks = if (filter == DeviceFilter.SEARCHING) EntitySearch.searchedNetworks(all) else emptyList(),
                stubs = if (query.isNotBlank()) stubIndex?.search(query).orEmpty().take(300) else emptyList(),
                ready = true,
            )
        }
    }
    val list = view.list
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Texts.tr("Devices", "Dispositivi"), style = MaterialTheme.typography.headlineSmall)
                val total = analysis?.totalEntities ?: all.size
                Text(
                    if (total > all.size) Texts.tr("${all.size} most relevant of $total", "${all.size} più rilevanti su $total")
                    else Texts.tr("$total in the window", "$total nella finestra"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { Collector.analyzeNow.value = System.nanoTime() }) { Text(Texts.tr("Refresh", "Aggiorna")) }
            Box {
                TextButton(onClick = { listMenu = true }) { Text("⋮") }
                androidx.compose.material3.DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                    Overline(Texts.tr("Show in the list", "Mostra nella lista"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    listOf(100, 300, 1000).forEach { n ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (shownMax == n) "● " else "○ ") + "$n") },
                            onClick = { shownMax = n; RetrovisionApp.instance.prefs.devicesShown = n; listMenu = false },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Overline(Texts.tr("Keep in full detail", "Tieni con tutti i dettagli"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    val keep = RetrovisionApp.instance.prefs.maxReports
                    listOf(1000, 2500, 5000, 10000).forEach { n ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (keep == n) "● " else "○ ") + "$n" + if (n == 10000) Texts.tr(" (more memory)", " (più memoria)") else "") },
                            onClick = { RetrovisionApp.instance.prefs.maxReports = n; listMenu = false; Collector.analyzeNow.value = System.nanoTime() },
                        )
                    }
                }
            }
        }
        // "All" searches also the devices trimmed from the detailed list (indexed only when on).
        SearchField(
            query, { query = it }, Texts.tr("Name, network, MAC, vendor", "Nome, rete, MAC, produttore"),
            trailing = if (others.isEmpty()) null else {
                {
                    val on = searchAll
                    Text(
                        Texts.tr("All", "Tutti"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { searchAll = !searchAll }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            },
        )
        if (searchAll && others.isNotEmpty()) Text(
            Texts.tr("Searching also the other ${others.size} devices", "Cerco anche negli altri ${others.size} dispositivi"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 14.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DeviceFilter.entries.forEach { f ->
                val n = view.counts[f] ?: 0
                if (n == 0 && f != DeviceFilter.ALL && f != filter) return@forEach
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text((if (f.emoji.isNotEmpty()) f.emoji + " " else "") + f.label + " $n") },
                )
            }
        }
        if (all.isEmpty()) Text(Texts.tr("Nothing analysed yet. Start collecting and wait a minute.", "Ancora nulla. Avvia la raccolta e attendi un minuto."))
        else if (!view.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (list.isEmpty() && query.isNotBlank()) {
            Text(Texts.tr("No device matches “$query” in the analysed window.", "Nessun dispositivo corrisponde a “$query” nella finestra analizzata."))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filter == DeviceFilter.SEARCHING) {
                item(key = "networks") {
                    val nets = view.networks
                    if (nets.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                Texts.tr("Networks being searched for (devices asking)", "Reti cercate (dispositivi che le chiedono)"),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                Texts.tr(
                                    "Tap one to see who asks for it. A network many devices know is a public one; one only a single device knows says more about that device.",
                                    "Toccane una per vedere chi la cerca. Una rete nota a molti dispositivi è pubblica; una che conosce un solo dispositivo dice di più su di esso.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                nets.take(60).forEach { (ssid, n) ->
                                    FilterChip(
                                        selected = EntitySearch.norm(query) == EntitySearch.norm(ssid),
                                        onClick = { query = if (EntitySearch.norm(query) == EntitySearch.norm(ssid)) "" else ssid },
                                        label = { Text("“$ssid” · $n") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            items(list.take(shownMax), key = { it.entityId }) { r ->
                EntityCard(r, query) { selected = r }
            }
            if (view.stubs.isNotEmpty()) {
                item(key = "stubs-h") { Overline(Texts.tr("Other devices (${view.stubs.size})", "Altri dispositivi (${view.stubs.size})"), Modifier.padding(top = 8.dp)) }
                items(view.stubs, key = { "s" + it.entityId }) { st ->
                    StubRow(st, loading = opening == st.entityId) {
                        val f = Collector.analyzeOne ?: return@StubRow
                        opening = st.entityId
                        scope.launch {
                            val r = runCatching { f(st.entityId) }.getOrNull()
                            opening = null
                            if (r != null) selected = r
                        }
                    }
                }
            } else if (searchAll && query.isNotBlank() && view.ready && stubIndex == null) {
                item(key = "stubs-wait") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (list.size > shownMax) item(key = "more") {
                Text(
                    Texts.tr("Showing $shownMax of ${list.size}. Search, filter or change ⋮ to see more.", "Mostrati $shownMax su ${list.size}. Cerca, filtra o cambia ⋮ per vederne di più."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    selected?.let { DeviceDialog(it) { selected = null } }
}

private class DevicesView(
    val stubs: List<dev.retrovision.core.analysis.EntityStub> = emptyList(),
    val list: List<EntityReport> = emptyList(),
    val counts: Map<DeviceFilter, Int> = emptyMap(),
    val networks: List<Pair<String, Int>> = emptyList(),
    val ready: Boolean = false,
)

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private val SEARCH = Color(0xFFFFC857)
private val JOIN = Color(0xFFFF8A3D)
private val WARN = Color(0xFFFF9E80)
private val LINK = Color(0xFF7FD8FF)

fun trustLabel(t: MacTrust) = when (t) {
    MacTrust.STABLE -> Texts.tr("Fixed MAC", "MAC fisso")
    MacTrust.UNTIL_REBOOT -> Texts.tr("MAC changes on reboot", "MAC cambia al riavvio")
    MacTrust.ROTATING -> Texts.tr("Random MAC (not reliable)", "MAC casuale (non affidabile)")
}

@Composable
fun EntityCard(r: EntityReport, query: String = "", onClick: (() -> Unit)? = null) {
    val catColor = CategoryUi.color(r.category)
    Panel(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(catColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Text(CategoryUi.icon(r.category), fontSize = 20.sp, color = catColor) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Texts.entityLabel(r), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A level in words, never a percentage: the score is not a probability.
                    val lv = dev.retrovision.core.analysis.Levels.of(r)
                    if (lv != dev.retrovision.core.analysis.Level.LOW) LevelPill(lv)
                    Text(CategoryUi.label(r.category), style = MaterialTheme.typography.labelMedium, color = catColor)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.macTrust != MacTrust.STABLE) Badge("⚠ " + trustLabel(r.macTrust), WARN)
                    if (r.addresses.size > 1) Badge("🔗 " + Texts.tr("${r.addresses.size} addresses linked", "${r.addresses.size} indirizzi collegati"), LINK)
                    r.joinAttempts.firstOrNull()?.let {
                        Badge("🔗 " + Texts.tr("joining ", "si collega a ") + (it.ssid.ifEmpty { it.bssid.toString() }), JOIN)
                    }
                    if (r.probedSsids.isNotEmpty()) {
                        // Networks matching the search come first, so the reason it matched is visible.
                        val words = EntitySearch.norm(query).split(' ').filter { it.isNotBlank() }
                        val shown = r.probedSsids.sortedByDescending { s -> words.any { EntitySearch.norm(s).contains(it) } }
                        Badge(
                            "🔍 " + Texts.tr("looking for ", "cerca ") + shown.take(3).joinToString(", ") { "“$it”" } +
                                if (shown.size > 3) " +${shown.size - 3}" else "",
                            SEARCH,
                        )
                    }
                }
                Text(
                    "${r.placeIds.size} ${Texts.tr("places", "luoghi")} · ${r.sightings} ${Texts.tr("frames", "frame")} · ${r.maxRssi} dBm",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The two reasons that weighed most; the rest are in the detail.
                val top = remember(r) { r.reasons.sortedByDescending { r.reasonWeights[it] ?: 0.0 }.take(2) }
                top.forEach { Text(Texts.reason(it), style = MaterialTheme.typography.bodySmall) }
                if (r.reasons.size > 2) Text(Texts.tr("+${r.reasons.size - 2} more reasons", "+${r.reasons.size - 2} altri motivi"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Extra facts in the device dialog: addresses and their reliability, networks searched and joined. */
@Composable
fun DeviceDetails(r: EntityReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IdentityPart(r)
        VisitsList(r)
        NetworksPart(r)
    }
}

/** What it is, how far its address can be trusted, its addresses and how they were linked. */
@Composable
fun IdentityPart(r: EntityReport) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(CategoryUi.icon(r.category) + "  " + CategoryUi.label(r.category) + (r.bleName?.let { " · “$it”" } ?: ""))
        Text(
            "⚠ ".takeIf { r.macTrust != MacTrust.STABLE }.orEmpty() + trustLabel(r.macTrust),
            color = if (r.macTrust == MacTrust.STABLE) MaterialTheme.colorScheme.onSurface else WARN,
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.macTrust == MacTrust.ROTATING) {
            Text(
                Texts.tr(
                    "The address is randomised: it changes over time, so the same device may appear as several entries, and lookups by address are meaningless.",
                    "L'indirizzo è casuale: cambia nel tempo, quindi lo stesso dispositivo può comparire più volte e le ricerche per indirizzo non hanno senso.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (r.addressLinks.size > 1) {
            Text("🔗 " + Texts.tr("Linked addresses", "Indirizzi collegati"), style = MaterialTheme.typography.titleSmall, color = LINK)
            Text(
                Texts.tr(
                    "These addresses were judged to be the same device. Each line says why; check that the times follow on from each other.",
                    "Questi indirizzi sono stati giudicati lo stesso dispositivo. Ogni riga dice perché; controlla che gli orari si susseguano.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            r.addressLinks.forEach { l ->
                Column(Modifier.padding(start = 4.dp, top = 2.dp)) {
                    Text(
                        "${l.address}  ${fmt.format(Date(l.firstMs))}–${fmt.format(Date(l.lastMs))} · ×${l.sightings}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                    Text(Texts.linkVia(l.via), style = MaterialTheme.typography.bodySmall, color = if (l.via == dev.retrovision.core.analysis.LinkVia.ORIGINAL) Color.Unspecified else LINK)
                }
            }
        } else {
            Text(r.addresses.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        r.apUptimeDays?.let { d ->
            Text(
                Texts.tr("Access point up for ", "Access point acceso da ") + (if (d >= 1) "%.1f ".format(d) + Texts.tr("days", "giorni") else "%.0f min".format(d * 1440)) +
                    Texts.tr(" (beacon clock). Days: likely a fixed router. Minutes: just switched on (hotspot, car, or a router after a reboot).", " (orologio del beacon). Giorni: probabile router fisso. Minuti: appena acceso (hotspot, auto, o router riavviato)."),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        r.htProfile?.let { ht ->
            Text(
                "📡 $ht",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                Texts.tr(
                    "Hardware capabilities the device advertises. A model-level clue, shared by every identical phone, not an identity.",
                    "Capacità hardware dichiarate dal dispositivo. Un indizio a livello di modello, condiviso da ogni telefono identico, non un'identità.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

    }
}

/** Networks it searched for by name and tried to join. */
@Composable
fun NetworksPart(r: EntityReport) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (r.probeRequests > 0) {
            Text(Texts.tr("Network search", "Ricerca di reti"), style = MaterialTheme.typography.titleSmall, color = SEARCH)
            Text(
                Texts.tr(
                    "${r.probeRequests} probe requests, ${r.wildcardProbes} for any network.",
                    "${r.probeRequests} richieste di rete, ${r.wildcardProbes} generiche (qualsiasi rete).",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (r.probedSsids.isNotEmpty()) {
                Text(
                    Texts.tr("Asked by name (networks it knows):", "Chieste per nome (reti che conosce):"),
                    style = MaterialTheme.typography.bodySmall,
                )
                r.probedSsids.forEach { Badge("🔍 $it", SEARCH) }
            }
        }
        if (r.joinAttempts.isNotEmpty()) {
            Text(Texts.tr("Connection attempts", "Tentativi di connessione"), style = MaterialTheme.typography.titleSmall, color = JOIN)
            r.joinAttempts.forEach { j ->
                val what = when (j.kind) {
                    WifiKind.ASSOC_REQ -> Texts.tr("association", "associazione")
                    WifiKind.REASSOC_REQ -> Texts.tr("re-association (roaming)", "riassociazione (roaming)")
                    else -> Texts.tr("authentication", "autenticazione")
                }
                Text(
                    "🔗 ${j.ssid.ifEmpty { Texts.tr("(unknown name)", "(nome sconosciuto)") }} · ${j.bssid} · $what ×${j.count} · ${fmt.format(Date(j.lastMs))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = JOIN,
                )
            }
        }
    }
}

/**
 * When it was heard and where YOU were at the time (your GPS), newest first. Not the device's
 * position: one receiver cannot locate a transmitter. Text only, no per-device map.
 */
@Composable
fun VisitsList(r: EntityReport) {
    if (r.visits.isEmpty()) return
    val routine by remember { dev.retrovision.app.RetrovisionApp.instance.db.dao().familiarPlaces() }.collectAsState(initial = emptyList())
    val here by Collector.location.collectAsState()
    val day = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val today = remember { day.format(Date()) }
    Text(
        Texts.tr(
            "Where you were at the time (your GPS), not where the device is. Newest first.",
            "Dove eri tu in quel momento (il tuo GPS), non dove si trova il dispositivo. Dal più recente.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    val confirmed = routine.filter { it.state == dev.retrovision.core.analysis.FamiliarPlace.State.CONFIRMED.ordinal }
    r.visits.asReversed().take(30).forEach { v ->
        val d = day.format(Date(v.startMs))
        val whenText = (if (d == today) Texts.tr("Today", "Oggi") else d) + " " + time.format(Date(v.startMs)) +
            (if (v.endMs - v.startMs >= 60_000) "–" + time.format(Date(v.endMs)) else "")
        val lat = v.lat
        val lon = v.lon
        val whereText = when {
            lat == null || lon == null -> Texts.tr("no GPS at the time", "senza GPS in quel momento")
            else -> {
                val rt = confirmed.firstOrNull { dev.retrovision.core.analysis.Geo.distanceM(it.lat, it.lon, lat, lon) <= it.radiusM }
                val name = rt?.let { it.label.ifEmpty { Texts.tr("routine place", "luogo di routine") } }
                    ?: (Texts.tr("place ", "luogo ") + "#${v.placeId + 1}")
                val h = here
                val dist = h?.let { dev.retrovision.core.analysis.Geo.distanceM(it.lat, it.lon, lat, lon) }
                name + (dist?.let { " · " + Texts.tr("${fmtDist(it)} from here", "a ${fmtDist(it)} da qui") } ?: "")
            }
        }
        Column(Modifier.padding(start = 4.dp, top = 2.dp)) {
            Text(whenText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                "$whereText · ×${v.sightings}" + (if (v.maxRssi != 0) " · ${v.maxRssi} dBm" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (r.visits.size > 30) Text(Texts.tr("…and ${r.visits.size - 30} earlier", "…e altri ${r.visits.size - 30} prima"), style = MaterialTheme.typography.bodySmall)
}

private fun fmtDist(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

/** One line about a trimmed device; tap builds its full report. */
@Composable
private fun StubRow(st: dev.retrovision.core.analysis.EntityStub, loading: Boolean, onOpen: () -> Unit) {
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stubLabel(st), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    CategoryUi.label(st.category) + " · ${st.places} " + Texts.tr("places", "luoghi") + " · ${st.sightings} " + Texts.tr("frames", "frame"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(Texts.tr("Analyse", "Analizza"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun stubLabel(st: dev.retrovision.core.analysis.EntityStub): String {
    val addr = st.addresses.firstOrNull()?.toString().orEmpty()
    val vendor = (st.bleCompanyId?.let { dev.retrovision.app.enrich.Vendors.forCompany(it) } ?: st.addresses.firstOrNull()?.let { dev.retrovision.app.enrich.Vendors.forMac(it) })
    val name = st.bleName?.let { "“$it”" } ?: st.ssids.firstOrNull()
    return listOfNotNull(name, addr, vendor).joinToString(" · ")
}

/** Compact rounded search field: icon, text, clear. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, trailing: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Close, contentDescription = Texts.tr("Clear", "Cancella"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).clickable { onChange("") },
            )
        }
        if (trailing != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            trailing()
        }
    }
}
