package dev.retrovision.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

private val Dark = darkColorScheme(
    primary = Color(0xFF3DDCFF),
    onPrimary = Color(0xFF00212A),
    secondary = Color(0xFF7CF29A),
    tertiary = Color(0xFFFFC857),
    error = Color(0xFFFF5C7A),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF0F151C),
    surfaceVariant = Color(0xFF18212B),
    surfaceContainer = Color(0xFF131A22),
    surfaceContainerHigh = Color(0xFF18212B),
    outline = Color(0xFF3A4A5C),
)

private val Light = lightColorScheme(
    primary = Color(0xFF006A80),
    secondary = Color(0xFF1B7A3E),
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFB3213F),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun RetrovisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content,
    )
}
