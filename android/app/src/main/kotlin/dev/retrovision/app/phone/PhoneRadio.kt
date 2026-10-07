// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.ParcelUuid
import android.os.SystemClock
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleAdvType
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

/** Receiver id used for sightings heard by the phone itself (vs a probe's hardware id). */
const val PHONE_SOURCE = "phone"

/**
 * The phone's own Bluetooth LE radio as a second receiver. It sees full advertisements, like the
 * probe, so trackers, Remote ID over Bluetooth, BLE spam and notable devices work without a
 * probe. On phones with LE Coded PHY it also hears Bluetooth 5 Long Range, which the probe doesn't.
 * Limits: Android throttles scans and pauses UNFILTERED scans while the screen is off. So a second,
 * filtered scan runs alongside, for what matters most (trackers, Remote ID, pop-up spam): Android
 * keeps filtered scans going with the screen off. Field data: with the phone in a pocket at night and
 * the only probe on a Bluetooth link (which does not scan), nothing listened to Bluetooth for 30% of
 * the recorded time.
 */
class PhoneBle(private val ctx: Context, private val onSighting: (Sighting) -> Unit) {
    val active = MutableStateFlow(false)
    /** Elapsed-realtime before which start() must not retry (after a failure). */
    @Volatile var retryAfter = 0L
    val heard = MutableStateFlow(0L)
    val codedPhy = MutableStateFlow(false)

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val recent = HashMap<Long, Long>() // (address, payload) hash -> last ms
    private var lastPrune = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            active.value = false
            // Android allows ~5 scan starts per 30 s per app; retrying faster gets us blocked.
            retryAfter = SystemClock.elapsedRealtime() + 60_000L
        }
    }

    /** The screen-off scan: same handling, its own callback (a failure here keeps the main scan). */
    private val filteredCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { filteredActive = false }
    }
    @Volatile private var filteredActive = false

    /**
     * What keeps being heard with the screen off. Kept under ~16 filters (the controller's
     * hardware filter table on many phones): Apple and Samsung by company id (Find My, SmartThings
     * Find, iPhones, Galaxy devices), and service data of the tracker networks, drone Remote ID and
     * Fast Pair (the pop-up spam vector).
     */
    private fun trackerFilters(): List<ScanFilter> {
        fun uuid16(u: Int) = ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(u))
        val out = ArrayList<ScanFilter>()
        for (company in intArrayOf(0x004C, 0x0075)) out += ScanFilter.Builder().setManufacturerData(company, ByteArray(0)).build()
        // FEAA Google Find Hub/Eddystone, FD5A SmartTag, FD69 Samsung Find, FEED Tile, FE33 Chipolo,
        // FA25 Pebblebee, FCB2 DULT (any compliant tag), FFFA drone Remote ID, FE2C Fast Pair.
        for (u in intArrayOf(0xFEAA, 0xFD5A, 0xFD69, 0xFEED, 0xFE33, 0xFA25, 0xFCB2, 0xFFFA, 0xFE2C)) {
            out += ScanFilter.Builder().setServiceData(uuid16(u), ByteArray(0)).build()
        }
        // Tile and Chipolo also list their UUID without service data.
        for (u in intArrayOf(0xFEED, 0xFE33)) out += ScanFilter.Builder().setServiceUuid(uuid16(u)).build()
        return out
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active.value) return true
        if (SystemClock.elapsedRealtime() < retryAfter) return false
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        val scanner = a.bluetoothLeScanner ?: return false
        val coded = runCatching { a.isLeCodedPhySupported }.getOrDefault(false)
        val ext = runCatching { a.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .apply {
                if (ext || coded) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val ok = runCatching { scanner.startScan(null, settings, callback) }
            .onSuccess { active.value = true; codedPhy.value = coded }
            .isSuccess
        // Duplicates with the main scan while the screen is on are dropped by [handle] (same
        // address and bytes within 1 s).
        if (ok && !filteredActive) {
            filteredActive = runCatching { scanner.startScan(trackerFilters(), settings, filteredCallback) }.isSuccess
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!active.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        if (filteredActive) runCatching { adapter?.bluetoothLeScanner?.stopScan(filteredCallback) }
        filteredActive = false
        active.value = false
    }

    private fun handle(r: ScanResult) {
        val bytes = r.scanRecord?.bytes ?: return
        val addr = runCatching { MacAddress.parse(r.device.address) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val t = now - (SystemClock.elapsedRealtimeNanos() - r.timestampNanos) / 1_000_000
        // Same advert from the same address within 1 s: drop, like the probe's dedup.
        val key = addr.bits * 31 + bytes.contentHashCode()
        synchronized(recent) {
            val last = recent[key]
            if (last != null && t - last < 1_000) return
            recent[key] = t
            if (now - lastPrune > 30_000) {
                lastPrune = now
                recent.values.removeAll { now - it > 5_000 }
            }
        }
        val advType = when {
            !r.isLegacy -> BleAdvType.EXTENDED
            r.isConnectable -> BleAdvType.ADV_IND
            else -> BleAdvType.ADV_NONCONN_IND
        }
        val tx = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT } ?: 0
        heard.value = heard.value + 1
        onSighting(
            // Android reports 127 when the RSSI is unavailable: store it as unknown (0), not +127 dBm.
            Sighting(t, Radio.BLE, addr, if (r.rssi >= 20) 0 else r.rssi, ble = BleDetail(addressKind(r.device, addr), advType, bytes, tx), probeId = PHONE_SOURCE),
        )
    }

    /**
     * Android exposes public-vs-random only on recent versions (reflection keeps older SDKs
     * compiling). Random sub-types come from the top two address bits (Core spec Vol 6 B 1.3).
     */
    private fun addressKind(d: BluetoothDevice, a: MacAddress): BleAddressKind {
        val type = runCatching { d.javaClass.getMethod("getAddressType").invoke(d) as Int }.getOrNull()
        val top = a.octet(0) ushr 6
        fun random() = when (top) {
            3 -> BleAddressKind.RANDOM_STATIC
            1 -> BleAddressKind.RANDOM_RESOLVABLE
            0 -> BleAddressKind.RANDOM_NON_RESOLVABLE
            else -> BleAddressKind.UNKNOWN
        }
        return when (type) {
            0 -> BleAddressKind.PUBLIC
            1 -> random()
            // Unknown: resolvable private (01) is by far the most common rotating kind on phones.
            else -> if (top == 1) BleAddressKind.RANDOM_RESOLVABLE else BleAddressKind.UNKNOWN
        }
    }
}

/** What the accelerometer says about you. RESTING and HANDHELD both mean "not going anywhere". */
enum class MotionState { UNKNOWN, RESTING, HANDHELD, MOVING }

/**
 * "Are you going anywhere?" from the accelerometer: the spread of acceleration magnitude over
 * the last [windowMs].
 *  - resting on a table: almost flat (< 0.12 m/s²);
 *  - in your hand on the sofa: tremor and taps, but small (< 0.8 m/s²);
 *  - walking: every step is a jolt of 1–3 m/s², the spread is well above that.
 * Driving can look calm: that's why the drift guard also requires a low GPS Doppler speed.
 */
class MotionMonitor(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acc: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val windowMs = 20_000L
    private val restingStd = 0.12 // m/s²
    private val handheldStd = 0.8 // m/s²

    /** True when you are not travelling (resting or handheld). */
    val still = MutableStateFlow(false)
    val state = MutableStateFlow(MotionState.UNKNOWN)
    val available get() = acc != null

    fun start() {
        acc ?: return
        // Batched delivery: the sensor hub buffers, the CPU wakes rarely.
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val now = e.timestamp / 1_000_000L // event time (elapsed-realtime ns), not batch delivery time
        samples.addLast(now to m)
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 20 || now - samples.first().first < windowMs * 3 / 4) {
            still.value = false
            state.value = MotionState.UNKNOWN
            return
        }
        val mean = samples.sumOf { it.second.toDouble() } / samples.size
        val std = sqrt(samples.sumOf { (it.second - mean) * (it.second - mean) } / samples.size)
        state.value = when {
            std < restingStd -> MotionState.RESTING
            std < handheldStd -> MotionState.HANDHELD
            else -> MotionState.MOVING
        }
        still.value = state.value != MotionState.MOVING
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
