// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app

import dev.retrovision.app.probe.ProbeSession
import dev.retrovision.app.probe.SessionState
import dev.retrovision.core.analysis.AnalysisResult
import dev.retrovision.core.analysis.AssociatedClients
import dev.retrovision.core.analysis.WifiThreats
import dev.retrovision.core.identity.DeviceCategory
import dev.retrovision.core.model.GeoFix
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class Link { STOPPED, NO_DEVICE, NEED_PERMISSION, CONNECTING, CONNECTED, ERROR }

data class ConnectionUi(
    val link: Link = Link.STOPPED,
    val device: String = "",
    val session: SessionState? = null,
    val error: String = "",
)

/** Phone's current Wi-Fi association. [own] = one of your networks; [trusted] = known access point for it. */
/** How much of the analysis window was loaded: raw rows in the window, rows analysed, thinning bucket. */
data class AnalysisLoad(
    val rawRows: Long = 0,
    val analysedRows: Int = 0,
    val bucketMs: Long = 0,
    val truncated: Boolean = false,
    /** Oldest sighting in the analysed window (0 = none): how far back the analysis can see. */
    val oldestMs: Long = 0,
)

data class WifiConn(val ssid: String, val bssid: String, val own: Boolean, val trusted: Boolean)

data class FlashUi(
    val running: Boolean = false,
    val stage: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val log: List<String> = emptyList(),
    val error: String = "",
    val success: Boolean = false,
)

/** One device on the live radar. [rssi] is smoothed; [bearingDeg] is set only when movement gives a usable estimate. */
data class RadarBlip(
    val entityId: String,
    val label: String,
    val rssi: Double,
    val category: DeviceCategory,
    val alert: Boolean,
    val bearingDeg: Double?,
    val bearingConf: Double,
)

data class RadarFrame(val blips: List<RadarBlip> = emptyList(), val movedM: Double = 0.0, val ts: Long = 0L)

/** Process-wide state shared between the service and the UI. */
object Collector {
    val connection = MutableStateFlow(ConnectionUi())
    val analysis = MutableStateFlow<AnalysisResult?>(null)
    val analysisLoad = MutableStateFlow(AnalysisLoad())
    val location = MutableStateFlow<GeoFix?>(null)
    val flash = MutableStateFlow(FlashUi())
    val running = MutableStateFlow(false)

    /** The live probe session, if any (used to reboot the probe into its bootloader). */
    @Volatile var session: ProbeSession? = null

    /** Set while the flasher owns the USB port. */
    val usbPaused = AtomicBoolean(false)

    /** Set by the UI to request an immediate analysis run. */
    val analyzeNow = MutableStateFlow(0L)

    /** Mirrors Prefs.probeLedOn; ProbeSession reads it when building the probe config. */
    val probeLedOn = MutableStateFlow(true)

    /** Live radar frame (proximity + indicative bearing), refreshed a few times a second. */
    val liveRadar = MutableStateFlow(RadarFrame())

    /** Active Wi-Fi attacks detected in the recent window. */
    val threats = MutableStateFlow<List<WifiThreats.Threat>>(emptyList())

    /** Mirrors Prefs.captureDataFrames; ProbeSession reads it when building the probe config. */
    val captureDataFrames = MutableStateFlow(false)

    /** Phone-side receivers. */
    val phoneBleActive = MutableStateFlow(false)
    val phoneBleHeard = MutableStateFlow(0L)
    val phoneCodedPhy = MutableStateFlow(false)
    val phoneStill = MutableStateFlow(false)
    val phoneMotion = MutableStateFlow(dev.retrovision.app.phone.MotionState.UNKNOWN)
    val driftRejected = MutableStateFlow(0L)

    /** "Identify my phone": until this time, strong probe requests are taken as this phone's own. */
    @Volatile var calibrateUntilMs = 0L
    val calibrated = MutableStateFlow<Set<String>>(emptySet())

    /** The Wi-Fi network the phone is connected to (null when none / unknown). */
    val wifiConnection = MutableStateFlow<WifiConn?>(null)

    /** Drones heard recently (Remote ID or drone-radio signatures). */
    val drones = MutableStateFlow<List<dev.retrovision.core.analysis.Drones.Drone>>(emptyList())

    /** APs and the clients talking to them (only when data-frame capture is on). */
    val associations = MutableStateFlow<List<AssociatedClients.Ap>>(emptyList())
}
