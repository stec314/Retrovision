// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.retrovision.app.Collector
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.analysis.Reason
import kotlinx.coroutines.launch

/** Label of any device of the current analysis, full report or trimmed stub. */
internal fun anyLabel(id: String): String {
    val a = Collector.analysis.value
    a?.entities?.firstOrNull { it.entityId == id }?.let { return Texts.entityLabel(it) }
    a?.others?.firstOrNull { it.entityId == id }?.let { return stubLabel(it) }
    return id
}

/**
 * What this device is tied to: devices moving with it (same places, same times), devices linked to
 * it by rare networks they both ask for, the access point it is connected to and the clients
 * connected to it. Empty when there is nothing to say.
 */
@Composable
internal fun AssociationsPanel(r: EntityReport, onOpen: (EntityReport) -> Unit) {
    val analysis by Collector.analysis.collectAsState()
    val assoc by Collector.associations.collectAsState()
    val scope = rememberCoroutineScope()
    val group = analysis?.groupOf?.get(r.entityId) ?: r.memberIds.firstNotNullOfOrNull { analysis?.groupOf?.get(it) }
    val mates = group?.members?.filter { it != r.entityId && it !in r.memberIds }.orEmpty()
    val myAddrs = r.addresses.toSet()
    val apsOf = assoc.filter { ap -> ap.clients.any { it in myAddrs } }
    val clientsOf = assoc.filter { it.bssid in myAddrs }.flatMap { it.clients }.distinct()
    val shared = r.reasons.filterIsInstance<Reason.LinkedByNetworks>()
    if (mates.isEmpty() && apsOf.isEmpty() && clientsOf.isEmpty() && shared.isEmpty()) return

    fun open(id: String) {
        val live = analysis?.entities?.firstOrNull { it.entityId == id }
        if (live != null) { onOpen(live); return }
        val f = Collector.analyzeOne ?: return
        scope.launch { runCatching { f(id) }.getOrNull()?.let(onOpen) }
    }

    Panel(title = Texts.tr("Associated with", "Associato a")) {
        if (mates.isNotEmpty()) {
            Text(
                Texts.tr(
                    "Moves together with ${mates.size} device(s): heard at ${group!!.sharedPlaces} of the same places at the same times. One person or one vehicle, or people travelling together.",
                    "Si muove insieme a ${mates.size} dispositivi: sentiti in ${group!!.sharedPlaces} degli stessi luoghi negli stessi momenti. Una persona o un veicolo, o persone che viaggiano insieme.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            mates.forEach { id ->
                Text(
                    "👥 " + anyLabel(id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { open(id) }.padding(vertical = 6.dp),
                )
            }
        }
        shared.forEach { l ->
            Text(Texts.reason(l), style = MaterialTheme.typography.bodySmall)
        }
        apsOf.forEach { ap ->
            Text(
                Texts.tr("Connected to ", "Connesso a ") + (ap.ssid?.let { "“$it” " } ?: "") + ap.bssid,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (clientsOf.isNotEmpty()) {
            Column {
                Text(Texts.tr("${clientsOf.size} client(s) connected to it:", "${clientsOf.size} client connessi:"), style = MaterialTheme.typography.bodySmall)
                clientsOf.take(10).forEach { Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
