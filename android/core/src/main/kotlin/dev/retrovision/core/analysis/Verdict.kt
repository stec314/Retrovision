// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.analysis

/**
 * How strongly to word a finding for someone who is not an expert. The score is a sum of
 * heuristics, not a probability, so the UI never shows it as "100 %" outside the evidence.
 *
 * [STRONG] needs an alert AND behaviour that only something moving with you produces (steady
 * signal over a long move, arrived and left with you, stayed through your turns, a tracker away
 * from its owner). Presence alone — many places, long time — is [WORTH_A_LOOK]: shared routes,
 * public transport and neighbourhoods produce it too.
 */
enum class Level { LOW, SOME, WORTH_A_LOOK, STRONG }

object Levels {
    fun of(r: EntityReport): Level = when {
        r.alert && hasBehaviour(r) -> Level.STRONG
        r.alert -> Level.WORTH_A_LOOK
        r.score >= 0.5 -> Level.SOME
        else -> Level.LOW
    }

    fun of(t: WifiThreats.Threat): Level = if (t.severity >= 0.6) Level.STRONG else Level.WORTH_A_LOOK

    /** Evidence of moving with you, as opposed to being around a lot. */
    fun hasBehaviour(r: EntityReport): Boolean = r.reasons.any {
        when (it) {
            is Reason.MovedWithYou -> true
            is Reason.JoinedAfterYou -> it.stops >= 2
            is Reason.StayedThroughTurns -> it.turns >= 3
            is Reason.Tracker -> it.separatedFromOwner == true
            else -> false
        }
    }
}

/**
 * The one-line answer on the Status screen, and what it is based on. The verdict never says
 * "nothing found" when the app could not have seen it: no receiver, no GPS, too little time.
 */
object Verdict {
    enum class State {
        /** Collection is off. */
        STOPPED,
        /** Not enough coverage to say anything. */
        CANT_TELL,
        /** Nothing found, with full coverage. */
        CLEAR,
        /** Nothing found, but part of the picture is missing (see [Result.gaps]). */
        CLEAR_PARTIAL,
        WORTH_A_LOOK,
        STRONG,
    }

    enum class Gap {
        /** No probe streaming: Wi-Fi, Wi-Fi attacks and most of BLE are not heard. */
        NO_PROBE,
        /** Nothing is listening at all. */
        NO_RECEIVER,
        /** No usable GPS: places, travel and routes are paused. */
        NO_GPS,
        /** GPS present but worse than the accuracy limit. */
        POOR_GPS,
        /** Collected for less than [MIN_COVERED_MS]: following needs time to show. */
        TOO_SHORT,
        /** No analysis has run yet, or the last one is stale. */
        NO_ANALYSIS,
        /** The analysis window was cut to its most recent part. */
        TRUNCATED,
    }

    class Inputs(
        val running: Boolean,
        val probeStreaming: Boolean,
        val phoneBleListening: Boolean,
        /** Age of the last GPS fix (null = never), and its accuracy. */
        val gpsAgeMs: Long?,
        val gpsAccuracyM: Float?,
        val maxGpsAccuracyM: Float,
        /** How much of the analysis window is covered by this collection run. */
        val coveredMs: Long,
        /** Age of the last analysis (null = none yet). */
        val analysisAgeMs: Long?,
        val truncated: Boolean,
        val entityLevels: List<Level>,
        val threatLevels: List<Level>,
    )

    class Result(val state: State, val gaps: List<Gap>, val strong: Int, val worth: Int)

    const val MIN_COVERED_MS = 10 * 60_000L
    const val FULL_COVERED_MS = 20 * 60_000L
    const val GPS_STALE_MS = 60_000L
    const val ANALYSIS_STALE_MS = 3 * 60_000L

    fun of(i: Inputs): Result {
        val levels = i.entityLevels + i.threatLevels
        val strong = levels.count { it == Level.STRONG }
        val worth = levels.count { it == Level.WORTH_A_LOOK }
        if (!i.running) return Result(State.STOPPED, emptyList(), strong, worth)

        val gaps = ArrayList<Gap>()
        if (!i.probeStreaming && !i.phoneBleListening) gaps += Gap.NO_RECEIVER
        else if (!i.probeStreaming) gaps += Gap.NO_PROBE
        when {
            i.gpsAgeMs == null || i.gpsAgeMs > GPS_STALE_MS -> gaps += Gap.NO_GPS
            i.gpsAccuracyM != null && i.gpsAccuracyM > i.maxGpsAccuracyM -> gaps += Gap.POOR_GPS
        }
        if (i.coveredMs < FULL_COVERED_MS) gaps += Gap.TOO_SHORT
        if (i.analysisAgeMs == null || i.analysisAgeMs > ANALYSIS_STALE_MS) gaps += Gap.NO_ANALYSIS
        if (i.truncated) gaps += Gap.TRUNCATED

        // Findings are shown whatever the coverage: something seen is still worth reporting.
        if (strong > 0) return Result(State.STRONG, gaps, strong, worth)
        if (worth > 0) return Result(State.WORTH_A_LOOK, gaps, strong, worth)
        val blind = Gap.NO_RECEIVER in gaps || Gap.NO_ANALYSIS in gaps || i.coveredMs < MIN_COVERED_MS
        val state = when {
            blind -> State.CANT_TELL
            gaps.isEmpty() -> State.CLEAR
            else -> State.CLEAR_PARTIAL
        }
        return Result(state, gaps, strong, worth)
    }
}
