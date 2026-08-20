package com.rocs.demo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    background = Color(0xFF1A1A2E),
    surface    = Color(0xFF16213E),
    primary    = Color(0xFF7C3AED),
    onPrimary  = Color.White,
    onBackground = Color.White,
    onSurface    = Color.White,
)

@Composable
fun RocsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content,
    )
}
