package dev.retrovision.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.model.WifiKind
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
    val list = all.filter(filter.match)

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Texts.tr("Devices", "Dispositivi") + " (${list.size}/${all.size})", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { Collector.analyzeNow.value = System.nanoTime() }) { Text(Texts.tr("Analyse now", "Analizza ora")) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DeviceFilter.entries.forEach { f ->
                val n = all.count(f.match)
                if (n == 0 && f != DeviceFilter.ALL && f != filter) return@forEach
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text((if (f.emoji.isNotEmpty()) f.emoji + " " else "") + f.label + " $n") },
                )
            }
        }
        if (all.isEmpty()) Text(Texts.tr("Nothing analysed yet. Start collecting and wait a minute.", "Ancora nulla. Avvia la raccolta e attendi un minuto."))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.take(300), key = { it.entityId }) { r ->
                EntityCard(r) { selected = r }
            }
        }
    }
    selected?.let { DeviceDialog(it) { selected = null } }
}

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

fun trustLabel(t: MacTrust) = when (t) {
    MacTrust.STABLE -> Texts.tr("Fixed MAC", "MAC fisso")
    MacTrust.UNTIL_REBOOT -> Texts.tr("MAC changes on reboot", "MAC cambia al riavvio")
    MacTrust.ROTATING -> Texts.tr("Random MAC (not reliable)", "MAC casuale (non affidabile)")
}

@Composable
fun EntityCard(r: EntityReport, onClick: (() -> Unit)? = null) {
    val catColor = CategoryUi.color(r.category)
    Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(catColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text(CategoryUi.icon(r.category), fontSize = 20.sp, color = catColor) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Texts.entityLabel(r), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "%.0f%%".format(r.score * 100),
                        color = if (r.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(CategoryUi.label(r.category), style = MaterialTheme.typography.labelSmall, color = catColor)
                LinearProgressIndicator(progress = { r.score.toFloat() }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.macTrust != MacTrust.STABLE) Badge("⚠ " + trustLabel(r.macTrust), WARN)
                    if (r.addresses.size > 1) Badge(Texts.tr("${r.addresses.size} addresses", "${r.addresses.size} indirizzi"), WARN)
                    r.joinAttempts.firstOrNull()?.let {
                        Badge("🔗 " + Texts.tr("joining ", "si collega a ") + (it.ssid.ifEmpty { it.bssid.toString() }), JOIN)
                    }
                    if (r.probedSsids.isNotEmpty()) {
                        Badge(
                            "🔍 " + Texts.tr("looking for ", "cerca ") + r.probedSsids.take(3).joinToString(", ") { "“$it”" } +
                                if (r.probedSsids.size > 3) " +${r.probedSsids.size - 3}" else "",
                            SEARCH,
                        )
                    }
                }
                Text(
                    "${r.placeIds.size} ${Texts.tr("places", "luoghi")} · ${r.sightings} ${Texts.tr("sightings", "avvistamenti")} · ${r.maxRssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                )
                r.reasons.forEach { Text("• " + Texts.reason(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Extra facts in the device dialog: addresses and their reliability, networks searched and joined. */
@Composable
fun DeviceDetails(r: EntityReport) {
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
        Text(r.addresses.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)

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
