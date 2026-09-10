package com.celestial.latent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Latent palette: dark, warm-neutral, one amber accent (matches the mockups).
object LatentColors {
    val Background = Color(0xFF161615)
    val Surface = Color(0xFF2C2C2A)
    val Line = Color(0xFF5F5E5A)
    val TextDim = Color(0xFF888780)
    val Text = Color(0xFFB4B2A9)
    val TextBright = Color(0xFFF1EFE8)
    val Amber = Color(0xFFFAC775)
    val AmberInk = Color(0xFF412402)
}

private val scheme = darkColorScheme(
    primary = LatentColors.Amber,
    onPrimary = LatentColors.AmberInk,
    background = LatentColors.Background,
    onBackground = LatentColors.TextBright,
    surface = LatentColors.Surface,
    onSurface = LatentColors.TextBright,
)

@Composable
fun LatentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
