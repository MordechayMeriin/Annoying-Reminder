package com.prominder.myapp.ui.theme

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
 * The layout follows the app's language (i18n/AppLanguage.kt): right-to-left in Hebrew, left-to-right in English.
 * Text inside it, like chat messages, takes its direction from its own first letters, so an English message reads
 * left-to-right in the Hebrew layout and the other way round.
 */
@Composable
fun ReminderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type) {
        ProvideTextStyle(LocalTextStyle.current.copy(textDirection = TextDirection.Content), content)
    }
}

/** Material's type scale, each style taking its direction from the text itself (see above). */
private val Type: Typography = Typography().run {
    fun TextStyle.byContent() = copy(textDirection = TextDirection.Content)
    Typography(
        displayLarge.byContent(), displayMedium.byContent(), displaySmall.byContent(),
        headlineLarge.byContent(), headlineMedium.byContent(), headlineSmall.byContent(),
        titleLarge.byContent(), titleMedium.byContent(), titleSmall.byContent(),
        bodyLarge.byContent(), bodyMedium.byContent(), bodySmall.byContent(),
        labelLarge.byContent(), labelMedium.byContent(), labelSmall.byContent(),
    )
}
