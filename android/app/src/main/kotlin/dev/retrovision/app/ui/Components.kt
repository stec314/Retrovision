// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.retrovision.core.analysis.Level

/** Small tracked uppercase label above a group: the app's section voice. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The one card style: quiet surface, hairline border, generous padding. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = tint?.copy(alpha = 0.10f)?.compositeOverSurface() ?: MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, tint?.copy(alpha = 0.35f) ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Overline(title, Modifier.weight(1f))
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

@Composable
private fun Color.compositeOverSurface(): Color {
    val s = MaterialTheme.colorScheme.surfaceContainer
    val a = alpha
    return Color(red * a + s.red * (1 - a), green * a + s.green * (1 - a), blue * a + s.blue * (1 - a), 1f)
}

/** A section that opens on tap. [summary] says what's inside while closed. */
@Composable
fun Expandable(
    title: String,
    summary: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .semantics { role = Role.Button; stateDescription = if (open) "open" else "closed" }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (summary != null && !open) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

/** A number with a label underneath, for overview rows. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun levelColor(l: Level): Color = when (l) {
    Level.STRONG -> MaterialTheme.colorScheme.error
    Level.WORTH_A_LOOK -> MaterialTheme.colorScheme.tertiary
    Level.SOME -> MaterialTheme.colorScheme.onSurfaceVariant
    Level.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The level as a small pill: dot + words. Colour is never the only cue. */
@Composable
fun LevelPill(l: Level) {
    val c = levelColor(l)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text("  " + Texts.level(l), style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A full-screen layer over the app. Android 15 draws every window edge to edge, and inside a dialog
 * window Compose often gets no system-bar insets, so content slid under the status bar and the
 * navigation buttons. The insets are read here from the activity (where they are always right) and
 * applied inside the dialog as plain padding. Every full-screen dialog goes through this.
 */
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, background: Color? = null, content: @Composable () -> Unit) {
    val safe = androidx.compose.foundation.layout.WindowInsets.systemBars
        .union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
        .asPaddingValues()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = background ?: MaterialTheme.colorScheme.background) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(safe).imePadding()) { content() }
        }
    }
}

/** The one back arrow: a full-size icon button (48 dp touch target), never a text glyph. */
@Composable
fun BackButton(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = dev.retrovision.app.ui.Texts.tr("Back", "Indietro"), modifier = Modifier.size(26.dp))
    }
}
