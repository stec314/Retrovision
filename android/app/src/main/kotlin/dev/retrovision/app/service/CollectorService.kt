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
import dev.retrovision.app.Collector
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
    private val resolver = EntityResolver()
    private val queue = Channel<SightingRow>(capacity = 16_384)
    private var locationManager: LocationManager? = null
    private var lastFixWritten = 0L
    private val notifiedAt = HashMap<String, Long>()

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
        startLocation()
        scope.launch { connectionLoop() }
        scope.launch { writerLoop() }
        scope.launch { analysisLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionRecorder.stop()
        Collector.running.value = false
        Collector.session = null
        Collector.connection.value = ConnectionUi(Link.STOPPED)
        runCatching { locationManager?.removeUpdates(locationListener) }
        scope.cancel()
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
        lastFixWritten = now
        val fix = dev.retrovision.core.model.GeoFix(
            now, loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 0f,
            if (loc.hasSpeed()) loc.speed else null,
        )
        Collector.location.value = fix
        SessionRecorder.write(fix)
        scope.launch {
            app.db.dao().insertFix(FixRow(fix.timeMs, fix.lat, fix.lon, fix.accuracyM, fix.speedMps ?: -1f))
        }
    }

    // ---- probe link ------------------------------------------------------------

    private fun onSighting(s: Sighting) {
        SessionRecorder.write(s)
        val res = synchronized(resolver) { resolver.resolve(s) }
        queue.trySend(s.toRow(res.entityId)) // full queue: drop rather than block the USB thread
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
            usb.openPort(dev)
        } catch (e: Exception) {
            null
        }
        if (port == null) {
            Collector.connection.value = ConnectionUi(Link.ERROR, name, error = Texts.cannotOpenPort())
            delay(3000)
            return
        }
        val transport = object : ProbeTransport {
            override fun write(data: ByteArray) {
                port.write(data, 1000)
            }
        }
        val session = ProbeSession(transport, scope, ::onSighting)
        Collector.session = session
        val done = CompletableDeferred<Unit>()
        val io = SerialInputOutputManager(
            port,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = session.onBytes(data)
                override fun onRunError(e: Exception) {
                    done.complete(Unit)
                }
            },
        )
        io.start()
        session.start()
        val watcher = scope.launch {
            session.state.collect { Collector.connection.value = ConnectionUi(Link.CONNECTED, name, it) }
        }
        try {
            while (!done.isCompleted && !Collector.usbPaused.get() && usb.isAttached(dev) && scope.isActive) {
                delay(500)
            }
        } finally {
            watcher.cancel()
            session.stop()
            Collector.session = null
            runCatching { io.stop() }
            runCatching { port.close() }
        }
    }

    // ---- database --------------------------------------------------------------

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
            runCatching { dao.insertSightings(batch) }
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
                runCatching { analyzeOnce() }
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
        )
        val from = now - cfg.lookbackMs
        val rows = dao.sightingsSince(from)
        val fixes = dao.fixesSince(from).map { it.toFix() }
        val ignoreIds = dao.ignoresNow().map { it.entityId }.toSet()
        val result = Analyzer(cfg).analyze(
            now,
            rows.map { EntitySighting(it.entityId, it.toSighting()) },
            fixes,
            IgnoreList(entityIds = ignoreIds, apSsids = prefs.ownSsidSet()),
            familiar = dao.familiarNow().map { it.toModel() },
        )
        Collector.analysis.value = result

        for (a in result.alerts) {
            val last = notifiedAt[a.entityId]
            if (last == null || now - last > 30 * 60_000L) {
                notifiedAt[a.entityId] = now
                notifyAlert(a)
            }
        }
        // Retention
        val cutoff = now - prefs.retentionDays * 24L * 3600_000L
        dao.pruneSightings(cutoff)
        // Fixes are kept longer than sightings: learning routine places needs weeks, not days.
        dao.pruneFixes(minOf(cutoff, now - 30L * 24 * 3600_000L))
        if (now - lastLearn > 15 * 60_000L) {
            lastLearn = now
            learnFamiliar(now)
        }
        synchronized(resolver) { resolver.prune(now) }
    }

    private var lastLearn = 0L

    private suspend fun learnFamiliar(now: Long) {
        val dao = app.db.dao()
        val fixes = dao.fixesSampled(now - 30L * 24 * 3600_000L).map { it.toFix() }
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

    private fun notifyAlert(a: dev.retrovision.core.analysis.EntityReport) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Texts.alertTitle(Texts.entityLabel(a)))
            .setContentText(a.reasons.joinToString(" · ") { Texts.reason(it) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.reasons.joinToString("\n") { Texts.reason(it) }))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        nm.notify(a.entityId.hashCode(), n)
    }

    companion object {
        const val ACTION_STOP = "dev.retrovision.app.STOP"
        private const val CH_ONGOING = "ongoing"
        private const val CH_ALERTS = "alerts"
        private const val NOTIF_ONGOING = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CollectorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CollectorService::class.java).setAction(ACTION_STOP))
        }
    }
}
