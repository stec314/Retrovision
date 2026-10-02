package dev.retrovision.core.wiki

import dev.retrovision.core.analysis.AnalysisConfig
import dev.retrovision.core.analysis.FamiliarConfig
import dev.retrovision.core.analysis.WifiThreats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WikiTest {
    private fun wikiFile(): File =
        listOf("../docs/WIKI.md", "../../docs/WIKI.md", "docs/WIKI.md").map(::File).first { it.isFile }

    @Test fun parsesBlocks() {
        val doc = WikiDoc.parse(
            """
            # Title
            Intro **bold** and `code`.

            ## Section A
            - one
              continued
            - two
            1. first

            | a | b |
            |---|---|
            | 1 | 2 |

            ```
            x = 1
            ```
            ### Sub
            """.trimIndent(),
        )
        val b = doc.blocks
        assertEquals(Block.Heading(1, "Title"), b[0])
        assertEquals(Block.Paragraph("Intro **bold** and `code`."), b[1])
        assertEquals(Block.Item("•", "one continued"), b[3])
        assertEquals(Block.Item("1.", "first"), b[5])
        assertEquals(Block.Table(listOf("a", "b"), listOf(listOf("1", "2"))), b[6])
        assertEquals(Block.Code("x = 1"), b[7])
        assertEquals(listOf("Section A", "Sub"), doc.sections.map { it.title })
    }

    @Test fun inlineMarkup() {
        val s = Inline.spans("a **b** `c` [d](http://e) *f*")
        assertTrue(s.contains(Inline.Span.Bold("b")))
        assertTrue(s.contains(Inline.Span.Code("c")))
        assertTrue(s.contains(Inline.Span.Link("d", "http://e")))
        assertTrue(s.contains(Inline.Span.Italic("f")))
        assertEquals("a b c d f", Inline.plain("a **b** `c` [d](http://e) *f*"))
        // multiplication sign in a formula is not italic
        assertEquals("0.40·places + 0.3 × familiar", Inline.plain("0.40·places + 0.3 × familiar"))
    }

    @Test fun realWikiIsNavigableAndSearchable() {
        val doc = WikiDoc.parse(wikiFile().readText())
        assertTrue("toc", doc.sections.size >= 15)
        assertTrue(doc.search("deauth flood").isNotEmpty())
        assertTrue(doc.search("AIRTAG separated").isNotEmpty())
        assertTrue(doc.search("zzzz-not-there").isEmpty())
    }

    /**
     * Keeps the wiki honest: if a documented default changes in code, this fails until the
     * wiki is updated too. Every release is built from a green test run, so the bundled wiki
     * cannot silently drift from the heuristics it describes.
     */
    @Test fun wikiMatchesCodeDefaults() {
        val w = wikiFile().readText()
        val a = AnalysisConfig()
        val t = WifiThreats.Config()
        val f = FamiliarConfig()
        fun has(s: String) = assertTrue("wiki should mention \"$s\"", s in w)
        has("score ≥ alert threshold** (default %.2f)".format(java.util.Locale.US, a.alertScore))
        has("minimum places** (default ${a.alertMinPlaces})")
        has("${a.placeRadiusM.toInt()} m radius")
        has("**±${a.maxFixAccuracyM.toInt()} m**")
        has("0.3** of an unfamiliar place".replace("0.3", a.familiarWeight.toString()))
        assertEquals("update the windows section of the wiki", listOf(5, 10, 15, 20), a.windowMinutes)
        has("0–5, 5–10, 10–15 and 15–20 minutes")
        has("**${t.deauthMinPerBssid}** deauth")
        has("**≥ ${t.karmaMinSsids} different SSIDs**")
        has("≥ ${f.minDays} days")
        has("≥ ${(f.nightShare * 100).toInt()}%")
        has("clamp(travel / ${a.travelSaturationM.toInt()} m)")
        has("clamp(span / ${a.spanSaturationMs / 60_000} min)")
    }
}
