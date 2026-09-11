package com.yzrun.ropecounter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RopeColorScheme = darkColorScheme(
    primary = Color(0xFFB8F35A),
    onPrimary = Color(0xFF172000),
    secondary = Color(0xFF80D8FF),
    background = Color(0xFF0E110D),
    onBackground = Color(0xFFF1F5EA),
    surface = Color(0xFF191D17),
    onSurface = Color(0xFFF1F5EA),
    surfaceVariant = Color(0xFF282D25),
    onSurfaceVariant = Color(0xFFC5CCBE),
    error = Color(0xFFFFB4AB),
)

@Composable
fun RopeCounterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RopeColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
