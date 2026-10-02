package dev.retrovision.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** One device found by a Bluetooth Classic inquiry. */
class ClassicDevice(val address: String, val name: String?, val rssi: Int, val major: Int?) {
    /** Cheap serial modules (HC-05/06, linvor, RN-42…): common in card skimmers, also in DIY and cars. */
    val serialModule: Boolean get() = name != null && SKIMMER_NAMES.containsMatchIn(name)

    companion object {
        val SKIMMER_NAMES = Regex("^(HC-0[3-9]|HC0[56]|linvor|BT04|BT05|JDY-3\\d|RNBT-|RN42|SPP-CA|HC-08|BT-HC0)", RegexOption.IGNORE_CASE)
    }
}

/**
 * On-demand Bluetooth Classic inquiry (~12 s). The probe (ESP32-S3) has no Classic radio, so this is
 * the only way to see discoverable Classic devices, e.g. serial modules hidden in a skimmer.
 * Only devices in discoverable mode answer; it occupies the phone's Bluetooth while it runs.
 */
@SuppressLint("MissingPermission")
@Composable
fun ClassicScanDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val adapter = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    val found = remember { mutableStateListOf<ClassicDevice>() }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) {
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }) ?: return
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val cls: BluetoothClass? = if (Build.VERSION.SDK_INT >= 33) {
                            i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                        } else {
                            @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                        }
                        val name = runCatching { d.name }.getOrNull() ?: i.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        found.removeAll { it.address == d.address }
                        found += ClassicDevice(d.address, name, rssi, cls?.majorDeviceClass)
                        found.sortByDescending { it.rssi }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> running = false
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        val ok = runCatching { adapter?.isEnabled == true && adapter.startDiscovery() }.getOrDefault(false)
        running = ok
        if (!ok) error = Texts.tr("Bluetooth is off or permission is missing.", "Bluetooth spento o permesso mancante.")
        onDispose {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(Texts.tr("Classic Bluetooth scan", "Scansione Bluetooth Classic")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) LinearProgressIndicator(Modifier)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    Texts.tr(
                        "Only devices in discoverable mode answer. A serial module (HC-05, HC-06…) next to a card reader or fuel pump deserves a look; the same modules are common in DIY and car gadgets.",
                        "Rispondono solo i dispositivi in modalità visibile. Un modulo seriale (HC-05, HC-06…) vicino a un lettore di carte o a una pompa merita un controllo; gli stessi moduli sono comuni in progetti fai-da-te e accessori auto.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!running && found.isEmpty() && error == null) Text(Texts.tr("Nothing discoverable nearby.", "Niente di visibile nelle vicinanze."))
                found.forEach { d ->
                    Text(
                        (if (d.serialModule) "⚠ " else "• ") + (d.name ?: Texts.tr("(no name)", "(senza nome)")) +
                            " · ${d.address} · ${d.rssi} dBm" + (majorName(d.major)?.let { " · $it" } ?: "") +
                            if (d.serialModule) Texts.tr(" — serial module", " — modulo seriale") else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.serialModule) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(Texts.tr("Close", "Chiudi")) } },
    )
}

private fun majorName(m: Int?): String? = when (m) {
    BluetoothClass.Device.Major.PHONE -> Texts.tr("phone", "telefono")
    BluetoothClass.Device.Major.COMPUTER -> "computer"
    BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio/video"
    BluetoothClass.Device.Major.PERIPHERAL -> Texts.tr("peripheral", "periferica")
    BluetoothClass.Device.Major.WEARABLE -> Texts.tr("wearable", "indossabile")
    BluetoothClass.Device.Major.TOY -> Texts.tr("toy", "giocattolo")
    BluetoothClass.Device.Major.HEALTH -> Texts.tr("health", "salute")
    BluetoothClass.Device.Major.UNCATEGORIZED -> Texts.tr("uncategorised", "non classificato")
    else -> null
}
