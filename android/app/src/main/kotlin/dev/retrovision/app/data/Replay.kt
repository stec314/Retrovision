package dev.retrovision.app.data

import dev.retrovision.core.analysis.AnalysisConfig
import dev.retrovision.core.analysis.AnalysisResult
import dev.retrovision.core.analysis.Analyzer
import dev.retrovision.core.analysis.EntitySighting
import dev.retrovision.core.analysis.FamiliarPlace
import dev.retrovision.core.analysis.IgnoreList
import dev.retrovision.core.identity.EntityResolver
import dev.retrovision.core.session.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Re-runs identity resolution and the analysis on a recorded session with the current settings. */
object Replay {
    class Outcome(val result: AnalysisResult, val sightings: Int, val fixes: Int, val durationMs: Long)

    suspend fun run(input: InputStream, prefs: Prefs, familiar: List<FamiliarPlace>): Outcome =
        withContext(Dispatchers.Default) {
            val rec = input.use { SessionLog.read(it) }
            val sorted = rec.sightings.sortedBy { it.timeMs }
            val resolver = EntityResolver()
            val es = sorted.map { EntitySighting(resolver.resolve(it).entityId, it) }
            val start = sorted.firstOrNull()?.timeMs ?: 0L
            val end = sorted.lastOrNull()?.timeMs ?: 0L
            val cfg = AnalysisConfig(
                lookbackMs = (end - start) + 60_000L,
                alertScore = prefs.alertScore.toDouble(),
                alertMinPlaces = prefs.alertMinPlaces,
            )
            val result = Analyzer(cfg).analyze(
                end, es, rec.fixes, IgnoreList(apSsids = prefs.ownSsidSet()), familiar,
            )
            Outcome(result, rec.sightings.size, rec.fixes.size, end - start)
        }
}
