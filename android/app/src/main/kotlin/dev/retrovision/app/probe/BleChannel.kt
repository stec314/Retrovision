// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.probe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import dev.retrovision.core.wire.ReconnectPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * One GATT connection to a probe over the Nordic UART Service. Low-level only: the session
 * (handshake, protocol) runs on top via [ProbeSession], exactly as over USB.
 *
 * Android's GATT client accepts one operation at a time and silently drops (or reports "busy" for)
 * anything issued while another is pending. Every operation here therefore goes through [op]: one
 * at a time, each awaited on its callback with a timeout. Writes without response are awaited too:
 * Android signals in onCharacteristicWrite when the controller took the packet, which is its flow
 * control. A [BleChannel] is single use: after a disconnect it is closed and a new one is made, as
 * Android requires (a reused BluetoothGatt is the classic source of status 133 loops).
 *
 * Confidentiality is the BLE link's (LE Secure Connections bonding); the app-layer HMAC in the
 * handshake decides whether the probe streams anything.
 */
@SuppressLint("MissingPermission")
class BleChannel(private val ctx: Context) {
    companion object {
        val SVC: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // host -> probe (write)
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // probe -> host (notify)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val OP_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 4_000L
        private const val DIRECT_CONNECT_MS = 10_000L
        private const val BOND_TIMEOUT_MS = 30_000L

        private val adapterOf = { c: Context -> (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }

        /**
         * The device for a stored address: the bonded entry when there is one (it carries the right
         * address type), else a plain lookup. Null if Bluetooth is off or the address is invalid.
         */
        fun deviceFor(ctx: Context, address: String): BluetoothDevice? {
            val ad = adapterOf(ctx) ?: return null
            if (!ad.isEnabled || !BluetoothAdapter.checkBluetoothAddress(address)) return null
            return runCatching { ad.bondedDevices?.firstOrNull { it.address.equals(address, true) } }.getOrNull()
                ?: runCatching { ad.getRemoteDevice(address) }.getOrNull()
        }

        /** Forgets the phone's bond with [device] (hidden API, present on every Android so far). */
        fun removeBond(device: BluetoothDevice): Boolean =
            runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }.getOrDefault(false)
    }

    /** Why [open] failed, mapped onto the reconnect policy. */
    @Volatile var lastFailure: ReconnectPolicy.Failure = ReconnectPolicy.Failure.TRANSIENT
        private set

    @Volatile var onData: (ByteArray) -> Unit = {}
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var up = false
    @Volatile private var closed = false
    val connected get() = up && disconnectStatus < 0

    /** GATT status of the disconnect that ended this channel, -1 while it is open. */
    @Volatile var disconnectStatus = -1
        private set

    private val adapter get() = adapterOf(ctx)

    private val opLock = Mutex()
    @Volatile private var pending: Pending? = null
    private val connectedSignal = CompletableDeferred<Int>()   // GATT status of the connect
    private val outbox = Channel<ByteArray>(capacity = 256)

    private enum class OpKind { MTU, DISCOVER, DESCRIPTOR, WRITE, RSSI }
    private class Pending(val kind: OpKind, val done: CompletableDeferred<Int> = CompletableDeferred())

    /** A probe found by [scan]: the device, its signal, and every other Retrovision probe heard. */
    class ScanHit(val device: BluetoothDevice?, val rssi: Int, val others: Map<String, Int>)

    /** Human-readable reason of the last failure, for the Settings status line. */
    @Volatile var lastError: String = ""
        private set

    /** Called as the connection progresses ("bonding", "connecting"…), for the status line. */
    @Volatile var onStage: (String) -> Unit = {}

    // ---------------------------------------------------------------------------------------- scan

    /**
     * Scans for a probe advertising our service whose name matches "RV-<name>". The hit's device is
     * null on timeout; [ScanHit.others] lists other probes heard (e.g. one paired with another key).
     */
    suspend fun scan(name: String, timeoutMs: Long = 12_000): ScanHit {
        val ad = adapter
        if (ad == null || !ad.isEnabled) { lastError = "Bluetooth is off"; return ScanHit(null, 0, emptyMap()) }
        val scanner = ad.bluetoothLeScanner ?: run { lastError = "no BLE scanner"; return ScanHit(null, 0, emptyMap()) }
        val want = "RV-$name"
        val found = CompletableDeferred<Pair<BluetoothDevice, Int>?>()
        val others = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
                if (n == want || (name.isEmpty() && r.scanRecord?.serviceUuids?.contains(ParcelUuid(SVC)) == true)) {
                    if (!found.isCompleted) found.complete(r.device to r.rssi)
                } else {
                    others[n ?: r.device.address] = r.rssi
                }
            }
            override fun onScanFailed(code: Int) {
                lastError = scanFailText(code)
                Diag.w("ble", lastError)
                if (!found.isCompleted) found.complete(null)
            }
        }
        val started = ScanGate.start(scanner, cb)
        if (!started) { lastError = "cannot start a Bluetooth scan"; return ScanHit(null, 0, emptyMap()) }
        val hit = withTimeoutOrNull(timeoutMs) { found.await() }
        runCatching { scanner.stopScan(cb) }
        if (hit == null && lastError.isEmpty()) lastError = "“$want” not heard"
        return ScanHit(hit?.first, hit?.second ?: 0, HashMap(others))
    }

    /** Result of [scanMany]: the wanted probes found (name -> device, dBm) and any other probes heard. */
    class MultiHit(val found: Map<String, Pair<BluetoothDevice, Int>>, val others: Map<String, Int>)

    /**
     * One scan for several paired probes at once. Stops early when all are found. Scans go through
     * [ScanGate], which keeps the app under Android's limit of five scan starts per 30 s (beyond it
     * scans are silently dropped, and the probe "is never heard").
     */
    suspend fun scanMany(names: Set<String>, timeoutMs: Long = 10_000): MultiHit {
        lastError = ""
        val ad = adapter
        if (ad == null || !ad.isEnabled) { lastError = "Bluetooth is off"; return MultiHit(emptyMap(), emptyMap()) }
        val scanner = ad.bluetoothLeScanner ?: run { lastError = "no BLE scanner"; return MultiHit(emptyMap(), emptyMap()) }
        val want = names.associateBy { "RV-$it" }
        val found = java.util.concurrent.ConcurrentHashMap<String, Pair<BluetoothDevice, Int>>()
        val others = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val all = CompletableDeferred<Unit>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
                val name = n?.let { want[it] }
                if (name != null) {
                    found[name] = r.device to r.rssi
                    if (found.keys.containsAll(names) && !all.isCompleted) all.complete(Unit)
                } else {
                    others[n ?: r.device.address] = r.rssi
                }
            }
            override fun onScanFailed(code: Int) {
                lastError = scanFailText(code)
                Diag.w("ble", lastError)
                if (!all.isCompleted) all.complete(Unit)
            }
        }
        if (!ScanGate.start(scanner, cb)) { lastError = "cannot start a Bluetooth scan"; return MultiHit(emptyMap(), emptyMap()) }
        withTimeoutOrNull(timeoutMs) { all.await() }
        runCatching { scanner.stopScan(cb) }
        return MultiHit(HashMap(found), HashMap(others))
    }

    private fun scanFailText(code: Int) = "scan failed (code $code)" +
        if (code == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) ": toggle Bluetooth off and on" else ""

    // ---------------------------------------------------------------------------------- callbacks

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedSignal.complete(status)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                up = false
                disconnectStatus = status
                if (!connectedSignal.isCompleted) connectedSignal.complete(if (status == 0) -2 else status)
                // Fail whatever was in flight: no callback will ever come for it.
                pending?.done?.complete(-1)
                if (!closed) {
                    lastError = "disconnected: " + gattStatus(status)
                    Diag.i("ble", lastError)
                }
                // The writer may be suspended on an empty outbox: closing it wakes it up.
                outbox.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            finish(OpKind.MTU, status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(OpKind.DISCOVER, status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(OpKind.DESCRIPTOR, status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(OpKind.WRITE, status)

        override fun onReadRemoteRssi(g: BluetoothGatt, r: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) rssi = r
            finish(OpKind.RSSI, status)
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) phy = if (txPhy == BluetoothDevice.PHY_LE_2M && rxPhy == BluetoothDevice.PHY_LE_2M) "2M" else "1M"
        }

        // API 33+ delivers the value directly; older APIs use the deprecated getter.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX) onData(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return  // the new overload already delivered it
            if (c.uuid == TX) c.value?.let { onData(it) }
        }
    }

    private fun finish(kind: OpKind, status: Int) {
        val p = pending
        if (p != null && p.kind == kind) p.done.complete(status)
    }

    /**
     * Runs one GATT operation and waits for its callback. Returns the GATT status, or -1 if the
     * operation could not be issued, timed out, or the link dropped meanwhile.
     */
    private suspend fun op(kind: OpKind, timeoutMs: Long = OP_TIMEOUT_MS, issue: (BluetoothGatt) -> Boolean): Int = opLock.withLock {
        val g = gatt ?: return -1
        val p = Pending(kind)
        pending = p
        try {
            val issued = runCatching { issue(g) }.getOrDefault(false)
            if (!issued) return -1
            withTimeoutOrNull(timeoutMs) { p.done.await() } ?: -1
        } finally {
            pending = null
        }
    }

    /** Phy agreed for this connection ("1M"/"2M", "" until known). */
    @Volatile var phy: String = ""
        private set

    /** RSSI of the open connection as the phone hears it, refreshed by [readRssi]. */
    @Volatile var rssi: Int = 0
        private set

    // ------------------------------------------------------------------------------- connection

    /**
     * Connects to [device], sets the link up (MTU, 2M PHY, services, bond, notifications) and
     * returns true when it is ready for the protocol. [background] = Android autoConnect: no timeout
     * of its own, the controller connects whenever the probe advertises; [waitMs] bounds the wait.
     * On failure the channel is closed and [lastFailure]/[lastError] say why.
     */
    suspend fun open(device: BluetoothDevice, background: Boolean = false, waitMs: Long = DIRECT_CONNECT_MS): Boolean {
        lastError = ""
        val ok = try {
            setUp(device, background, waitMs) && disconnectStatus < 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            lastError = "setup error: ${e.message}"
            lastFailure = ReconnectPolicy.Failure.TRANSIENT
            false
        }
        if (!ok) close()
        up = ok
        return ok
    }

    private suspend fun setUp(device: BluetoothDevice, background: Boolean, waitMs: Long): Boolean {
        val ad = adapter
        if (ad == null || !ad.isEnabled) return fail(ReconnectPolicy.Failure.TRANSIENT, "Bluetooth is off")
        onStage(if (background) "waiting" else "connecting")
        gatt = if (Build.VERSION.SDK_INT >= 26) {
            device.connectGatt(ctx, background, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK)
        } else {
            device.connectGatt(ctx, background, cb, BluetoothDevice.TRANSPORT_LE)
        }
        if (gatt == null) return fail(ReconnectPolicy.Failure.TRANSIENT, "the phone refused to open a connection")

        val st = withTimeoutOrNull(waitMs) { connectedSignal.await() }
        if (st == null) {
            return fail(ReconnectPolicy.Failure.NOT_FOUND, if (background) "probe not in range" else "connection timed out: probe off or out of range")
        }
        if (st != BluetoothGatt.GATT_SUCCESS) {
            return fail(if (st == 5 || st == 15) ReconnectPolicy.Failure.AUTH else ReconnectPolicy.Failure.TRANSIENT, "connect failed: " + gattStatus(st))
        }
        Diag.i("ble", "connected to ${device.address}, setting up")
        onStage("connecting")
        val g = gatt ?: return fail(ReconnectPolicy.Failure.TRANSIENT, "connection lost")

        // A bond already in progress (the phone started it on connect) must finish before any
        // other operation, or discovery and writes fail intermittently.
        if (device.bondState == BluetoothDevice.BOND_BONDING && !awaitBond(device)) {
            return fail(ReconnectPolicy.Failure.AUTH, bondFailText(device))
        }

        // Fast connection interval while setting up; relaxed once streaming (see [relax]).
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }

        // MTU first: the probe batches frames into one notification per MTU.
        // Some stacks never answer the MTU request: go on with the default after a few seconds.
        if (op(OpKind.MTU, 4_000) { it.requestMtu(247) } != BluetoothGatt.GATT_SUCCESS) {
            Diag.w("ble", "no MTU answer, staying at $mtu")
        }
        if (disconnectStatus >= 0) return failDropped()

        // 2M PHY: same data in half the air time, which leaves more for the probe's Wi-Fi listening.
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
        }

        val found = discover()
        if (found == null) {
            // No answer to discovery: the probe is busy or the link is bad. Not a cache problem,
            // and refreshing the cache now makes some phones (Samsung) drop the link: just retry.
            if (disconnectStatus >= 0) return failDropped()
            return fail(ReconnectPolicy.Failure.TRANSIENT, "the probe did not answer service discovery")
        }
        if (!found) {
            // A re-flashed probe can change its attribute table while the phone keeps the old one
            // cached for the bond: refresh the cache and look again before giving up.
            Diag.w("ble", "service not found, refreshing the GATT cache")
            refreshCache(g)
            delay(300)
            if (discover() != true) {
                if (disconnectStatus >= 0) return failDropped()
                return fail(ReconnectPolicy.Failure.NO_SERVICE, "probe service not found: old firmware, or the probe is not set up for Bluetooth")
            }
        }

        // The RX characteristic needs an encrypted link: bond on this connection if needed.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            onStage("bonding")
            Diag.i("ble", "bonding with ${device.address}")
            runCatching { device.createBond() }
            if (!awaitBond(device)) return if (disconnectStatus >= 0 && disconnectStatus !in setOf(5, 6, 15, 61, 137)) failDropped() else fail(ReconnectPolicy.Failure.AUTH, bondFailText(device))
        }

        val tx = g.getService(SVC)?.getCharacteristic(TX) ?: return fail(ReconnectPolicy.Failure.NO_SERVICE, "probe TX characteristic missing")
        rxChar = g.getService(SVC)?.getCharacteristic(RX)
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD) ?: return fail(ReconnectPolicy.Failure.NO_SERVICE, "probe notification descriptor missing")
        val rc = op(OpKind.DESCRIPTOR) {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                it.writeDescriptor(cccd)
            }
        }
        if (rc != BluetoothGatt.GATT_SUCCESS) {
            val auth = rc == 5 || rc == 15 || rc == 137
            return fail(if (auth) ReconnectPolicy.Failure.AUTH else ReconnectPolicy.Failure.TRANSIENT, "could not subscribe to the probe: " + gattStatus(rc))
        }
        if (disconnectStatus >= 0) return failDropped()
        Diag.i("ble", "link ready: MTU $mtu")
        return true
    }

    /** true = service found, false = discovery answered without it, null = no answer. */
    private suspend fun discover(): Boolean? {
        if (op(OpKind.DISCOVER, 15_000) { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) return null
        val svc = gatt?.getService(SVC) ?: return false
        return svc.getCharacteristic(RX) != null && svc.getCharacteristic(TX) != null
    }

    private suspend fun awaitBond(device: BluetoothDevice): Boolean {
        var sawBonding = device.bondState == BluetoothDevice.BOND_BONDING
        return withTimeoutOrNull(BOND_TIMEOUT_MS) {
            while (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (device.bondState == BluetoothDevice.BOND_BONDING) sawBonding = true
                // Back to NONE after trying: refused, timed out, or a stale key on one side.
                if (sawBonding && device.bondState == BluetoothDevice.BOND_NONE) return@withTimeoutOrNull false
                if (disconnectStatus >= 0) return@withTimeoutOrNull false
                delay(200)
            }
            true
        } ?: false
    }

    private fun bondFailText(device: BluetoothDevice) =
        "Bluetooth pairing failed or was not accepted. If the probe was re-flashed or re-paired, remove “" +
            (runCatching { device.name }.getOrNull() ?: device.address) + "” in the phone's Bluetooth settings and retry"

    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            .onFailure { Diag.w("ble", "GATT cache refresh unavailable: ${it.message}") }
    }

    /**
     * The link dropped during setup. Some statuses mean the two sides disagree on the bond: the
     * probe lost its keys (re-flash), or the phone's are stale (key missing 6, MIC failure 61).
     */
    private fun failDropped(): Boolean {
        val auth = disconnectStatus in setOf(5, 6, 15, 61, 137)
        return fail(if (auth) ReconnectPolicy.Failure.AUTH else ReconnectPolicy.Failure.TRANSIENT, lastError.ifEmpty { "link dropped during setup" })
    }

    private fun fail(kind: ReconnectPolicy.Failure, msg: String): Boolean {
        lastFailure = kind
        lastError = msg
        Diag.w("ble", "setup failed (${kind.name.lowercase()}): $msg")
        return false
    }

    /** Streaming: the probe asks for its own interval (30-50 ms); release the fast setup one. */
    fun relax() {
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
    }

    suspend fun readRssi() {
        if (up) op(OpKind.RSSI, 3_000) { it.readRemoteRssi() }
    }

    /**
     * Standby keep-alive: one COBS delimiter byte, which the probe's decoder skips. It shows the
     * probe that the phone is still there, so its "silent connection" rule leaves an idle link up.
     */
    fun keepAlive() = write(byteArrayOf(0))

    /** Queues a frame; the writer loop drains it, chunked to the MTU, without response. */
    fun write(data: ByteArray) {
        if (outbox.trySend(data).isFailure && up) Diag.w("ble", "write queue full, frame dropped")
    }

    /**
     * Drains the outbox until the link drops. Runs on its own coroutine. Each chunk waits for
     * Android's write callback; a write that cannot get through for [WRITE_TIMEOUT_MS] means the
     * link is dead even if no disconnect has been reported yet, so it is closed.
     */
    suspend fun writerLoop() {
        while (coroutineContext.isActive && up) {
            val frame = outbox.receiveCatching().getOrNull() ?: break
            val rx = rxChar ?: break
            val chunk = (mtu - 3).coerceAtLeast(20)
            var off = 0
            while (off < frame.size && up) {
                val n = minOf(chunk, frame.size - off)
                val part = frame.copyOfRange(off, off + n)
                val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
                var rc: Int
                do {
                    rc = op(OpKind.WRITE, WRITE_TIMEOUT_MS) { g ->
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(rx, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            rx.value = part
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(rx)
                        }
                    }
                    if (rc == BluetoothGatt.GATT_SUCCESS) break
                    // Controller buffers full: back off briefly and retry this chunk.
                    delay(10)
                } while (up && System.currentTimeMillis() < deadline)
                if (rc != BluetoothGatt.GATT_SUCCESS) {
                    if (up) {
                        lastError = "writes to the probe stalled: link dropped"
                        Diag.w("ble", lastError)
                        runCatching { gatt?.disconnect() }
                        up = false
                    }
                    return
                }
                off += n
            }
        }
    }

    fun close() {
        closed = true
        up = false
        runCatching { outbox.close() }
        pending?.done?.complete(-1)
        val g = gatt
        gatt = null
        rxChar = null
        if (!connectedSignal.isCompleted) connectedSignal.complete(-2)
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
    }

    /** Mtu agreed for this connection (payload per packet = mtu - 3). */
    val currentMtu get() = mtu
}

/**
 * Android allows an app five scan starts per 30 s; a sixth is silently ignored (no error, no
 * results). Every scan of the app's own probe link goes through here and is spaced accordingly.
 */
internal object ScanGate {
    private val lock = Mutex()
    private val starts = ArrayDeque<Long>()
    private const val WINDOW_MS = 30_000L
    private const val MAX_STARTS = 4   // one left for the phone-radio scanner

    @SuppressLint("MissingPermission")
    suspend fun start(scanner: android.bluetooth.le.BluetoothLeScanner, cb: ScanCallback): Boolean = lock.withLock {
        val now = System.currentTimeMillis()
        while (starts.isNotEmpty() && now - starts.first() > WINDOW_MS) starts.removeFirst()
        if (starts.size >= MAX_STARTS) delay(WINDOW_MS - (now - starts.first()) + 100)
        starts.addLast(System.currentTimeMillis())
        while (starts.size > MAX_STARTS) starts.removeFirst()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleChannel.SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }
            .onFailure { Diag.w("ble", "cannot scan: ${it.message}") }
            .isSuccess
    }
}

/** GATT status codes as people meet them. */
fun gattStatus(status: Int): String = when (status) {
    0 -> "normal"
    -1 -> "no answer from the probe"
    5, 15 -> "status $status, authentication/encryption refused: remove the probe in the phone's Bluetooth settings and retry"
    8 -> "status 8, link timeout (out of range or probe off)"
    19 -> "status 19, the probe closed the link"
    22 -> "status 22, closed by the phone"
    62 -> "status 62, connection failed to establish"
    133 -> "status 133, generic GATT error (usually transient: retrying)"
    137 -> "status 137, authentication failed"
    147 -> "status 147, connection timed out"
    else -> "status $status"
}
