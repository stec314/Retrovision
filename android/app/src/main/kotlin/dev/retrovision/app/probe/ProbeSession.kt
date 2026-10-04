// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.probe

import com.google.protobuf.InvalidProtocolBufferException
import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import dev.retrovision.core.time.ClockSync
import dev.retrovision.core.wire.FrameDecoder
import dev.retrovision.core.wire.Framing
import dev.retrovision.proto.v1.BleAddressType
import dev.retrovision.proto.v1.BleConfig
import dev.retrovision.proto.v1.ChannelDwell
import dev.retrovision.proto.v1.Capability
import dev.retrovision.proto.v1.LinkKind
import dev.retrovision.proto.v1.SetLink
import dev.retrovision.core.wire.LinkAuth
import dev.retrovision.proto.v1.Command
import dev.retrovision.proto.v1.Config
import dev.retrovision.proto.v1.Envelope
import dev.retrovision.proto.v1.Hello
import dev.retrovision.proto.v1.HelloAck
import dev.retrovision.proto.v1.Observation
import dev.retrovision.proto.v1.RadioMode
import dev.retrovision.proto.v1.RadioSchedule
import dev.retrovision.proto.v1.LedMode
import dev.retrovision.proto.v1.Reboot
import dev.retrovision.proto.v1.TimeSyncRequest
import dev.retrovision.proto.v1.WifiConfig
import dev.retrovision.proto.v1.WifiFrameType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Bytes out to the probe. Implemented over USB; fakeable in tests. */
interface ProbeTransport {
    fun write(data: ByteArray)
}

enum class Phase { WAITING_HELLO, SYNCING, STREAMING, REJECTED }

class ProbeInfo(
    val probeType: String,
    val firmware: String,
    val hardwareId: String,
    val bootId: Int,
    val protocol: String,
)

data class SessionState(
    val phase: Phase = Phase.WAITING_HELLO,
    val info: ProbeInfo? = null,
    val clockUncertaintyUs: Long = -1,
    val wifiObs: Long = 0,
    /** Wi-Fi frames heard on 5 GHz channels (dual-band probes only). */
    val wifi5Obs: Long = 0,
    val bleObs: Long = 0,
    val droppedNoClock: Long = 0,
    val lostFrames: Long = 0,
    val badFrames: Long = 0,
    val probeDropped: Long = 0,
    val freeHeap: Int = 0,
    val chipTempC: Float = 0f,
    val channel: Int = 0,
    val lastLog: String = "",
    val rejectReason: String = "",
)

/**
 * Host side of the Retrovision wire protocol v1 (docs/protocol.md): handshake, time sync,
 * observation decoding. The probe is dumb; everything that needs a wall clock happens here.
 *
 * [onBytes] may be called from any thread; timers run on [scope].
 */
class ProbeSession(
    private val transport: ProbeTransport,
    private val scope: CoroutineScope,
    private val onSighting: (Sighting) -> Unit,
    private val nowUs: () -> Long = WallClock::nowUs,
    private val ledOn: () -> Boolean = { true },
    private val dataFrames: () -> Boolean = { false },
    /** Channel plan (see [ChannelPlans]): 0 = the probe's own default. */
    private val channelPlan: () -> Int = { 0 },
    /** Pairing key for a wireless link, or null (USB, or not paired). */
    private val pairingKey: () -> ByteArray? = { null },
) {
    private val lock = Any()
    private val decoder = FrameDecoder()
    private var badProto = 0L
    private val clock = ClockSync()
    private var txSeq = 0
    private var rxSeq = 0L
    private var bootId: Int? = null
    private var probeId = ""
    private var syncJob: Job? = null
    private var maintainJob: Job? = null

    // ---- link health (see [tick]) ----
    private var startedMs = 0L
    private var lastRxMs = 0L
    /** Frames received while we still wait for a Hello: the probe is mid-session from a previous host. */
    private var orphanFrames = 0L
    private var lastKickMs = 0L
    var recoveries = 0L
        private set

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    /** Flush anything the probe has half-sent, as the protocol recommends after opening the port. */
    fun start() {
        synchronized(lock) {
            startedMs = System.currentTimeMillis()
            runCatching { transport.write(byteArrayOf(0)) }
        }
    }

    enum class Health { OK, KICKED, DEAD }

    /**
     * Link watchdog, called periodically by the connection loop. Recovers the two ways a probe
     * can get stuck without the cable ever leaving:
     *
     *  1. Orphaned session: the app reopened the port (service restart, read error) but the probe
     *     still believes its old session is up, so it streams status/observations and never says
     *     Hello again. We would wait forever ("waiting for the probe to introduce itself", 0 obs).
     *     -> ask it to reboot; it comes back with a fresh Hello.
     *  2. Silence: nothing at all from the probe (stalled USB TX, wedged firmware).
     *     -> reboot it; if it still says nothing, report DEAD so the caller reopens the port.
     *
     * Reboot is honoured by the firmware in every state, including before the handshake.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): Health = synchronized(lock) { tickLocked(nowMs) }

    private fun tickLocked(nowMs: Long): Health {
        val phase = _state.value.phase
        if (phase == Phase.REJECTED) return Health.OK
        val quietFor = nowMs - maxOf(lastRxMs, startedMs)
        val canKick = nowMs - lastKickMs > KICK_EVERY_MS
        val stuck = when (phase) {
            // Hello comes every 2 s: frames without one = orphan; nothing for 8 s = silent.
            Phase.WAITING_HELLO -> (orphanFrames > 0 && nowMs - startedMs > 3_000) || quietFor > 8_000
            // Status comes every 10 s.
            else -> quietFor > 30_000
        }
        if (!stuck) return Health.OK
        if (quietFor > DEAD_AFTER_MS) return Health.DEAD
        if (!canKick) return Health.OK
        lastKickMs = nowMs
        recoveries++
        orphanFrames = 0
        update { it.copy(lastLog = "app: probe link stuck, restarting the probe (#$recoveries)") }
        send(
            Envelope.newBuilder().setSeq(nextSeq()).setCommand(
                Command.newBuilder().setReboot(Reboot.newBuilder().setIntoBootloader(false)),
            ).build(),
        )
        return Health.KICKED
    }

    fun stop() {
        syncJob?.cancel()
        maintainJob?.cancel()
    }

    fun onBytes(chunk: ByteArray) {
        synchronized(lock) {
            decoder.feed(chunk) { env ->
                try {
                    handle(Envelope.parseFrom(env))
                } catch (_: InvalidProtocolBufferException) {
                    badProto++
                }
            }
            update { it.copy(badFrames = decoder.badFrames + badProto) }
        }
    }

    /** Ask the probe to reboot, optionally into the ROM download mode used for flashing. */
    fun rebootProbe(intoBootloader: Boolean) {
        synchronized(lock) {
            send(Envelope.newBuilder().setSeq(nextSeq()).setCommand(
                Command.newBuilder().setReboot(Reboot.newBuilder().setIntoBootloader(intoBootloader)),
            ).build())
        }
    }

    // ---- handlers (lock held) --------------------------------------------------

    private fun handle(env: Envelope) {
        lastRxMs = System.currentTimeMillis()
        if (_state.value.phase == Phase.WAITING_HELLO && env.payloadCase != Envelope.PayloadCase.HELLO) orphanFrames++
        trackSeq(env.seq)
        when (env.payloadCase) {
            Envelope.PayloadCase.HELLO -> onHello(env.hello)
            Envelope.PayloadCase.OBSERVATION -> onObservation(env.observation)
            Envelope.PayloadCase.STATUS -> env.status.let { s ->
                update {
                    it.copy(
                        probeDropped = s.obsDropped, freeHeap = s.freeHeapBytes,
                        chipTempC = s.chipTempC, channel = s.currentWifiChannel,
                    )
                }
            }
            Envelope.PayloadCase.LOG -> update { it.copy(lastLog = "${env.log.tag}: ${env.log.text}") }
            Envelope.PayloadCase.TIME_SYNC_RESPONSE -> env.timeSyncResponse.let {
                val t3 = nowUs()
                // Ignore absurd round trips (stale echo after a reconnect).
                if (t3 - it.hostT1Us in 0..5_000_000L) clock.addSample(it.hostT1Us, it.probeT2Us, t3)
            }
            else -> Unit // CommandAck: nothing to do in v1
        }
    }

    private fun trackSeq(seq: Int) {
        val s = seq.toLong() and 0xFFFFFFFFL
        if (rxSeq != 0L && s != 0L) {
            val expected = if (rxSeq == 0xFFFFFFFFL) 1L else rxSeq + 1
            if (s != expected) {
                val gap = (s - expected) and 0xFFFFFFFFL
                if (gap < 1_000_000L) update { it.copy(lostFrames = it.lostFrames + gap) }
            }
        }
        rxSeq = s
    }

    private fun onHello(h: Hello) {
        dualBand = h.capabilitiesList.contains(Capability.CAPABILITY_WIFI_5GHZ)
        if (h.protocolMajor != PROTOCOL_MAJOR) {
            send(
                Envelope.newBuilder().setSeq(nextSeq()).setHelloAck(
                    HelloAck.newBuilder().setProtocolMajor(PROTOCOL_MAJOR).setProtocolMinor(PROTOCOL_MINOR)
                        .setBootId(h.bootId).setAccepted(false)
                        .setRejectReason("protocol major ${h.protocolMajor} not supported (host speaks $PROTOCOL_MAJOR)"),
                ).build(),
            )
            update {
                it.copy(
                    phase = Phase.REJECTED,
                    rejectReason = "Firmware protocol v${h.protocolMajor} ≠ app v$PROTOCOL_MAJOR: update the firmware",
                )
            }
            return
        }
        if (bootId != h.bootId) {
            clock.reset()
            rxSeq = 0
        }
        bootId = h.bootId
        probeId = h.hardwareId.toByteArray().joinToString("") { "%02x".format(it) }
        // A wireless probe challenges us: prove we hold the pairing key, or it streams nothing.
        if (h.link == LinkKind.LINK_KIND_BLE) {
            val key = pairingKey()
            if (key == null || h.authNonce.size() != 16) {
                update { it.copy(phase = Phase.REJECTED, rejectReason = "Not paired with this probe") }
                return
            }
            val mac = LinkAuth.authMac(key, h.authNonce.toByteArray(), h.bootId)
            send(
                Envelope.newBuilder().setSeq(nextSeq()).setHelloAck(
                    HelloAck.newBuilder().setProtocolMajor(PROTOCOL_MAJOR).setProtocolMinor(PROTOCOL_MINOR)
                        .setBootId(h.bootId).setAccepted(true).setConfig(defaultConfig())
                        .setAuthMac(com.google.protobuf.ByteString.copyFrom(mac)),
                ).build(),
            )
        } else {
            send(
                Envelope.newBuilder().setSeq(nextSeq()).setHelloAck(
                    HelloAck.newBuilder().setProtocolMajor(PROTOCOL_MAJOR).setProtocolMinor(PROTOCOL_MINOR)
                        .setBootId(h.bootId).setAccepted(true).setConfig(defaultConfig()),
                ).build(),
            )
        }
        update {
            it.copy(
                phase = Phase.SYNCING,
                rejectReason = "",
                info = ProbeInfo(
                    h.probeType, h.firmwareVersion, probeId, h.bootId,
                    "${h.protocolMajor}.${h.protocolMinor}",
                ),
            )
        }
        startSync()
    }

    private fun onObservation(o: Observation) {
        if (!clock.isSynced) {
            update { it.copy(droppedNoClock = it.droppedNoClock + 1) }
            return
        }
        val timeMs = clock.toWallMs(o.probeTsUs)
        when (o.detailCase) {
            Observation.DetailCase.WIFI -> {
                val w = o.wifi
                val addr = mac(w.addr2.toByteArray()) ?: return
                if (addr.bits == 0L || addr.isMulticast) return
                val kind = when (w.frameType) {
                    WifiFrameType.WIFI_FRAME_TYPE_PROBE_REQ -> WifiKind.PROBE_REQ
                    WifiFrameType.WIFI_FRAME_TYPE_PROBE_RESP -> WifiKind.PROBE_RESP
                    WifiFrameType.WIFI_FRAME_TYPE_BEACON -> WifiKind.BEACON
                    WifiFrameType.WIFI_FRAME_TYPE_ASSOC_REQ -> WifiKind.ASSOC_REQ
                    WifiFrameType.WIFI_FRAME_TYPE_REASSOC_REQ -> WifiKind.REASSOC_REQ
                    WifiFrameType.WIFI_FRAME_TYPE_AUTH -> WifiKind.AUTH
                    WifiFrameType.WIFI_FRAME_TYPE_DEAUTH -> WifiKind.DEAUTH
                    WifiFrameType.WIFI_FRAME_TYPE_DISASSOC -> WifiKind.DISASSOC
                    WifiFrameType.WIFI_FRAME_TYPE_DATA -> WifiKind.DATA
                    WifiFrameType.WIFI_FRAME_TYPE_ACTION -> WifiKind.ACTION
                    else -> WifiKind.OTHER
                }
                val detail = WifiDetail(
                    kind = kind,
                    channel = w.channel,
                    ssid = w.ssid.toByteArray(),
                    bssid = mac(w.addr3.toByteArray()),
                    seq = w.seqCtrl ushr 4,
                    ies = w.rawIes.toByteArray(),
                    iesTruncated = w.rawIesTruncated,
                    tsfUs = if (w.tsfUs != 0L) w.tsfUs else -1,
                )
                val five = w.channel > 14
                update { it.copy(wifiObs = it.wifiObs + 1, wifi5Obs = it.wifi5Obs + if (five) 1 else 0) }
                onSighting(Sighting(timeMs, Radio.WIFI, addr, o.rssiDbm, maxOf(1, o.mergedCount), wifi = detail, probeId = probeId))
            }
            Observation.DetailCase.BLE -> {
                val b = o.ble
                val addr = mac(b.address.toByteArray()) ?: return
                val kind = when (b.addressType) {
                    BleAddressType.BLE_ADDRESS_TYPE_PUBLIC -> BleAddressKind.PUBLIC
                    BleAddressType.BLE_ADDRESS_TYPE_RANDOM_STATIC -> BleAddressKind.RANDOM_STATIC
                    BleAddressType.BLE_ADDRESS_TYPE_RANDOM_RESOLVABLE -> BleAddressKind.RANDOM_RESOLVABLE
                    BleAddressType.BLE_ADDRESS_TYPE_RANDOM_NON_RESOLVABLE -> BleAddressKind.RANDOM_NON_RESOLVABLE
                    else -> BleAddressKind.UNKNOWN
                }
                val detail = BleDetail(kind, b.advType.number, b.advData.toByteArray(), b.txPowerDbm)
                update { it.copy(bleObs = it.bleObs + 1) }
                onSighting(Sighting(timeMs, Radio.BLE, addr, o.rssiDbm, maxOf(1, o.mergedCount), ble = detail, probeId = probeId))
            }
            else -> Unit // GNSS / custom: no consumer in v1 of the app
        }
    }

    private fun mac(b: ByteArray): MacAddress? = if (b.size == 6) MacAddress.of(b) else null

    // ---- time sync -------------------------------------------------------------

    private fun startSync() {
        syncJob?.cancel()
        maintainJob?.cancel()
        syncJob = scope.launch {
            burst()
            synchronized(lock) {
                if (clock.isSynced) update { it.copy(phase = Phase.STREAMING) }
            }
            maintainJob = launch {
                while (isActive) {
                    delay(30_000)
                    burst()
                }
            }
        }
    }

    private suspend fun burst() {
        repeat(8) {
            synchronized(lock) {
                send(
                    Envelope.newBuilder().setSeq(nextSeq()).setCommand(
                        Command.newBuilder().setTimeSync(TimeSyncRequest.newBuilder().setHostT1Us(nowUs())),
                    ).build(),
                )
            }
            delay(60)
        }
        delay(400)
        synchronized(lock) {
            if (clock.endBurst()) update { it.copy(clockUncertaintyUs = clock.uncertaintyUs) }
        }
    }

    // ---- plumbing --------------------------------------------------------------

    private fun nextSeq(): Int {
        txSeq = if (txSeq == -1) 1 else txSeq + 1 // wraps 2^32-1 -> 1 (as unsigned)
        if (txSeq == 0) txSeq = 1
        return txSeq
    }

    private fun send(env: Envelope) {
        runCatching { transport.write(Framing.encode(env.toByteArray())) }
    }

    private inline fun update(f: (SessionState) -> SessionState) {
        _state.value = f(_state.value)
    }

    /** Resend the running config (LED, data-frame capture) to the probe. Takes effect immediately. */
    fun resendConfig() {
        synchronized(lock) {
            send(
                Envelope.newBuilder().setSeq(nextSeq()).setCommand(
                    Command.newBuilder().setSetConfig(defaultConfig()),
                ).build(),
            )
        }
    }

    fun setLedEnabled(on: Boolean) = resendConfig()

    /**
     * Pairing, sent over USB only (the firmware refuses it on a wireless link): store the mode,
     * a name and the pairing key on the probe, which then reboots into that mode.
     * [key] is 16..32 random bytes. [mode] USB turns wireless off.
     */
    fun setLink(mode: LinkKind, name: String, key: ByteArray) {
        synchronized(lock) {
            send(
                Envelope.newBuilder().setSeq(nextSeq()).setCommand(
                    Command.newBuilder().setSetLink(
                        SetLink.newBuilder().setMode(mode).setName(name)
                            .setKey(com.google.protobuf.ByteString.copyFrom(key)),
                    ),
                ).build(),
            )
        }
    }

    /** Set from the probe's Hello: it can tune 5 GHz (ESP32-C5). */
    @Volatile private var dualBand = false

    private fun defaultConfig(): Config = configWith(ledOn(), dataFrames())

    private fun configWith(led: Boolean, data: Boolean, hops: List<Pair<Int, Int>> = ChannelPlans.hops(channelPlan(), dualBand)): Config = Config.newBuilder()
        .setWifi(
            WifiConfig.newBuilder().setEnabled(true)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_PROBE_REQ)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_BEACON)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_PROBE_RESP)
                // Clients joining a network: shows who is actually connecting, not only searching.
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_AUTH)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_ASSOC_REQ)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_REASSOC_REQ)
                // Attack detection: deauth/disassoc floods.
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_DEAUTH)
                .addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_DISASSOC)
                .apply { if (data) addFrameTypes(WifiFrameType.WIFI_FRAME_TYPE_DATA) }
                .apply { hops.forEach { (ch, ms) -> addHop(ChannelDwell.newBuilder().setChannel(ch).setDwellMs(ms)) } }
                .setForwardRawIes(true)
                .setProbeReqDedupMs(0)
                .setBeaconDedupMs(30_000),
        )
        .setBle(BleConfig.newBuilder().setEnabled(true).setExtended(true).setDedupMs(1_000))
        .setSchedule(RadioSchedule.newBuilder().setMode(RadioMode.RADIO_MODE_COEX))
        .setStatusIntervalS(10)
        .setLed(if (led) LedMode.LED_MODE_ON else LedMode.LED_MODE_OFF)
        .build()

    companion object {
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 2
        private const val KICK_EVERY_MS = 12_000L
        private const val DEAD_AFTER_MS = 45_000L
    }
}

/** Microsecond wall clock with sub-millisecond resolution, monotonic between NTP steps. */
object WallClock {
    private val baseUs: Long = System.currentTimeMillis() * 1000 - System.nanoTime() / 1000
    fun nowUs(): Long = baseUs + System.nanoTime() / 1000
}
