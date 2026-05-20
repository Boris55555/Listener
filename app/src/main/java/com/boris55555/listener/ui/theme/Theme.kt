package com.boris55555.listener.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EInkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = Color.Black,
    onError = Color.White
)

@Composable
fun ListenerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EInkColorScheme,
        typography = Typography,
        content = content
    )
}
