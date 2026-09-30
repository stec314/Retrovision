package dev.retrovision.core.time

import kotlin.math.abs

/**
 * Maps a probe's monotonic clock (µs since boot) to host wall-clock time
 * (Unix µs). NTP-style, see docs/protocol.md §6:
 *
 *  - every request/response pair gives offset ≈ (t1 + t3)/2 − t2 with error ≤ rtt/2;
 *  - within a burst only the minimum-RTT sample is kept (removes USB/scheduler jitter);
 *  - across bursts `wall = a·probe + b` is fitted by least squares over the last
 *    [windowUs], which absorbs crystal drift (±20 ppm ≈ 72 ms/hour).
 *
 * Not thread-safe; owned by one probe session.
 */
class ClockSync(
    private val windowUs: Long = 10 * 60 * 1_000_000L,
    private val maxDriftPpm: Double = 200.0,
) {
    private data class Point(val probeUs: Long, val offsetUs: Double, val rttUs: Long)

    private var burstBest: Point? = null
    private val points = ArrayDeque<Point>()

    // Model: wall = probe + offset(probe), offset(probe) = slope·(probe − pivot) + intercept.
    private var pivot = 0L
    private var slope = 0.0
    private var intercept = 0.0

    var isSynced: Boolean = false
        private set

    /** Half the RTT of the best sample of the last burst, µs. */
    var uncertaintyUs: Long = Long.MAX_VALUE
        private set

    /** One request/response pair. Times: t1/t3 host wall µs, t2 probe monotonic µs. */
    fun addSample(t1HostUs: Long, t2ProbeUs: Long, t3HostUs: Long) {
        val rtt = t3HostUs - t1HostUs
        if (rtt < 0) return // host clock jumped; discard
        val p = Point(t2ProbeUs, (t1HostUs + t3HostUs) / 2.0 - t2ProbeUs, rtt)
        val best = burstBest
        if (best == null || p.rttUs < best.rttUs) burstBest = p
    }

    /** Close the current burst and refit. Returns false if the burst had no samples. */
    fun endBurst(): Boolean {
        val best = burstBest ?: return false
        burstBest = null
        points.addLast(best)
        while (points.size > 2 && best.probeUs - points.first().probeUs > windowUs) points.removeFirst()
        fit()
        uncertaintyUs = best.rttUs / 2
        isSynced = true
        return true
    }

    /** Probe reboot (new boot_id): all state is invalid. */
    fun reset() {
        burstBest = null
        points.clear()
        isSynced = false
        uncertaintyUs = Long.MAX_VALUE
        slope = 0.0
        intercept = 0.0
    }

    /** Probe monotonic µs -> host wall-clock µs. Requires [isSynced]. */
    fun toWallUs(probeUs: Long): Long {
        check(isSynced) { "clock not synced" }
        return probeUs + (slope * (probeUs - pivot) + intercept).toLong()
    }

    fun toWallMs(probeUs: Long): Long = toWallUs(probeUs) / 1000

    private fun fit() {
        val n = points.size
        pivot = points.last().probeUs
        if (n < 2) {
            slope = 0.0
            intercept = points.last().offsetUs
            return
        }
        var sx = 0.0
        var sy = 0.0
        for (p in points) {
            sx += (p.probeUs - pivot).toDouble()
            sy += p.offsetUs
        }
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = (p.probeUs - pivot) - mx
            sxx += dx * dx
            sxy += dx * (p.offsetUs - my)
        }
        // Too little time span, or an implausible drift: fall back to a pure offset.
        val s = if (sxx > 0) sxy / sxx else 0.0
        if (sxx <= 0 || abs(s) * 1e6 > maxDriftPpm) {
            slope = 0.0
            intercept = points.last().offsetUs
        } else {
            slope = s
            intercept = my - s * mx
        }
    }
}
