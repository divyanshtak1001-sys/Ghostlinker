package com.ghostlink.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

// ─── Font Families ────────────────────────────────────────────────────────────
// System monospace stack (JetBrains Mono / IBM Plex Mono fallback chain)
val GhostMono = FontFamily.Monospace

// Regular sans-serif for body and navigation
val GhostSans = FontFamily.Default

// ─── Typography ───────────────────────────────────────────────────────────────
val GhostTypography = Typography(
    // Screen / section headings
    headlineLarge = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
        color = GhostTextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
        color = GhostTextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
        color = GhostTextPrimary
    ),
    // Titles for cards and sections
    titleLarge = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
        color = GhostTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = GhostTextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
        color = GhostTextSecondary
    ),
    // Body text
    bodyLarge = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = GhostTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        color = GhostTextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
        color = GhostTextMuted
    ),
    // Labels
    labelLarge = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        color = GhostTextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = GhostSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        color = GhostTextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = GhostMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        color = GhostTextMuted
    )
)

// ─── Specific mono text styles for terminal elements ─────────────────────────
val GhostAliasStyle = TextStyle(
    fontFamily = GhostMono,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    letterSpacing = 0.sp,
    color = GhostGreenSecondary
)

val GhostCodeStyle = TextStyle(
    fontFamily = GhostMono,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    letterSpacing = 1.sp,
    color = GhostTextPrimary
)

val GhostTimestampStyle = TextStyle(
    fontFamily = GhostMono,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    letterSpacing = 0.3.sp,
    color = GhostTextMuted
)

val GhostStatusStyle = TextStyle(
    fontFamily = GhostMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    letterSpacing = 0.5.sp,
    color = GhostTextSecondary
)
