package dev.retrovision.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "📡" to Texts.tr("Status", "Stato"),
        "🔎" to Texts.tr("Devices", "Dispositivi"),
        "📍" to Texts.tr("Places", "Luoghi"),
        "🔌" to Texts.tr("Probe", "Sonda"),
        "⚙" to Texts.tr("Settings", "Impostazioni"),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
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
            3 -> ProbeScreen(m)
            else -> SettingsScreen(m)
        }
    }
}
