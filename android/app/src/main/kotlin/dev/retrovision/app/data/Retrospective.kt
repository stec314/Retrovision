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
    data class Result(val analysis: AnalysisResult, val spanMs: Long, val sightings: Int)

    suspend fun run(spanMs: Long): Result = withContext(Dispatchers.IO) {
        val app = RetrovisionApp.instance
        val dao = app.db.dao()
        val now = System.currentTimeMillis()
        val from = now - spanMs
        val rows = dao.sightingsSince(from)
        val fixes = dao.fixesSince(from).map { it.toFix() }
        val ignoreIds = dao.ignoresNow().map { it.entityId }.toSet()
        val cfg = retrospectiveConfig(
            spanMs = spanMs,
            alertScore = app.prefs.alertScore.toDouble(),
            alertMinPlaces = app.prefs.alertMinPlaces,
            familiarWeight = 0.3,
        )
        val result = Analyzer(cfg).analyze(
            now,
            rows.map { EntitySighting(it.entityId, it.toSighting()) },
            fixes,
            IgnoreList(entityIds = ignoreIds, apSsids = app.prefs.ownSsidSet()),
            familiar = dao.familiarNow().map { it.toModel() },
        )
        Result(result, spanMs, rows.size)
    }
}
