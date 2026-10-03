// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The alert notification opens straight on the alerts and their evidence. */
    private fun handle(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ALERTS, false) == true) AlertsNav.open.value = true
    }

    companion object {
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            RetrovisionTheme {
                // Edge to edge, but nothing drawn under the status bar, the navigation buttons or the
                // keyboard: the whole app sits inside the safe area (screens and overlays alike).
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // "Show on map" from a device: go to Places (which centres on it), closing any overlay.
    val mapDevice by MapNav.device.collectAsState()
    val mapPoint by MapNav.point.collectAsState()
    androidx.compose.runtime.LaunchedEffect(mapDevice, mapPoint) {
        if (mapDevice != null || mapPoint != null) { AlertsNav.open.value = false; tab = 2 }
    }
    val wikiOpen by WikiNav.open.collectAsState()
    if (wikiOpen) {
        WikiScreen(onClose = { WikiNav.open.value = false })
        return
    }
    val alertsOpen by AlertsNav.open.collectAsState()
    if (alertsOpen) {
        AlertsScreen(onClose = { AlertsNav.open.value = false })
        return
    }
    val diagOpen by DiagNav.open.collectAsState()
    if (diagOpen) {
        DiagnosticsScreen(onClose = { DiagNav.open.value = false })
        return
    }
    val tabs = listOf(
        androidx.compose.material.icons.Icons.Filled.Home to Texts.tr("Status", "Stato"),
        androidx.compose.material.icons.Icons.AutoMirrored.Filled.List to Texts.tr("Devices", "Dispositivi"),
        androidx.compose.material.icons.Icons.Filled.Place to Texts.tr("Places", "Luoghi"),
        androidx.compose.material.icons.Icons.Filled.Settings to Texts.tr("Settings", "Impostazioni"),
        androidx.compose.material.icons.Icons.Filled.Build to Texts.tr("Probe", "Sonda"),
    )
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { androidx.compose.material3.Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> StatusScreen(m)
            1 -> DevicesScreen(m)
            2 -> PlacesScreen(m)
            3 -> SettingsScreen(m)
            else -> ProbeScreen(m)
        }
    }
}
