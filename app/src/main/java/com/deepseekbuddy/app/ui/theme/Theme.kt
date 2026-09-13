package com.deepseekbuddy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4D6BFE),
    secondary = Color(0xFF7C8CF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AA8FF),
    secondary = Color(0xFFB9C2FF),
)

@Composable
fun DeepSeekBuddyTheme(
    themeMode: String = "system",   // system / light / dark
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
