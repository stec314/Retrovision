// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrovision.app.BuildConfig
import dev.retrovision.core.wiki.Block
import dev.retrovision.core.wiki.Inline
import dev.retrovision.core.wiki.WikiDoc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Opens the wiki over the current tab (from Settings or anywhere else). */
object WikiNav {
    val open = MutableStateFlow(false)
}

/** Loads docs/WIKI.md (bundled into the APK by Gradle) plus the per-build changelog CI adds. */
private fun loadWiki(ctx: android.content.Context): WikiDoc {
    val main = runCatching { ctx.assets.open("WIKI.md").bufferedReader().readText() }
        .getOrDefault("# Retrovision Wiki\n\nThe guide is not bundled in this build.")
    val changes = runCatching { ctx.assets.open("wiki-changes.md").bufferedReader().readText() }.getOrDefault("")
    return WikiDoc.parse(main + "\n\n" + changes)
}

@Composable
fun WikiScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val doc = remember { loadWiki(ctx) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showToc by remember { mutableStateOf(false) }
    BackHandler {
        when {
            showToc -> showToc = false
            query.isNotEmpty() -> query = ""
            else -> onClose()
        }
    }
    val accent = MaterialTheme.colorScheme.primary

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BackButton(onClose)
                Text(
                    Texts.tr("Guide", "Guida") + " · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showToc = !showToc; query = "" }) { Text(if (showToc) Texts.tr("Text", "Testo") else Texts.tr("Contents", "Indice")) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it; showToc = false }, singleLine = true,
                placeholder = { Text(Texts.tr("Search the guide (e.g. deauth, GPS, AirTag)", "Cerca nella guida (es. deauth, GPS, AirTag)")) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
            )
            Spacer(Modifier.padding(4.dp))

            fun jump(index: Int) {
                showToc = false
                query = ""
                scope.launch { list.scrollToItem(index) }
            }

            when {
                query.isNotBlank() -> {
                    val hits = remember(query) { doc.search(query) }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                if (hits.isEmpty()) Texts.tr("No results.", "Nessun risultato.")
                                else Texts.tr("${hits.size} results", "${hits.size} risultati"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        itemsIndexed(hits) { _, h ->
                            Column(
                                Modifier.fillMaxWidth().clickable { jump(h.blockIndex) }
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                            ) {
                                Text(h.section, style = MaterialTheme.typography.labelLarge, color = accent)
                                Text(highlight(h.snippet, query, accent), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                showToc -> {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        itemsIndexed(doc.sections) { _, s ->
                            Text(
                                s.title,
                                style = if (s.level == 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable { jump(s.blockIndex) }
                                    .padding(start = if (s.level == 2) 0.dp else 16.dp, top = 10.dp, bottom = 10.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
                else -> {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                        itemsIndexed(doc.blocks) { _, b -> BlockView(b, accent) }
                        item { Spacer(Modifier.padding(32.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockView(b: Block, accent: Color) {
    val ctx = LocalContext.current
    when (b) {
        is Block.Heading -> Text(
            inline(b.text, accent),
            style = when (b.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            color = if (b.level <= 2) accent else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = if (b.level <= 2) 20.dp else 12.dp, bottom = 6.dp),
        )
        is Block.Paragraph -> {
            val links = Inline.spans(b.text).filterIsInstance<Inline.Span.Link>().filter { it.url.startsWith("http") }
            Text(inline(b.text, accent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            links.forEach { l ->
                Text(
                    "↗ ${l.s}", color = accent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                    }.padding(vertical = 2.dp),
                )
            }
        }
        is Block.Item -> Row(Modifier.padding(vertical = 2.dp)) {
            Text(b.marker, color = accent, modifier = Modifier.width(24.dp))
            Text(inline(b.text, accent), style = MaterialTheme.typography.bodyMedium)
        }
        is Block.Code -> Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState()).padding(10.dp),
        ) {
            Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        is Block.Table -> Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp),
        ) {
            // Phones are narrow: each row becomes a small card, header labels in front of values.
            b.rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                row.forEachIndexed { c, cell ->
                    val label = b.header.getOrNull(c).orEmpty()
                    if (c == 0) {
                        Text(inline(cell, accent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(
                            buildAnnotatedString {
                                if (label.isNotEmpty()) withStyle(SpanStyle(color = accent)) { append(Inline.plain(label)); append(": ") }
                                append(inline(cell, accent))
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
    }
}

private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    for (s in Inline.spans(text)) when (s) {
        is Inline.Span.Text -> append(s.s)
        is Inline.Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.s) }
        is Inline.Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.s) }
        is Inline.Span.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x332A9DFF))) { append(s.s) }
        is Inline.Span.Link -> withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(s.s) }
    }
}

private fun highlight(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (w in query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        var i = lower.indexOf(w)
        while (i >= 0) {
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), i, i + w.length)
            i = lower.indexOf(w, i + w.length)
        }
    }
}
