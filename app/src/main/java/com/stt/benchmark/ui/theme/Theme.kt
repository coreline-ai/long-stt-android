package com.stt.benchmark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82DAFF),
    secondary = Color(0xFF4DBACC),
    tertiary = Color(0xFFFFB77C)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00658E),
    secondary = Color(0xFF4A626B),
    tertiary = Color(0xFF715641)
)

@Composable
fun SttBenchmarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
