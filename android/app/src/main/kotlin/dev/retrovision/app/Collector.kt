package dev.retrovision.app

import dev.retrovision.app.probe.ProbeSession
import dev.retrovision.app.probe.SessionState
import dev.retrovision.core.analysis.AnalysisResult
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

data class FlashUi(
    val running: Boolean = false,
    val stage: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val log: List<String> = emptyList(),
    val error: String = "",
    val success: Boolean = false,
)

/** Process-wide state shared between the service and the UI. */
object Collector {
    val connection = MutableStateFlow(ConnectionUi())
    val analysis = MutableStateFlow<AnalysisResult?>(null)
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
}
