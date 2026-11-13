package com.vknxworks.mailctrl.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vknxworks.mailctrl.R

/** One Ctrl / Signal palette. [CtrlColors.Dark] and [CtrlColors.Light] are the two the app switches between. */
@Immutable
data class CtrlPalette(
    val isDark: Boolean,
    /** Accent fills: hero cards, primary buttons, the open tab, switch tracks, PIN dots, your bubbles. */
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    /** Accent-coloured text, icons and thin lines on the page (the fill is too pale for that on white). */
    val accentText: Color,
    val background: Color,
    /** Cards, sheets, dialogs and incoming bubbles. */
    val surface: Color,
    val raised: Color,
    /** Card borders. */
    val outline: Color,
    /** Borders on controls (buttons, fields, the profile button, the tab bar). */
    val controlOutline: Color,
    /** Lines between list rows. */
    val divider: Color,
    val text: Color,
    val muted: Color,
    /** Disabled and "off" states. */
    val inactive: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    /** Progress bar track. */
    val track: Color,
    /** Progress bar fill (the accent is used for the primary metric). */
    val progress: Color,
    val switchOff: Color,
    val pinEmpty: Color,
)

/** The current palette; [MailCtrlTheme] picks it from the device's dark/light setting. */
val LocalCtrlColors = staticCompositionLocalOf { CtrlColors.Dark }

/**
 * Ctrl / Signal colours, shared by every Ctrl app. The app follows the device's dark/light setting.
 * [AccentFill] is the one place to change the accent colour. The other names read the current palette.
 */
object CtrlColors {
    /** The accent, the same in dark and light. */
    val AccentFill = Color(0xFFB48CFF)
    /** Accent for text and icons on light backgrounds. */
    val AccentInk = Color(0xFF6A3BDB)
    private val Ink = Color(0xFF08080A)

    val Dark = CtrlPalette(
        isDark = true,
        accent = AccentFill,
        onAccent = Ink,
        accentText = AccentFill,
        background = Color(0xFF08080A),
        surface = Color(0xFF141417),
        raised = Color(0xFF1C1C21),
        outline = Color(0xFF232328),
        controlOutline = Color(0xFF2A2A2F),
        divider = Color(0xFF1E1E23),
        text = Color(0xFFF4F4F2),
        muted = Color(0xFFA3A3AB),
        inactive = Color(0xFF8C8C94),
        error = Color(0xFFFF6B6B),
        onError = Ink,
        errorContainer = Color(0xFF3A1A1C),
        onErrorContainer = Color(0xFFFFD9D9),
        track = Color(0xFF26262C),
        progress = Color(0xFFF4F4F2),
        switchOff = Color(0xFF2A2A30),
        pinEmpty = Color(0xFF3A3A41),
    )

    val Light = CtrlPalette(
        isDark = false,
        accent = AccentFill,
        onAccent = Ink,
        accentText = AccentInk,
        background = Color(0xFFF5F5F1),
        surface = Color(0xFFFFFFFF),
        raised = Color(0xFFECECE7),
        outline = Color(0xFFDEDED7),
        controlOutline = Color(0xFFD2D2CA),
        divider = Color(0xFFE6E6E0),
        text = Color(0xFF0B0B0D),
        muted = Color(0xFF5C5C64),
        inactive = Color(0xFF74747C),
        error = Color(0xFFD93636),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFBE3E3),
        onErrorContainer = Color(0xFF5C1111),
        track = Color(0xFFE2E2DC),
        progress = Color(0xFF0B0B0D),
        switchOff = Color(0xFFDCDCD5),
        pinEmpty = Color(0xFFC9C9C1),
    )

    val Accent: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.onAccent
    val AccentText: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.accentText
    val Background: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.surface
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.raised
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.outline
    val ControlOutline: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.controlOutline
    val Divider: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.divider
    val Text: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.muted
    val Inactive: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.inactive
    val Error: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.error
    val Track: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.track
    val Progress: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.progress
    val SwitchOff: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.switchOff
    val PinEmpty: Color @Composable @ReadOnlyComposable get() = LocalCtrlColors.current.pinEmpty
}

private fun schemeFor(p: CtrlPalette): ColorScheme {
    // In light, Material's own accent-coloured text (text buttons, pickers, spinners) needs the darker ink;
    // Ctrl components that fill with the accent use CtrlColors.Accent directly.
    val primary = if (p.isDark) p.accent else p.accentText
    val onPrimary = if (p.isDark) p.onAccent else Color.White
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = p.accent,
        onPrimaryContainer = p.onAccent,
        inversePrimary = p.accent,
        secondary = p.text,
        onSecondary = p.background,
        secondaryContainer = p.raised,
        onSecondaryContainer = p.text,
        tertiary = p.accentText,
        onTertiary = onPrimary,
        tertiaryContainer = p.raised,
        onTertiaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.raised,
        onSurfaceVariant = p.muted,
        surfaceTint = p.surface,
        inverseSurface = p.text,
        inverseOnSurface = p.background,
        error = p.error,
        onError = p.onError,
        errorContainer = p.errorContainer,
        onErrorContainer = p.onErrorContainer,
        outline = p.controlOutline,
        outlineVariant = p.outline,
        scrim = Color.Black,
        surfaceBright = p.raised,
        surfaceDim = p.background,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.raised,
        surfaceContainerHighest = p.outline,
    )
}

private val DarkScheme = schemeFor(CtrlColors.Dark)
private val LightScheme = schemeFor(CtrlColors.Light)

// Space Grotesk is one variable font (weights 300-700); JetBrains Mono comes as three static weights.
private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** All UI text and headings. */
val SpaceGrotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))

/** Numbers, data readouts, section labels and the wordmark. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, family: FontFamily = SpaceGrotesk) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

private val CtrlTypography = Typography(
    // Big numeric readouts.
    displayLarge = style(64, 60, 700, -0.05),
    // Display / hero.
    displayMedium = style(56, 54, 700, -0.04),
    displaySmall = style(44, 46, 700, -0.035),
    // Headline (main tab titles).
    headlineLarge = style(40, 42, 700, -0.035),
    headlineMedium = style(32, 36, 700, -0.03),
    headlineSmall = style(24, 30, 700, -0.02),
    titleLarge = style(20, 26, 600, -0.01),
    // Title.
    titleMedium = style(18, 24, 600),
    titleSmall = style(15, 20, 600),
    // Body.
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 20, 400),
    bodySmall = style(12, 17, 400),
    labelLarge = style(14, 20, 600),
    labelMedium = style(12, 16, 500),
    labelSmall = style(11, 14, 500),
)

/** Mono text styles that Material's type scale has no slot for. */
object CtrlType {
    /** "TEXT/CTRL" in the top bar. */
    val Wordmark = style(13, 16, 600, 0.14, JetBrainsMono)
    /** Section headers: callers upper-case the text; shown in the muted colour. */
    val Label = style(11, 14, 500, 0.16, JetBrainsMono)
    /** Small data next to a title: times, numbers, counts. */
    val Meta = style(12, 16, 400, 0.0, JetBrainsMono)
    /** Status words at the end of a row. */
    val Status = style(11, 14, 500, 0.1, JetBrainsMono)
    /** Lock screen keys and other big mono readouts. */
    val Key = style(26, 30, 500, 0.0, JetBrainsMono)
    /** The giant number on a hero card. */
    val Readout = style(64, 58, 700, -0.05)
}

private val CtrlShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Horizontal page margin used everywhere. */
val Gutter = 20.dp

/** The Ctrl / Signal theme. Follows the device's dark/light setting; no wallpaper colours, no in-app choice. */
@Composable
fun MailCtrlTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) CtrlColors.Dark else CtrlColors.Light
    CompositionLocalProvider(LocalCtrlColors provides palette) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = CtrlTypography, shapes = CtrlShapes, content = content)
    }
}
