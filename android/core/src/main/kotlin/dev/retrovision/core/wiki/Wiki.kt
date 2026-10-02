package dev.retrovision.core.wiki

/**
 * Minimal Markdown model for the in-app wiki (docs/WIKI.md, bundled into the APK).
 * Supports exactly what the wiki uses: ATX headings, paragraphs, bullet and numbered
 * lists, fenced code, pipe tables, rules. Inline markup (bold, code, links) is left in
 * the text and handled by [Inline].
 */
sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    /** [marker] is "•" for bullets or "3." for numbered items. */
    data class Item(val marker: String, val text: String) : Block()
    data class Code(val text: String) : Block()
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
    object Rule : Block()
}

class Section(val title: String, val level: Int, val blockIndex: Int)

class Hit(val blockIndex: Int, val section: String, val snippet: String)

class WikiDoc(val blocks: List<Block>) {
    /** Table of contents: level-2 and level-3 headings. */
    val sections: List<Section> = blocks.mapIndexedNotNull { i, b ->
        if (b is Block.Heading && b.level in 2..3) Section(Inline.plain(b.text), b.level, i) else null
    }

    /** Case-insensitive search across all text; every query word must appear in the block. */
    fun search(query: String, max: Int = 60): List<Hit> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        var section = ""
        for ((i, b) in blocks.withIndex()) {
            if (b is Block.Heading && b.level <= 3) section = Inline.plain(b.text)
            val text = Inline.plain(textOf(b))
            val lower = text.lowercase()
            if (words.all { it in lower } || (b !is Block.Heading && words.all { it in (section.lowercase() + " " + lower) } && words.any { it in lower })) {
                out += Hit(i, section, snippet(text, lower.indexOf(words.first { it in lower })))
                if (out.size >= max) break
            }
        }
        return out
    }

    private fun snippet(text: String, at: Int): String {
        val start = (at - 50).coerceAtLeast(0)
        val end = (at + 110).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ') + (if (end < text.length) "…" else "")
    }

    companion object {
        fun textOf(b: Block): String = when (b) {
            is Block.Heading -> b.text
            is Block.Paragraph -> b.text
            is Block.Item -> b.text
            is Block.Code -> b.text
            is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { it.joinToString(" · ") }
            Block.Rule -> ""
        }

        fun parse(md: String): WikiDoc {
            val lines = md.replace("\r\n", "\n").split('\n')
            val out = ArrayList<Block>()
            val para = StringBuilder()
            fun flush() {
                if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
                para.clear()
            }
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val t = line.trim()
                when {
                    t.startsWith("```") -> {
                        flush()
                        val code = StringBuilder()
                        i++
                        while (i < lines.size && !lines[i].trim().startsWith("```")) {
                            code.append(lines[i]).append('\n'); i++
                        }
                        out += Block.Code(code.toString().trimEnd())
                    }
                    t.startsWith("#") && t.trimStart('#').startsWith(" ") -> {
                        flush()
                        val level = t.takeWhile { it == '#' }.length
                        out += Block.Heading(level, t.drop(level).trim())
                    }
                    t.startsWith("|") -> {
                        flush()
                        val rows = ArrayList<List<String>>()
                        while (i < lines.size && lines[i].trim().startsWith("|")) {
                            val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                            if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                            i++
                        }
                        i--
                        if (rows.isNotEmpty()) out += Block.Table(rows.first(), rows.drop(1))
                    }
                    t == "---" || t == "***" -> { flush(); out += Block.Rule }
                    t.startsWith("- ") || t.startsWith("* ") -> {
                        flush()
                        out += Block.Item("•", continued(lines, i, t.drop(2)).also { i = it.second }.first)
                    }
                    Regex("^\\d+\\.\\s").containsMatchIn(t) -> {
                        flush()
                        val n = t.substringBefore('.')
                        out += Block.Item("$n.", continued(lines, i, t.substringAfter(". ")).also { i = it.second }.first)
                    }
                    t.isEmpty() -> flush()
                    else -> para.append(if (para.isEmpty()) t else " $t")
                }
                i++
            }
            flush()
            return WikiDoc(out)
        }

        /** List items may wrap onto indented continuation lines. Returns (text, last line index). */
        private fun continued(lines: List<String>, start: Int, first: String): Pair<String, Int> {
            val sb = StringBuilder(first)
            var j = start + 1
            while (j < lines.size && lines[j].startsWith("  ") && lines[j].isNotBlank() &&
                !lines[j].trim().let { it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d+\\.\\s").containsMatchIn(it) }
            ) {
                sb.append(' ').append(lines[j].trim()); j++
            }
            return sb.toString() to (j - 1)
        }
    }
}

/** Inline markup: **bold**, `code`, [text](url). */
object Inline {
    sealed class Span {
        data class Text(val s: String) : Span()
        data class Bold(val s: String) : Span()
        data class Code(val s: String) : Span()
        data class Link(val s: String, val url: String) : Span()
        data class Italic(val s: String) : Span()
    }

    private val token = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])""")

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var pos = 0
        for (m in token.findAll(text)) {
            if (m.range.first > pos) out += Span.Text(text.substring(pos, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Span.Bold(g[1])
                g[2].isNotEmpty() -> Span.Code(g[2])
                g[3].isNotEmpty() -> Span.Link(g[3], g[4])
                else -> Span.Italic(g[5])
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) out += Span.Text(text.substring(pos))
        return out
    }

    fun plain(text: String): String = spans(text).joinToString("") {
        when (it) {
            is Span.Text -> it.s
            is Span.Bold -> it.s
            is Span.Code -> it.s
            is Span.Link -> it.s
            is Span.Italic -> it.s
        }
    }
}
