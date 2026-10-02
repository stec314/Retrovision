package dev.retrovision.app.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
 * Limits: Android throttles scans and may pause unfiltered scans with the screen off.
 */
class PhoneBle(private val ctx: Context, private val onSighting: (Sighting) -> Unit) {
    val active = MutableStateFlow(false)
    val heard = MutableStateFlow(0L)
    val codedPhy = MutableStateFlow(false)

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val recent = HashMap<Long, Long>() // (address, payload) hash -> last ms
    private var lastPrune = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { active.value = false }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active.value) return true
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
        return runCatching { scanner.startScan(null, settings, callback) }
            .onSuccess { active.value = true; codedPhy.value = coded }
            .isSuccess
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!active.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
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
            Sighting(t, Radio.BLE, addr, r.rssi, ble = BleDetail(addressKind(r.device, addr), advType, bytes, tx), probeId = PHONE_SOURCE),
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
        val now = SystemClock.elapsedRealtime()
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
