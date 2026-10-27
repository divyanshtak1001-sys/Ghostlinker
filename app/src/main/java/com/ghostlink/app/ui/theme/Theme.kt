package com.ghostlink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── App Theme Selection ──────────────────────────────────────────────────────
enum class GhostTheme { ShadowGreen, QuietBlue }

// ─── Shadow Green Color Scheme ────────────────────────────────────────────────
private val ShadowGreenColorScheme = darkColorScheme(
    primary          = GhostGreenAction,
    onPrimary        = GhostTextPrimary,
    primaryContainer = GhostGreenPrimary,
    onPrimaryContainer = GhostTextPrimary,
    secondary        = GhostGreenSecondary,
    onSecondary      = GhostTextPrimary,
    secondaryContainer = GhostBorderSubtle,
    onSecondaryContainer = GhostTextSecondary,
    tertiary         = GhostGreenMutedText,
    onTertiary       = GhostMainBackground,
    tertiaryContainer = GhostElevatedPanel,
    error            = GhostError,
    onError          = GhostTextPrimary,
    errorContainer   = Color(0xFF221111),
    background       = GhostScreenBackground,
    onBackground     = GhostTextPrimary,
    surface          = GhostCardBackground,
    onSurface        = GhostTextPrimary,
    surfaceVariant   = GhostElevatedPanel,
    onSurfaceVariant = GhostTextSecondary,
    outline          = GhostBorderSubtle,
    outlineVariant   = GhostDividerLine,
    inverseSurface   = GhostTextPrimary,
    inverseOnSurface = GhostMainBackground,
    inversePrimary   = GhostGreenAction,
    surfaceTint      = GhostGreenPrimary,
    scrim            = Color(0xCC030604)
)

// ─── Quiet Blue Color Scheme ──────────────────────────────────────────────────
private val QuietBlueColorScheme = darkColorScheme(
    primary          = GhostBlueAction,
    onPrimary        = GhostTextPrimary,
    primaryContainer = GhostBlueMuted,
    onPrimaryContainer = GhostTextPrimary,
    secondary        = GhostBlueAccent,
    onSecondary      = GhostTextPrimary,
    secondaryContainer = GhostBlueBorder,
    onSecondaryContainer = GhostTextSecondary,
    tertiary         = GhostBlueMutedText,
    onTertiary       = GhostMainBackground,
    tertiaryContainer = GhostBlueSurface,
    error            = GhostError,
    onError          = GhostTextPrimary,
    errorContainer   = Color(0xFF221111),
    background       = GhostScreenBackground,
    onBackground     = GhostTextPrimary,
    surface          = GhostCardBackground,
    onSurface        = GhostTextPrimary,
    surfaceVariant   = GhostBlueSurface,
    onSurfaceVariant = GhostTextSecondary,
    outline          = GhostBlueBorder,
    outlineVariant   = GhostBlueDivider,
    inverseSurface   = GhostTextPrimary,
    inverseOnSurface = GhostMainBackground,
    inversePrimary   = GhostBlueAction,
    surfaceTint      = GhostBlueMuted,
    scrim            = Color(0xCC030604)
)

// ─── Extended Color Composition Local ─────────────────────────────────────────
data class GhostExtendedColors(
    val mainBackground: Color,
    val screenBackground: Color,
    val cardBackground: Color,
    val elevatedBackground: Color,
    val actionPrimary: Color,
    val primaryDark: Color,
    val secondaryGreen: Color,
    val mutedTextGreen: Color,
    val borderSubtle: Color,
    val dividerLine: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val warning: Color,
    val error: Color,
    val theme: GhostTheme,
    // Backwards compatibility properties
    val accent: Color = actionPrimary,
    val accentMuted: Color = primaryDark,
    val accentBorder: Color = borderSubtle,
    val success: Color = secondaryGreen,
    val card: Color = cardBackground,
    val elevated: Color = elevatedBackground,
    val background: Color = screenBackground,
    val surface: Color = cardBackground
)

val LocalGhostColors = staticCompositionLocalOf {
    GhostExtendedColors(
        mainBackground     = GhostMainBackground,
        screenBackground   = GhostScreenBackground,
        cardBackground     = GhostCardBackground,
        elevatedBackground = GhostElevatedPanel,
        actionPrimary      = GhostGreenAction,
        primaryDark        = GhostGreenPrimary,
        secondaryGreen     = GhostGreenSecondary,
        mutedTextGreen     = GhostGreenMutedText,
        borderSubtle       = GhostBorderSubtle,
        dividerLine        = GhostDividerLine,
        textPrimary        = GhostTextPrimary,
        textSecondary      = GhostTextSecondary,
        textMuted          = GhostTextMuted,
        warning            = GhostWarning,
        error              = GhostError,
        theme              = GhostTheme.ShadowGreen
    )
}

val GhostColors @Composable get() = LocalGhostColors.current

@Composable
fun GhostLinkTheme(
    selectedTheme: GhostTheme = GhostTheme.ShadowGreen,
    content: @Composable () -> Unit
) {
    val colorScheme = when (selectedTheme) {
        GhostTheme.ShadowGreen -> ShadowGreenColorScheme
        GhostTheme.QuietBlue   -> QuietBlueColorScheme
    }

    val extendedColors = when (selectedTheme) {
        GhostTheme.ShadowGreen -> GhostExtendedColors(
            mainBackground     = GhostMainBackground,
            screenBackground   = GhostScreenBackground,
            cardBackground     = GhostCardBackground,
            elevatedBackground = GhostElevatedPanel,
            actionPrimary      = GhostGreenAction,
            primaryDark        = GhostGreenPrimary,
            secondaryGreen     = GhostGreenSecondary,
            mutedTextGreen     = GhostGreenMutedText,
            borderSubtle       = GhostBorderSubtle,
            dividerLine        = GhostDividerLine,
            textPrimary        = GhostTextPrimary,
            textSecondary      = GhostTextSecondary,
            textMuted          = GhostTextMuted,
            warning            = GhostWarning,
            error              = GhostError,
            theme              = GhostTheme.ShadowGreen
        )
        GhostTheme.QuietBlue -> GhostExtendedColors(
            mainBackground     = GhostMainBackground,
            screenBackground   = GhostScreenBackground,
            cardBackground     = GhostCardBackground,
            elevatedBackground = GhostBlueSurface,
            actionPrimary      = GhostBlueAction,
            primaryDark        = GhostBlueMuted,
            secondaryGreen     = GhostBlueAccent,
            mutedTextGreen     = GhostBlueMutedText,
            borderSubtle       = GhostBlueBorder,
            dividerLine        = GhostBlueDivider,
            textPrimary        = GhostTextPrimary,
            textSecondary      = GhostTextSecondary,
            textMuted          = GhostTextMuted,
            warning            = GhostWarning,
            error              = GhostError,
            theme              = GhostTheme.QuietBlue
        )
    }

    CompositionLocalProvider(LocalGhostColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = GhostTypography,
            content     = content
        )
    }
}
