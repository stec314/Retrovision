package dev.retrovision.app.probe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dev.retrovision.core.flash.SerialLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object UsbIds {
    const val ESPRESSIF = 0x303A
    const val WCH = 0x1A86
    const val SILABS = 0x10C4
    private val probeVendors = setOf(ESPRESSIF, WCH, SILABS)

    fun isProbe(d: UsbDevice) = d.vendorId in probeVendors
    fun isNativeUsb(d: UsbDevice) = d.vendorId == ESPRESSIF
}

class UsbAccess(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findProbe(): UsbDevice? = mgr.deviceList.values.firstOrNull { UsbIds.isProbe(it) }

    fun isAttached(d: UsbDevice): Boolean = mgr.deviceList.values.any { it.deviceId == d.deviceId }

    fun hasPermission(d: UsbDevice) = mgr.hasPermission(d)

    suspend fun ensurePermission(d: UsbDevice): Boolean {
        if (mgr.hasPermission(d)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != ACTION_PERMISSION) return
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
            mgr.requestPermission(d, pi)
            cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
        }
    }

    /**
     * Opens the first serial port of [d] at [baud] 8N1. Native USB ignores the line settings.
     * With [release] the DTR/RTS lines are deasserted, so the auto-reset circuit of
     * UART boards (NodeMCU, DevKitC) lets the chip run instead of holding it in reset/download.
     */
    fun openPort(d: UsbDevice, baud: Int = ROM_BAUD, release: Boolean = false): UsbSerialPort? {
        val table = UsbSerialProber.getDefaultProbeTable()
            .apply { addProduct(UsbIds.ESPRESSIF, 0x1001, CdcAcmSerialDriver::class.java) }
        val driver = UsbSerialProber(table).probeDevice(d) ?: return null
        val conn = mgr.openDevice(d) ?: return null
        val port = driver.ports.firstOrNull() ?: return null
        port.open(conn)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        if (release && !UsbIds.isNativeUsb(d)) {
            runCatching { port.dtr = false; port.rts = false }
        }
        return port
    }

    companion object {
        const val ACTION_PERMISSION = "dev.retrovision.app.USB_PERMISSION"
        /** ROM bootloader speed (flashing). */
        const val ROM_BAUD = 115200
        /** Probe firmware speed on UART boards (classic ESP32 behind CP210x/CH340). */
        const val PROBE_BAUD = 921600
    }
}

/** [SerialLink] for the ROM flasher on top of usb-serial-for-android. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun write(data: ByteArray) {
        port.write(data, 5000)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, maxOf(1, timeoutMs))

    override fun setDtr(on: Boolean) {
        port.dtr = on
    }

    override fun setRts(on: Boolean) {
        port.rts = on
    }

    override fun discardInput() {
        val tmp = ByteArray(1024)
        var n = 0
        while (n++ < 50 && runCatching { port.read(tmp, 20) }.getOrDefault(0) > 0) { /* drain */ }
    }
}
