// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.probe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.retrovision.app.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * One GATT connection to a probe over the Nordic UART Service. Low-level only: the session (handshake,
 * protocol) runs on top via [ProbeSession], exactly as over USB. Writes are chunked to the MTU and
 * sent without response; notifications on the TX characteristic are handed to [onData].
 *
 * Confidentiality is the BLE link's (LE Secure Connections bonding); the app-layer HMAC in the
 * handshake decides whether the probe streams anything. This whole path needs validation on real
 * hardware: BLE pairing, bonding and MTU behaviour vary by phone.
 */
@SuppressLint("MissingPermission")
class BleChannel(private val ctx: Context) {
    companion object {
        val SVC: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // host -> probe (write)
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // probe -> host (notify)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile var onData: (ByteArray) -> Unit = {}
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var up = false
    val connected get() = up

    private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val servicesReady = CompletableDeferred<Boolean>()
    private val outbox = Channel<ByteArray>(capacity = 256)

    /** Scans for a probe advertising our service whose name matches "RV-<name>". Null on timeout. */
    suspend fun scan(name: String, timeoutMs: Long = 12_000): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val want = "RV-$name"
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: r.device.name
                if (n == want || (name.isEmpty() && r.scanRecord?.serviceUuids?.contains(ParcelUuid(SVC)) == true)) {
                    if (!found.isCompleted) found.complete(r.device)
                }
            }
            override fun onScanFailed(code: Int) {
                Diag.w("ble", "scan failed: $code")
                if (!found.isCompleted) found.complete(null)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }
            .onFailure { Diag.w("ble", "startScan: ${it.message}"); return null }
        val dev = withTimeoutOrNull(timeoutMs) { found.await() }
        runCatching { scanner.stopScan(cb) }
        return dev
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Diag.i("ble", "connected, requesting MTU")
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                up = false
                if (!servicesReady.isCompleted) servicesReady.complete(false)
                Diag.i("ble", "disconnected (status $status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) m else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC)
            val rx = svc?.getCharacteristic(RX)
            val tx = svc?.getCharacteristic(TX)
            if (rx == null || tx == null) {
                Diag.w("ble", "NUS service/characteristics not found")
                servicesReady.complete(false)
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                servicesReady.complete(true)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD && !servicesReady.isCompleted) servicesReady.complete(true)
        }

        // API 33+ delivers the value directly; older APIs use the deprecated getter.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX) onData(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == TX) c.value?.let { onData(it) }
        }
    }

    /** Connects to [device], discovers the service and enables notifications. Bonds if needed. */
    suspend fun open(device: BluetoothDevice): Boolean {
        // Bond first so the encrypted RX characteristic is writable right away.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            runCatching { device.createBond() }
            withTimeoutOrNull(20_000) {
                while (device.bondState != BluetoothDevice.BOND_BONDED) {
                    if (device.bondState == BluetoothDevice.BOND_NONE && coroutineContext.isActive) delay(300) else delay(300)
                }
            }
        }
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(20_000) { servicesReady.await() } ?: false
        up = ok && gatt != null && rxChar != null
        return up
    }

    /** Queues a frame; the writer loop drains it, chunked to the MTU, without response. */
    fun write(data: ByteArray) {
        outbox.trySend(data)
    }

    /** Drains the outbox until the link drops. Runs on its own coroutine. */
    suspend fun writerLoop() {
        while (coroutineContext.isActive && up) {
            val frame = outbox.receive()
            val g = gatt ?: break
            val rx = rxChar ?: break
            val chunk = (mtu - 3).coerceAtLeast(20)
            var off = 0
            while (off < frame.size) {
                val n = minOf(chunk, frame.size - off)
                val part = frame.copyOfRange(off, off + n)
                val rc = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(rx, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        rx.value = part
                        if (g.writeCharacteristic(rx)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    }
                }
                if (rc != BluetoothGatt.GATT_SUCCESS) {
                    // Controller buffers full: back off briefly and retry this chunk.
                    delay(8)
                    continue
                }
                off += n
                // Pace writes without response so the controller queue does not overflow.
                if (off < frame.size) delay(3)
            }
        }
    }

    fun close() {
        up = false
        runCatching { outbox.close() }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rxChar = null
    }
}
