// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dev.retrovision.app.AnalysisLoad
import dev.retrovision.app.Collector
import dev.retrovision.app.Diag
import dev.retrovision.app.RadarBlip
import dev.retrovision.app.RadarFrame
import dev.retrovision.app.ConnectionUi
import dev.retrovision.app.Link
import dev.retrovision.app.R
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.FixRow
import dev.retrovision.app.data.SessionRecorder
import dev.retrovision.app.data.SightingRow
import dev.retrovision.app.data.toFix
import dev.retrovision.app.data.toModel
import dev.retrovision.app.data.FamiliarRow
import dev.retrovision.app.data.toRow
import dev.retrovision.app.data.toSighting
import dev.retrovision.app.probe.ProbeSession
import dev.retrovision.app.probe.ProbeTransport
import dev.retrovision.app.probe.UsbAccess
import dev.retrovision.app.ui.MainActivity
import dev.retrovision.app.ui.Texts
import dev.retrovision.core.analysis.AnalysisConfig
import dev.retrovision.core.analysis.Analyzer
import dev.retrovision.core.analysis.EntitySighting
import dev.retrovision.core.analysis.IgnoreList
import dev.retrovision.core.identity.EntityResolver
import dev.retrovision.core.model.Sighting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import dev.retrovision.core.analysis.BearingEstimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service: owns the USB link to the probe, the phone's GPS, the database writer
 * and the periodic analysis. The probe only listens (it never transmits); everything that
 * leaves the phone is an explicit user action (lookups), never this service.
 */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as RetrovisionApp
    private val prefs get() = app.prefs
    private val resolver = EntityResolver()
    private val queue = Channel<SightingRow>(capacity = 16_384)

    /** Repeats collapsed before storage: one row per device/kind/SSID per 10 s (see SightingBuckets). */
    private val storeBuckets = dev.retrovision.core.analysis.SightingBuckets(STORE_BUCKET_MS)

    /** The analysis window, kept in memory: no database scan every minute. */
    private val liveWindow = dev.retrovision.core.analysis.SightingBuckets(WINDOW_BUCKET_MS, MAX_ANALYSIS_ROWS)

    /** Live sightings at or after this time go to [liveWindow]; older ones come from the database. */
    @Volatile private var liveFromMs = Long.MAX_VALUE

    /** Every frame of the last few minutes, for attack and drone detection (they count frames). */
    private val recentRaw = ArrayDeque<Sighting>()
    private var locationManager: LocationManager? = null
    private var lastFixWritten = 0L
    private val notifiedAt = HashMap<String, Long>()
    private val notifiedScore = HashMap<String, Double>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Collector.running.value) {
            startInForeground() // a repeated start still has to satisfy startForegroundService()
            return START_NOT_STICKY
        }
        createChannels()
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        Collector.running.value = true
        Diag.i("service", "collection started")
        Collector.probeLedOn.value = app.prefs.probeLedOn
        Collector.captureDataFrames.value = app.prefs.captureDataFrames
        startLocation()
        registerWifiCallback()
        scope.launch { connectionLoop() }
        scope.launch { writerLoop() }
        scope.launch { storeFlushLoop() }
        scope.launch { analysisLoop() }
        scope.launch { radarLoop() }
        scope.launch { probeWatchLoop() }
        scope.launch { phoneLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionRecorder.stop()
        Diag.i("service", "collection stopped")
        Collector.running.value = false
        Collector.session = null
        Collector.connection.value = ConnectionUi(Link.STOPPED)
        runCatching { locationManager?.removeUpdates(locationListener) }
        phoneBle?.stop()
        motion?.stop()
        netCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        scope.cancel()
        // Don't lose the last few seconds: open slots and anything still queued go to the database.
        val pending = ArrayList<SightingRow>()
        while (true) pending += queue.tryReceive().getOrNull() ?: break
        storeBuckets.drainAll().mapTo(pending) { it.sighting.toRow(it.entityId) }
        if (pending.isNotEmpty()) {
            val dao = app.db.dao()
            Thread {
                runCatching { kotlinx.coroutines.runBlocking { dao.insertSightings(pending) } }
                    .onFailure { Diag.e("db", "final flush of ${pending.size} rows failed", it) }
            }.start()
        }
        super.onDestroy()
    }

    // ---- foreground ------------------------------------------------------------

    private fun startInForeground(): Boolean {
        val n = ongoingNotification(Texts.notifRunning())
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLoc) return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ONGOING, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIF_ONGOING, n)
            }
            true
        } catch (_: SecurityException) {
            // connectedDevice needs a granted USB device on Android 14+; location alone is still useful.
            runCatching {
                startForeground(NOTIF_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                true
            }.getOrDefault(false)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, Texts.channelOngoing(), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, Texts.channelAlerts(), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS_SILENT, Texts.channelAlertsSilent(), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoingNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CollectorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Retrovision")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .addAction(0, Texts.stop(), stop)
            .build()
    }

    // ---- location --------------------------------------------------------------

    private val locationListener = LocationListener { loc: Location -> onLocation(loc) }

    private fun startLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locationListener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
        }
    }

    private fun onLocation(loc: Location) {
        // A fix worse than 150 m would smear places together; ignore it.
        if (loc.hasAccuracy() && loc.accuracy > 150f) return
        val now = System.currentTimeMillis()
        if (now - lastFixWritten < 5000) return
        val fix = dev.retrovision.core.model.GeoFix(
            now, loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 0f,
            if (loc.hasSpeed()) loc.speed else null,
        )
        // Silent drift: the phone is still (accelerometer + Doppler) but the position wanders.
        if (prefs.driftGuard && motion?.available == true && !driftGuard.accept(fix, Collector.phoneStill.value)) {
            Collector.driftRejected.value = driftGuard.rejected
            return
        }
        lastFixWritten = now
        Collector.location.value = fix
        SessionRecorder.write(fix)
        scope.launch {
            app.db.dao().insertFix(FixRow(fix.timeMs, fix.lat, fix.lon, fix.accuracyM, fix.speedMps ?: -1f))
        }
    }

    // ---- phone receivers -------------------------------------------------------

    private val driftGuard = dev.retrovision.core.analysis.DriftGuard()
    private var phoneBle: dev.retrovision.app.phone.PhoneBle? = null
    private var motion: dev.retrovision.app.phone.MotionMonitor? = null

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Keeps the phone's BLE scanner and motion sensor in the state the settings ask for. */
    private suspend fun phoneLoop() {
        motion = dev.retrovision.app.phone.MotionMonitor(this).also { m ->
            m.start()
            scope.launch { m.still.collect { Collector.phoneStill.value = it } }
            scope.launch { m.state.collect { Collector.phoneMotion.value = it } }
        }
        val ble = dev.retrovision.app.phone.PhoneBle(this, ::onSighting).also { phoneBle = it }
        scope.launch { ble.active.collect { Collector.phoneBleActive.value = it } }
        scope.launch { ble.heard.collect { Collector.phoneBleHeard.value = it } }
        scope.launch { ble.codedPhy.collect { Collector.phoneCodedPhy.value = it } }
        var probeGoneSince = 0L
        while (scope.isActive) {
            val streaming = Collector.connection.value.session?.phase == dev.retrovision.app.probe.Phase.STREAMING
            val nowMs = System.currentTimeMillis()
            probeGoneSince = if (streaming) 0L else if (probeGoneSince == 0L) nowMs else probeGoneSince
            val permitted = if (Build.VERSION.SDK_INT >= 31) hasPerm(Manifest.permission.BLUETOOTH_SCAN) else true
            val want = permitted && when (prefs.phoneBleMode) {
                2 -> true
                1 -> !streaming && nowMs - probeGoneSince >= 30_000L // don't flap on brief USB re-enumerations
                else -> false
            }
            if (want && !ble.active.value) ble.start() else if (!want && ble.active.value) ble.stop()
            delay(5_000)
        }
    }

    // ---- probe link ------------------------------------------------------------

    private class RSample(val lat: Double, val lon: Double, val rssi: Int, val ms: Long, val source: String)
    private val radarLock = Any()
    private val radarBuf = HashMap<String, ArrayDeque<RSample>>()

    /** (address, payload) -> arrival time of BLE adverts heard by the probe, for phone de-duplication. */
    private val probeHeard = HashMap<Long, Long>()
    private var probeHeardPrune = 0L

    /** True if this is a phone advert the probe already delivered: same bytes, same device, ≤ 2 s ago. */
    private fun duplicateOfProbe(s: Sighting): Boolean {
        val b = s.ble ?: return false
        val key = s.address.bits * 1_000_003L + b.advData.contentHashCode()
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(probeHeard) {
            if (now - probeHeardPrune > 10_000L) {
                probeHeardPrune = now
                probeHeard.values.removeAll { now - it > 5_000L }
            }
            if (s.probeId != dev.retrovision.app.phone.PHONE_SOURCE) {
                probeHeard[key] = now
                return false
            }
            val t = probeHeard[key] ?: return false
            return now - t <= 2_000L
        }
    }

    private fun onSighting(s: Sighting) {
        if (duplicateOfProbe(s)) return
        SessionRecorder.write(s)
        // "Identify my phone": a scan was just triggered; this phone's probe requests are the loudest.
        if (System.currentTimeMillis() < Collector.calibrateUntilMs && s.probeId != dev.retrovision.app.phone.PHONE_SOURCE) {
            val w = s.wifi
            if (w != null && w.kind == dev.retrovision.core.model.WifiKind.PROBE_REQ && s.rssi >= -45) {
                dev.retrovision.core.identity.WifiFingerprint.of(w.ies)?.let { fp ->
                    Collector.calibrated.value = Collector.calibrated.value + fp
                }
            }
        }
        val res = synchronized(resolver) { resolver.resolve(s) }
        if (s.rssi != 0) {
            val fix = Collector.location.value
            // Radar bearing comes from how RSSI changes as you move: a drifting fix would invent a direction.
            if (fix != null && fix.accuracyM <= prefs.maxFixAccuracyM && System.currentTimeMillis() - fix.timeMs < 15_000L) {
                synchronized(radarLock) {
                    val dq = radarBuf.getOrPut(res.entityId) { ArrayDeque() }
                    dq.addLast(RSample(fix.lat, fix.lon, s.rssi, s.timeMs, s.probeId))
                    while (dq.size > 60) dq.removeFirst()
                }
            }
        }
        val es = dev.retrovision.core.analysis.EntitySighting(res.entityId, s)
        storeBuckets.add(es)
        if (s.timeMs >= liveFromMs) liveWindow.add(es)
        synchronized(recentRaw) {
            recentRaw.addLast(s)
            while (recentRaw.isNotEmpty() && s.timeMs - recentRaw.first().timeMs > RECENT_RAW_MS) recentRaw.removeFirst()
            while (recentRaw.size > RECENT_RAW_MAX) recentRaw.removeFirst()
        }
    }

    /** Builds the live radar frame: smoothed RSSI as distance, movement-derived bearing when usable. */
    private suspend fun radarLoop() {
        val smooth = HashMap<String, Double>()
        while (scope.isActive) {
            delay(1500)
            val now = System.currentTimeMillis()
            val reports = Collector.analysis.value?.entities.orEmpty().associateBy { it.entityId }
            val blips = ArrayList<RadarBlip>()
            var movedM = 0.0
            synchronized(radarLock) {
                val it = radarBuf.iterator()
                while (it.hasNext()) {
                    val (id, dq) = it.next()
                    while (dq.isNotEmpty() && now - dq.first().ms > 120_000L) dq.removeFirst()
                    if (dq.isEmpty()) { it.remove(); smooth.remove(id); continue }
                    // One receiver only: probe and phone antennas read different dBm for the same device.
                    val src = dq.groupingBy { s -> s.source }.eachCount().maxBy { e -> e.value }.key
                    val mine = dq.filter { s -> s.source == src }
                    val recent = mine.filter { now - it.ms <= 20_000L }
                    if (recent.isEmpty()) continue
                    val sm = BearingEstimator.ewma(smooth[id], recent.last().rssi).also { smooth[id] = it }

                    // bearing from the last ~90 s of movement
                    val win = mine.filter { now - it.ms <= 90_000L }
                    val lat0 = win.map { it.lat }.average()
                    val lon0 = win.map { it.lon }.average()
                    val cosL = Math.cos(Math.toRadians(lat0))
                    val samples = win.map {
                        BearingEstimator.Sample((it.lon - lon0) * cosL * 111_320.0, (it.lat - lat0) * 111_320.0, it.rssi, it.ms)
                    }
                    val est = BearingEstimator.estimate(samples)
                    val spreadE = samples.maxOfOrNull { it.east }?.minus(samples.minOfOrNull { it.east } ?: 0.0) ?: 0.0
                    val spreadN = samples.maxOfOrNull { it.north }?.minus(samples.minOfOrNull { it.north } ?: 0.0) ?: 0.0
                    movedM = maxOf(movedM, Math.hypot(spreadE, spreadN))

                    val rep = reports[id]
                    blips += RadarBlip(
                        entityId = id,
                        label = rep?.let { Texts.entityLabel(it) } ?: id,
                        rssi = sm,
                        category = rep?.category ?: dev.retrovision.core.identity.DeviceCategory.BLE_OTHER,
                        alert = rep?.alert == true,
                        bearingDeg = est?.takeIf { it.confidence >= 0.4 }?.bearingDeg,
                        bearingConf = est?.confidence ?: 0.0,
                    )
                }
            }
            blips.sortByDescending { it.rssi }
            Collector.liveRadar.value = RadarFrame(blips.take(40), movedM, now)
        }
    }

    private suspend fun connectionLoop() {
        val usb = UsbAccess(this)
        while (scope.isActive) {
            if (Collector.usbPaused.get()) {
                Collector.connection.value = ConnectionUi(Link.CONNECTING, error = "")
                delay(500)
                continue
            }
            val dev = usb.findProbe()
            if (dev == null) {
                Collector.connection.value = ConnectionUi(Link.NO_DEVICE)
                delay(1000)
                continue
            }
            if (!usb.hasPermission(dev)) {
                Collector.connection.value = ConnectionUi(Link.NEED_PERMISSION, dev.productName ?: "USB")
                if (!usb.ensurePermission(dev)) {
                    delay(5000)
                    continue
                }
            }
            runConnection(usb, dev)
            delay(1000)
        }
    }

    private suspend fun runConnection(usb: UsbAccess, dev: android.hardware.usb.UsbDevice) {
        val name = dev.productName ?: "%04x:%04x".format(dev.vendorId, dev.productId)
        Collector.connection.value = ConnectionUi(Link.CONNECTING, name)
        val port = try {
            usb.openPort(dev, UsbAccess.PROBE_BAUD, release = true)
        } catch (e: Exception) {
            Diag.e("usb", "cannot open port", e)
            null
        }
        if (port == null) {
            Diag.w("usb", "port not opened (${"%04x:%04x".format(dev.vendorId, dev.productId)})")
            Collector.connection.value = ConnectionUi(Link.ERROR, name, error = Texts.cannotOpenPort())
            delay(3000)
            return
        }
        val transport = object : ProbeTransport {
            override fun write(data: ByteArray) {
                port.write(data, 1000)
            }
        }
        val session = ProbeSession(transport, scope, ::onSighting, ledOn = { Collector.probeLedOn.value }, dataFrames = { Collector.captureDataFrames.value })
        Collector.session = session
        val done = CompletableDeferred<Unit>()
        // The USB reader only copies bytes into [inbox]; decoding, identity resolution and the DB
        // queue run on a separate thread. A GC pause or a busy core then delays processing instead of
        // stalling the USB endpoint, which made the probe's writes time out and drop frames.
        val inbox = java.util.concurrent.ArrayBlockingQueue<ByteArray>(INBOX_CHUNKS)
        val io = SerialInputOutputManager(
            port,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    // Full = processing is minutes behind: drop (the decoder resyncs on the next frame).
                    if (!inbox.offer(data)) {
                        Diag.update { it.copy(inboxDrops = it.inboxDrops + 1) }
                        if (Diag.metrics.value.inboxDrops % 100 == 1L) Diag.w("usb", "decoder behind: USB data dropped (${Diag.metrics.value.inboxDrops} chunks)")
                    }
                }
                override fun onRunError(e: Exception) {
                    Diag.update { it.copy(usbErrors = it.usbErrors + 1) }
                    Diag.w("usb", "reader stopped: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
                    done.complete(Unit)
                }
            },
        )
        // Default is one 64-byte USB packet per read call, which caps throughput near what the probe
        // sends in a busy place. One call can return many packets.
        io.readBufferSize = 16 * 1024
        val worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val chunk = inbox.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    runCatching { session.onBytes(chunk) }.onFailure { Diag.e("decode", "frame handling failed", it) }
                }
            } catch (_: InterruptedException) {
            }
        }, "rv-probe-decode")
        worker.start()
        io.start()
        session.start()
        Diag.update { it.copy(connections = it.connections + 1) }
        Diag.i("usb", "connected: $name")
        // The session state changes on every frame; the UI only needs it a few times a second.
        val watcher = scope.launch {
            var phase: dev.retrovision.app.probe.Phase? = null
            var dropped = 0L
            while (isActive) {
                val st = session.state.value
                Collector.connection.value = ConnectionUi(Link.CONNECTED, name, st)
                if (st.phase != phase) {
                    phase = st.phase
                    Diag.i("probe", "phase ${st.phase}" + (st.info?.let { " · fw ${it.firmware} · proto ${it.protocol}" } ?: "") +
                        (if (st.rejectReason.isNotEmpty()) " · ${st.rejectReason}" else ""))
                }
                if (st.probeDropped > dropped + 500) {
                    dropped = st.probeDropped
                    Diag.w("probe", "probe queue dropped ${st.probeDropped} frames · seq gaps ${st.lostFrames} · ${"%.0f".format(st.chipTempC)} °C")
                }
                Diag.update { it.copy(inboxBacklog = inbox.size) }
                delay(400)
            }
        }
        try {
            while (!done.isCompleted && !Collector.usbPaused.get() && usb.isAttached(dev) && scope.isActive) {
                delay(500)
                // Recover a stuck probe without making the user unplug it.
                when (session.tick()) {
                    ProbeSession.Health.DEAD -> { Diag.w("probe", "no data for 45 s: reopening the port"); break }
                    ProbeSession.Health.KICKED -> Diag.w("probe", "link stuck: asked the probe to reboot")
                    else -> Unit
                }
            }
        } finally {
            watcher.cancel()
            session.stop()
            Collector.session = null
            runCatching { io.stop() }
            worker.interrupt()
            runCatching { port.close() }
            Diag.i("usb", "disconnected")
        }
    }

    // ---- database --------------------------------------------------------------

    /** Moves closed 10 s slots from [storeBuckets] to the write queue. */
    private suspend fun storeFlushLoop() {
        while (scope.isActive) {
            delay(2_000)
            for (es in storeBuckets.drainClosed(System.currentTimeMillis())) {
                // Full queue: drop rather than block.
                if (queue.trySend(es.sighting.toRow(es.entityId)).isFailure) {
                    Diag.update { it.copy(queueDrops = it.queueDrops + 1) }
                    if (Diag.metrics.value.queueDrops % 1000 == 1L) Diag.w("db", "write queue full: sightings dropped (${Diag.metrics.value.queueDrops} so far)")
                }
            }
        }
    }

    private suspend fun writerLoop() {
        val dao = app.db.dao()
        while (scope.isActive) {
            val first = queue.receive()
            delay(500) // let a batch build up
            val batch = ArrayList<SightingRow>(256)
            batch += first
            while (batch.size < 2000) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch += next
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { dao.insertSightings(batch) }.onFailure {
                Diag.update { m -> m.copy(writerErrors = m.writerErrors + 1) }
                Diag.e("db", "insert of ${batch.size} rows failed", it)
            }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            Diag.update { it.copy(writerBatches = it.writerBatches + 1, writerLastRows = batch.size, writerLastMs = ms, writerMaxMs = maxOf(it.writerMaxMs, ms)) }
            if (ms > 3_000) Diag.w("db", "slow insert: ${batch.size} rows in $ms ms")
        }
    }

    // ---- analysis --------------------------------------------------------------

    private suspend fun analysisLoop() {
        var lastTrigger = Collector.analyzeNow.value
        var sinceLast = 60_000L
        while (scope.isActive) {
            delay(1000)
            sinceLast += 1000
            val trig = Collector.analyzeNow.value
            if (sinceLast >= 60_000 || trig != lastTrigger) {
                lastTrigger = trig
                sinceLast = 0
                val t0 = android.os.SystemClock.elapsedRealtime()
                val r = runCatching { analyzeOnce() }
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                r.onFailure { e ->
                    Diag.update { it.copy(analysisErrors = it.analysisErrors + 1) }
                    Diag.e("analysis", "run failed after $ms ms · ${Diag.heapLine()}", e)
                }
                Diag.update { it.copy(analysisRuns = it.analysisRuns + 1, analysisLastMs = ms, analysisMaxMs = maxOf(it.analysisMaxMs, ms)) }
                if (ms > 15_000) Diag.w("analysis", "slow run: $ms ms · ${Diag.heapLine()}")
            }
        }
    }

    private suspend fun analyzeOnce() {
        val prefs = app.prefs
        val dao = app.db.dao()
        val now = System.currentTimeMillis()
        val cfg = AnalysisConfig(
            lookbackMs = prefs.lookbackMin * 60_000L,
            alertScore = prefs.alertScore.toDouble(),
            alertMinPlaces = prefs.alertMinPlaces,
            maxFixAccuracyM = prefs.maxFixAccuracyM.toDouble(),
        )
        val from = now - cfg.lookbackMs
        // The window lives in memory. It is filled from the database once (at start, or when the
        // look-back changes); after that only live sightings are added.
        if (warmedLookbackMs != cfg.lookbackMs) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            liveFromMs = now
            liveWindow.clear()
            for (r in dao.sightingsThinned(from, now, WINDOW_BUCKET_MS, MAX_ANALYSIS_ROWS)) {
                liveWindow.add(EntitySighting(r.entityId, r.toSighting()))
            }
            warmedLookbackMs = cfg.lookbackMs
            Diag.i("analysis", "window loaded from storage: ${liveWindow.size} slots in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
        val window: List<EntitySighting> = liveWindow.snapshot(from)
        Collector.analysisLoad.value = AnalysisLoad(
            window.sumOf { maxOf(1, it.sighting.mergedCount).toLong() }, window.size, WINDOW_BUCKET_MS,
            window.size >= MAX_ANALYSIS_ROWS,
        )
        val fixes = dao.fixesSince(from).map { it.toFix() }
        val ignoreIds = dao.ignoresNow().map { it.entityId }.toSet()
        // "False alarm" feedback snoozes that device's alerts for a day.
        val snoozed = dao.falseAlarmsSince(now - 24 * 3600_000L).toSet()
        val conn = checkWifiConnection(now)
        val here = Collector.location.value
        // Connected to one of your own networks = you're at a routine place, even indoors without GPS.
        val familiar = dao.familiarNow().map { it.toModel() } +
            if (conn?.own == true && here != null) listOf(
                dev.retrovision.core.analysis.FamiliarPlace(-1, here.lat, here.lon, 150.0, conn.ssid, dev.retrovision.core.analysis.FamiliarPlace.State.CONFIRMED),
            ) else emptyList()
        val result = Analyzer(cfg).analyze(
            now,
            window,
            fixes,
            IgnoreList(entityIds = ignoreIds, apSsids = prefs.ownSsidSet(), ownFingerprints = prefs.ownFingerprints),
            familiar = familiar,
            residents = dao.residents(BASELINE_MIN_DAYS).toSet(),
        )
        Collector.analysis.value = result
        learnBaseline(result, now)
        learnCompanions(result, now)
        recordFieldTest(result, now)

        // Attack and drone detection need every frame (deauth counts, drone tracks): a short,
        // unthinned window, small by construction.
        val recent = synchronized(recentRaw) { recentRaw.toList() }.filter { now - it.timeMs <= RECENT_RAW_MS }
        val recentWifi = recent.filter { it.radio == dev.retrovision.core.model.Radio.WIFI && now - it.timeMs <= 3 * 60_000L }
        val recentBle = recent.filter { it.radio == dev.retrovision.core.model.Radio.BLE && now - it.timeMs <= 60_000L }
        val threats = dev.retrovision.core.analysis.WifiThreats.detect(recentWifi, prefs.ownSsidSet()) +
            dev.retrovision.core.analysis.WifiThreats.detectBle(recentBle)
        Collector.threats.value = threats

        // Drones heard in the last 5 minutes (Remote ID and drone-radio signatures).
        val drones = dev.retrovision.core.analysis.Drones.summarize(recent, Collector.location.value)
        Collector.drones.value = drones
        if (prefs.alertsEnabled && prefs.droneAlerts && !inQuietHours(now)) {
            for (d in drones) {
                val key = "drone:${d.key}"
                val last = notifiedAt[key]
                if (now - d.lastMs <= 2 * 60_000L && (last == null || now - last > 30 * 60_000L)) {
                    notifiedAt[key] = now
                    notifyDrone(d)
                }
            }
        }
        Collector.associations.value = if (prefs.captureDataFrames) {
            dev.retrovision.core.analysis.AssociatedClients.of(
                window.asSequence().map { it.sighting }.filter { it.radio == dev.retrovision.core.model.Radio.WIFI }.toList(),
            ).take(30)
        } else emptyList()
        if (prefs.alertsEnabled && !inQuietHours(now)) {
            for (th in threats.filter { it.severity >= 0.6 }) {
                val key = "threat:${th.kind}:${th.bssid}:${th.ssid}"
                val last = notifiedAt[key]
                if (last == null || now - last > 10 * 60_000L) {
                    notifiedAt[key] = now
                    notifyThreat(th)
                }
            }
        }

        val atFamiliar = prefs.alertsOnlyAwayFromFamiliar && (conn?.own == true) || prefs.alertsOnlyAwayFromFamiliar && run {
            here != null && dao.familiarNow().map { it.toModel() }.any {
                it.state == dev.retrovision.core.analysis.FamiliarPlace.State.CONFIRMED && it.contains(here.lat, here.lon)
            }
        }
        if (prefs.alertsEnabled && !inQuietHours(now) && !atFamiliar) {
            val cooldownMs = if (prefs.alertOncePerDevice) Long.MAX_VALUE else prefs.alertCooldownMin * 60_000L
            for (a in result.alerts) {
                // Merged entities: snooze, cooldown and last score follow every member id.
                if (a.memberIds.any { it in snoozed }) continue
                val last = a.memberIds.mapNotNull { notifiedAt[it] }.maxOrNull()
                val lastScore = a.memberIds.mapNotNull { notifiedScore[it] }.maxOrNull()
                // A clear escalation (+15 pts) always breaks through the cooldown.
                val escalated = lastScore != null && a.score >= lastScore + 0.15
                val cooldownOk = last == null || now - last >= cooldownMs
                val risesOk = !prefs.alertOnlyIfScoreRises || lastScore == null || a.score + 1e-9 >= lastScore
                if (escalated || (cooldownOk && risesOk)) {
                    for (m in a.memberIds) { notifiedAt[m] = now; notifiedScore[m] = a.score }
                    notifyAlert(a)
                }
            }
        }
        // Retention
        val cutoff = now - prefs.retentionDays * 24L * 3600_000L
        dao.pruneSightings(cutoff)
        // Fixes are kept longer than sightings: learning routine places needs weeks, not days.
        dao.pruneFixes(minOf(cutoff, now - 30L * 24 * 3600_000L))
        dao.pruneCompanions(now - 30L * 24 * 3600_000L)
        dao.pruneFeedback(now - 180L * 24 * 3600_000L)
        if (now - lastLearn > 15 * 60_000L) {
            lastLearn = now
            learnFamiliar(now)
        }
        synchronized(resolver) { resolver.prune(now) }
    }

    private var lastLearn = 0L
    private var warmedLookbackMs = -1L

    /** A device seen only at your routine places gains a "day" once per local day; residents are damped. */
    private suspend fun learnBaseline(result: dev.retrovision.core.analysis.AnalysisResult, now: Long) {
        val dao = app.db.dao()
        val day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
        for (e in result.entities) {
            if (e.placeIds.isEmpty() || e.unfamiliarPlaces > 0) continue // only devices confined to routine places
            val b = dao.baseline(e.entityId)
            if (b == null) {
                dao.putBaseline(dev.retrovision.app.data.BaselineRow(e.entityId, 1, day, now))
            } else if (b.lastDay != day) {
                dao.putBaseline(dev.retrovision.app.data.BaselineRow(e.entityId, (b.days + 1).coerceAtMost(30), day, now))
            }
        }
    }

    /**
     * Devices that travel with you day after day are most likely yours (watch, earbuds, car).
     * After [COMPANION_DAYS] days the app ASKS; it never ignores on its own, and never proposes a
     * tracker tag: a planted tracker also "travels with you every day".
     */
    private suspend fun learnCompanions(result: dev.retrovision.core.analysis.AnalysisResult, now: Long) {
        val dao = app.db.dao()
        val day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
        for (e in result.entities) {
            if (e.kind == dev.retrovision.core.analysis.EntityKind.BLE_TRACKER || e.tracker != null) continue
            if (e.isDrone) continue
            val travelled = e.reasons.any { it is dev.retrovision.core.analysis.Reason.MovedWithYou } && e.placeIds.size >= 2
            if (!travelled) continue
            val c = dao.companion(e.entityId)
            if (c != null && (c.state >= 2 || c.lastDay == day)) continue
            val days = (c?.days ?: 0) + 1
            val state = if (days >= COMPANION_DAYS) 1 else 0
            dao.putCompanion(dev.retrovision.app.data.CompanionRow(e.entityId, days, day, state, Texts.entityLabel(e), now))
        }
    }

    /** Latest Wi-Fi association from the network callback (Android 12+ needs it to see SSID/BSSID). */
    @Volatile private var cbWifi: android.net.wifi.WifiInfo? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerWifiCallback() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : android.net.ConnectivityManager.NetworkCallback(android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) {
                    cbWifi = c.transportInfo as? android.net.wifi.WifiInfo
                }
                override fun onLost(n: android.net.Network) { cbWifi = null }
            }
        } else {
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(n: android.net.Network) { cbWifi = null }
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }.onSuccess { netCallback = cb }
    }

    /**
     * Reads the phone's own Wi-Fi association. For each of your networks, only the very first
     * access point is trusted automatically; any other one alerts until you confirm it in Settings
     * (no automatic trust by vendor: a common router brand would let an impersonator straight in).
     * Joining your network through an unknown access point means an evil twin got YOUR phone.
     */
    @Suppress("DEPRECATION")
    private fun checkWifiConnection(now: Long): dev.retrovision.app.WifiConn? {
        val info = cbWifi ?: runCatching {
            applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
        }.getOrNull() ?: run { Collector.wifiConnection.value = null; return null }
        val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val bssid = info.bssid?.lowercase()?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        if (ssid == null || bssid == null) { Collector.wifiConnection.value = null; return null }
        val own = ssid in prefs.ownSsidSet()
        var trusted = true
        if (own) {
            val known = prefs.trustedAps.filter { it.startsWith("$ssid|") }.map { it.substringAfter('|') }
            trusted = when {
                bssid in known -> true
                known.isEmpty() -> { prefs.trustedAps = prefs.trustedAps + "$ssid|$bssid"; true }
                else -> false
            }
            if (!trusted && prefs.alertsEnabled) {
                val key = "conn:$ssid|$bssid"
                val last = notifiedAt[key]
                if (last == null || now - last > 6 * 3600_000L) {
                    notifiedAt[key] = now
                    notifyUntrustedAp(ssid, bssid)
                }
            }
        }
        return dev.retrovision.app.WifiConn(ssid, bssid, own, trusted).also { Collector.wifiConnection.value = it }
    }

    private fun notifyUntrustedAp(ssid: String, bssid: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = Texts.tr(
            "Your phone joined “$ssid” through an access point it has never used ($bssid). A mesh node or extender of yours also looks like this the first time: if it's yours, confirm it in Settings. If not, someone may be impersonating your network.",
            "Il telefono si è collegato a “$ssid” tramite un access point mai usato ($bssid). Anche un nodo mesh o un ripetitore tuo appare così la prima volta: se è tuo, confermalo in Impostazioni. Se no, qualcuno potrebbe impersonare la tua rete.",
        )
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Texts.tr("Unknown access point for your network", "Access point sconosciuto per la tua rete"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        nm.notify(("ap" + ssid + bssid).hashCode(), n)
    }

    /** Field test: remember when each marked target first crossed the alert threshold. */
    private fun recordFieldTest(result: dev.retrovision.core.analysis.AnalysisResult, now: Long) {
        val targets = prefs.targets
        if (targets.isEmpty()) return
        val done = prefs.testFirstAlerts.map { it.substringBefore('|') }.toSet()
        val fresh = result.alerts.filter { it.entityId in targets && it.entityId !in done }
        if (fresh.isNotEmpty()) prefs.testFirstAlerts = prefs.testFirstAlerts + fresh.map { "${it.entityId}|$now" }
    }

    private suspend fun learnFamiliar(now: Long) {
        val dao = app.db.dao()
        val maxAcc = app.prefs.maxFixAccuracyM.toFloat()
        // Poor fixes (indoors, in a car park) scatter and would invent "places" you never went.
        val fixes = dao.fixesSampled(now - 30L * 24 * 3600_000L).map { it.toFix() }
            .filter { it.accuracyM <= maxAcc }
        val known = dao.familiarNow().map { it.toModel() }
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        for (s in dev.retrovision.core.analysis.FamiliarLearner.suggest(fixes, known, offset)) {
            dao.addFamiliar(
                FamiliarRow(
                    lat = s.lat, lon = s.lon, radiusM = 150.0, label = "", state = 0,
                    kind = s.kind.ordinal, createdMs = now,
                ),
            )
        }
    }

    /** Alerts (optionally) when a streaming probe drops out. Ignores brief re-enumerations. */
    private suspend fun probeWatchLoop() {
        var wasStreaming = false
        var lostSince = 0L
        while (scope.isActive) {
            delay(2000)
            if (Collector.usbPaused.get()) { lostSince = 0; continue }
            val c = Collector.connection.value
            val streaming = c.link == Link.CONNECTED && c.session?.phase == dev.retrovision.app.probe.Phase.STREAMING
            val now = System.currentTimeMillis()
            if (streaming) {
                wasStreaming = true; lostSince = 0
            } else if (wasStreaming) {
                if (lostSince == 0L) {
                    lostSince = now
                } else if (now - lostSince > 15_000L) {
                    if (prefs.probeDisconnectAlert) notifyProbeLost()
                    wasStreaming = false; lostSince = 0
                }
            }
        }
    }

    private fun notifyDrone(d: dev.retrovision.core.analysis.Drones.Drone) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, if (prefs.alertSilent) CH_ALERTS_SILENT else CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Texts.tr("Drone nearby", "Drone nelle vicinanze"))
            .setContentText(Texts.drone(d))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Texts.drone(d)))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(("d" + d.key).hashCode(), n)
    }

    private fun notifyThreat(th: dev.retrovision.core.analysis.WifiThreats.Threat) {
        val nm = getSystemService(NotificationManager::class.java)
        val title = Texts.threatTitle(th.kind)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(Texts.threat(th))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        nm.notify(("t" + th.kind + th.bssid + th.ssid).hashCode(), n)
    }

    private fun notifyProbeLost() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Texts.tr("Probe disconnected", "Sonda scollegata"))
            .setContentText(Texts.tr("The probe stopped streaming. Check the cable or the board.", "La sonda ha smesso di trasmettere. Controlla il cavo o la scheda."))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        nm.notify(NOTIF_PROBE_LOST, n)
    }

    /** True when [now] falls inside the user's quiet hours (local time, may wrap past midnight). */
    private fun inQuietHours(now: Long): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val start = prefs.quietStartHour
        val end = prefs.quietEndHour
        if (start == end) return false
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return if (start < end) h in start until end else h >= start || h < end
    }

    private fun notifyAlert(a: dev.retrovision.core.analysis.EntityReport) {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = if (prefs.alertSilent) CH_ALERTS_SILENT else CH_ALERTS
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Texts.alertTitle(Texts.entityLabel(a)))
            .setContentText(a.reasons.joinToString(" · ") { Texts.reason(it) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.reasons.joinToString("\n") { Texts.reason(it) }))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setSilent(prefs.alertSilent)
            .setPriority(if (prefs.alertSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        nm.notify(a.entityId.hashCode(), n)
    }

    companion object {
        const val ACTION_STOP = "dev.retrovision.app.STOP"
        private const val CH_ONGOING = "ongoing"
        private const val CH_ALERTS = "alerts"
        private const val CH_ALERTS_SILENT = "alerts_silent"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_PROBE_LOST = 2
        private const val BASELINE_MIN_DAYS = 3
        private const val COMPANION_DAYS = 3
        /** Hard ceiling on rows held in memory by one analysis pass (~50-80 MB worst case). */
        private const val MAX_ANALYSIS_ROWS = 120_000
        /** USB chunks buffered between the reader and the decoder (≤16 KiB each). */
        private const val INBOX_CHUNKS = 1024
        private const val STORE_BUCKET_MS = 10_000L
        private const val WINDOW_BUCKET_MS = 60_000L
        private const val RECENT_RAW_MS = 5 * 60_000L
        private const val RECENT_RAW_MAX = 60_000

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CollectorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CollectorService::class.java).setAction(ACTION_STOP))
        }
    }
}
