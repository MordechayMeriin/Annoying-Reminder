package com.prominder.myapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** The app's palette: dark, with an amber accent. */
object Palette {
    val Bg = Color(0xFF111827)
    val Card = Color(0xFF1F2937)
    val Card2 = Color(0xFF273244)
    val Line = Color(0xFF374151)
    val Text = Color(0xFFF3F4F6)
    val Muted = Color(0xFF9CA3AF)
    val Accent = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Ok = Color(0xFF22C55E)
}

private val Colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF111111),
    secondary = Palette.Accent,
    onSecondary = Color(0xFF111111),
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.Card2,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Danger,
)

/**
 * The app's words are English, so its layout stays left-to-right even when the phone is set to Hebrew (otherwise
 * Android mirrors it and moves English sentences' periods to the wrong end). Hebrew text, like chat messages,
 * still reads right-to-left: each paragraph takes its direction from its own first letters.
 */
@Composable
fun ReminderTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = Colors, content = content)
    }
}
