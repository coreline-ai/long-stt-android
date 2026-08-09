package com.stt.benchmark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ArchiveInk = Color(0xFF151614)
val ArchivePaper = Color(0xFFEEE8DC)
val ArchiveCopper = Color(0xFFC07148)
val ArchiveMoss = Color(0xFF7E8C78)
val ArchiveFog = Color(0xFFA9AAA3)
val ArchiveError = Color(0xFFD06A60)
val ArchiveHairline = Color(0xFF343530)

internal val LightArchivePaper = Color(0xFFF7F3EA)
internal val LightArchiveCopper = Color(0xFFA95632)
internal val LightArchiveMoss = Color(0xFF5F6D58)

private val DarkColorScheme = darkColorScheme(
    primary = ArchiveCopper,
    onPrimary = ArchiveInk,
    primaryContainer = Color(0xFF4A2D20),
    onPrimaryContainer = ArchivePaper,
    secondary = ArchiveMoss,
    onSecondary = ArchiveInk,
    secondaryContainer = Color(0xFF30382E),
    onSecondaryContainer = ArchivePaper,
    tertiary = ArchiveFog,
    onTertiary = ArchiveInk,
    background = ArchiveInk,
    onBackground = ArchivePaper,
    surface = Color(0xFF1D1E1B),
    onSurface = ArchivePaper,
    surfaceVariant = Color(0xFF292A26),
    onSurfaceVariant = ArchiveFog,
    surfaceContainer = Color(0xFF1D1E1B),
    surfaceContainerHigh = Color(0xFF252622),
    surfaceContainerHighest = Color(0xFF2B2C28),
    error = ArchiveError,
    outline = ArchiveHairline,
)

private val LightColorScheme = lightColorScheme(
    primary = LightArchiveCopper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0D8C9),
    onPrimaryContainer = Color(0xFF4C2416),
    secondary = LightArchiveMoss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5D8),
    onSecondaryContainer = Color(0xFF263023),
    tertiary = Color(0xFF77736A),
    onTertiary = Color.White,
    background = LightArchivePaper,
    onBackground = Color(0xFF24241F),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF24241F),
    surfaceVariant = Color(0xFFE9E2D6),
    onSurfaceVariant = Color(0xFF6E706A),
    surfaceContainer = Color(0xFFF1EADF),
    surfaceContainerHigh = Color(0xFFEAE2D5),
    surfaceContainerHighest = Color(0xFFE3DBCF),
    error = Color(0xFFA73F39),
    outline = Color(0xFFDDD6CA),
)

/**
 * Font files are intentionally not bundled until their redistribution notice is approved.
 * These system fallbacks preserve the editorial/UI contrast without adding an untracked asset.
 */
val ArchiveSans = FontFamily.SansSerif
val ArchiveSerif = FontFamily.Serif

private val ArchiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 54.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontFamily = ArchiveSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchiveSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = ArchiveSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun SttBenchmarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colors,
        typography = ArchiveTypography,
        content = content
    )
}
