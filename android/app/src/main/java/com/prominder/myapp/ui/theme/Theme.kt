package com.prominder.myapp.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/**
 * The app's colours, one set per theme (only the dark one for now). Screens read them through [Palette], which reads
 * the set the theme provides, so a light theme later is a second [AppColors] and nothing else.
 *
 * What each colour means:
 * - [accent] (amber): something to tap that does something (the primary button, ▶ Start, the selected length). Not for
 *   links, labels or "this setting changed": those use [text], [muted] or a tag.
 * - [ok] (green): done. [danger] (red): overdue, or something destructive. Not for a passing network problem.
 * - [userBubble]: your own messages in the chat, a hue nothing else uses, so they never look like the assistant's.
 */
@Immutable
data class AppColors(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val ok: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val onUserBubbleMuted: Color,
)

val DarkColors = AppColors(
    bg = Color(0xFF111827),
    card = Color(0xFF1F2937),
    card2 = Color(0xFF273244),
    line = Color(0xFF374151),
    text = Color(0xFFF3F4F6),
    muted = Color(0xFF9CA3AF),
    accent = Color(0xFFF59E0B),
    onAccent = Color(0xFF111111),
    danger = Color(0xFFEF4444),
    ok = Color(0xFF22C55E),
    userBubble = Color(0xFF2F5B9A),
    onUserBubble = Color(0xFFF5F8FF),
    onUserBubbleMuted = Color(0xFFC3D3EE),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

/** The current theme's colours (see [AppColors] for what each one means). */
object Palette {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.bg
    val Card: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.card
    val Card2: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.card2
    val Line: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.line
    val Text: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.danger
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.ok
    val UserBubble: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.userBubble
    val OnUserBubble: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onUserBubble
    val OnUserBubbleMuted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onUserBubbleMuted
}

private fun schemeOf(c: AppColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    secondary = c.accent,
    onSecondary = c.onAccent,
    background = c.bg,
    onBackground = c.text,
    surface = c.bg,
    onSurface = c.text,
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    surfaceContainer = c.card,
    surfaceContainerHigh = c.card2,
    surfaceContainerHighest = c.card2,
    inverseSurface = c.text,
    inverseOnSurface = c.bg,
    inversePrimary = c.accent,
    outline = c.line,
    outlineVariant = c.line,
    error = c.danger,
)

/**
 * The layout follows the app's language (i18n/AppLanguage.kt): right-to-left in Hebrew, left-to-right in English.
 * Text inside it, like chat messages, takes its direction from its own first letters, so an English message reads
 * left-to-right in the Hebrew layout and the other way round.
 */
@Composable
fun ReminderTheme(content: @Composable () -> Unit) {
    val colors = DarkColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = Type) {
            ProvideTextStyle(LocalTextStyle.current.copy(textDirection = TextDirection.Content), content)
        }
    }
}

/**
 * Material's type scale, each style taking its direction from the text itself (see above). Screens use these slots
 * instead of fixed sizes, so every size follows the phone's font setting the same way:
 * - labelSmall (11): the tiniest lines (a bubble's time, the model's name)
 * - bodySmall (12): grey second lines (a task's time, a setting's help, section headings)
 * - labelMedium (13): status lines (the assistant's steps, errors)
 * - bodyMedium (14): chat text in bubbles, short notes
 * - bodyLarge (16): the default text
 */
private val Type: Typography = Typography().run {
    fun TextStyle.byContent() = copy(textDirection = TextDirection.Content)
    Typography(
        displayLarge.byContent(), displayMedium.byContent(), displaySmall.byContent(),
        headlineLarge.byContent(), headlineMedium.byContent(), headlineSmall.byContent(),
        titleLarge.byContent(), titleMedium.byContent(), titleSmall.byContent(),
        bodyLarge.byContent(), bodyMedium.byContent(), bodySmall.byContent(),
        labelLarge.byContent(),
        labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp).byContent(),
        labelSmall.byContent(),
    )
}
