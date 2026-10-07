// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import dev.retrovision.app.RetrovisionApp
import dev.retrovision.core.analysis.AnalysisResult
import dev.retrovision.core.analysis.Analyzer
import dev.retrovision.core.analysis.EntitySighting
import dev.retrovision.core.analysis.IgnoreList
import dev.retrovision.core.analysis.retrospectiveConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-demand review of all saved data over a span, tuned to reward recurring, travelling presence. */
object Retrospective {
    /** Keep the working set bounded so a huge history cannot exhaust memory. */
    private const val MAX_ROWS = 60_000

    data class Result(
        val analysis: AnalysisResult?,
        val spanMs: Long,
        val sightings: Int,
        val sampledFrom: Long = 0,
        val error: String? = null,
    )

    suspend fun run(spanMs: Long): Result = run(System.currentTimeMillis() - spanMs, System.currentTimeMillis())

    /** Reviews an arbitrary period [from, to): any past day or week, not only "the last N hours". */
    suspend fun run(from: Long, to: Long): Result = withContext(Dispatchers.IO) {
        val app = RetrovisionApp.instance
        val dao = app.db.dao()
        val now = to
        val spanMs = (to - from).coerceAtLeast(60_000L)
        try {
            val total = dao.sightingCountBetween(from, to)
            val stride = if (total > MAX_ROWS) ((total + MAX_ROWS - 1) / MAX_ROWS).toInt() else 1
            val rows = if (stride <= 1) dao.sightingsBetween(from, to) else dao.sightingsBetweenSampled(from, to, stride)
            val fixes = dao.fixesBetween(from, to).map { it.toFix() }
            val ignoreIds = dao.ignoresNow().map { it.entityId }.toSet()
            val cfg = retrospectiveConfig(
                spanMs = spanMs,
                alertScore = app.prefs.alertScore.toDouble(),
                alertMinPlaces = app.prefs.alertMinPlaces,
                familiarWeight = 0.3,
                maxFixAccuracyM = app.prefs.maxFixAccuracyM.toDouble(),
            )
            val result = Analyzer(cfg).analyze(
                now,
                rows.map { EntitySighting(it.entityId, it.toSightingLight()) },
                fixes,
                IgnoreList.fromRows(ignoreIds, app.prefs.ownSsidSet(), app.prefs.ownFingerprints),
                familiar = dao.familiarNow().map { it.toModel() },
            )
            Result(result, spanMs, rows.size, sampledFrom = if (stride > 1) total else 0)
        } catch (e: OutOfMemoryError) {
            Result(null, spanMs, 0, error = "out_of_memory")
        } catch (e: Throwable) {
            Result(null, spanMs, 0, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
