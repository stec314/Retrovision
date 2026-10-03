// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Map palette: deliberately dark in both themes so the track stays readable at night. */
object MapColors {
    val background = Color(0xFF0B0F14)
    val grid = Color(0xFF1B2430)
    val gridMajor = Color(0xFF273445)
    val track = Color(0xFF3DDCFF)
    val trackGlow = Color(0x333DDCFF)
    val routine = Color(0xFF7CF29A)
    val me = Color(0xFFFF5C7A)
    val label = Color(0xFF8FA3B8)
    val stay = Color(0xFFFFC857)
    val select = Color(0xFFFFFFFF)
}

// Graphite and one muted accent: calm, legible in sunlight and at night, colour reserved for meaning
// (coral = strong signs, amber = worth a look, green = fine).
private val Dark = darkColorScheme(
    primary = Color(0xFF5CC6D0),
    onPrimary = Color(0xFF00282D),
    primaryContainer = Color(0xFF123A40),
    onPrimaryContainer = Color(0xFFBFEFF3),
    secondary = Color(0xFF7FD3A3),
    tertiary = Color(0xFFE5B85C),
    error = Color(0xFFF2777F),
    errorContainer = Color(0xFF3A1D22),
    onErrorContainer = Color(0xFFFFD9DC),
    tertiaryContainer = Color(0xFF3A2F17),
    onTertiaryContainer = Color(0xFFFFE6B3),
    background = Color(0xFF0D1015),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF0D1015),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF1B212A),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = Color(0xFF12161C),
    surfaceContainer = Color(0xFF151A21),
    surfaceContainerHigh = Color(0xFF181E26),
    surfaceContainerHighest = Color(0xFF161B22),
    outline = Color(0xFF2B333F),
    outlineVariant = Color(0xFF222933),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7480),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F1F4),
    onPrimaryContainer = Color(0xFF00363C),
    secondary = Color(0xFF1E7A4A),
    tertiary = Color(0xFF8A5B00),
    error = Color(0xFFBF2F45),
    errorContainer = Color(0xFFFBE3E6),
    onErrorContainer = Color(0xFF5A0D19),
    tertiaryContainer = Color(0xFFFBEFD6),
    onTertiaryContainer = Color(0xFF3A2600),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF151A21),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF151A21),
    surfaceVariant = Color(0xFFE9ECF0),
    onSurfaceVariant = Color(0xFF586271),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFD3D8DF),
    outlineVariant = Color(0xFFE3E7EC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Tighter, more editorial type: stronger titles, quieter body, tracked labels. */
private val AppType = androidx.compose.material3.Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = t.bodySmall.copy(lineHeight = 18.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun RetrovisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        typography = AppType,
        content = content,
    )
}
