package app.maestri.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.maestri.ui.Style.DarkMaestriColors
import app.maestri.ui.Style.LightMaestriColors
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriDarkScheme
import app.maestri.ui.Style.MaestriLightScheme
import app.maestri.ui.Style.MaestriShapes

@Composable
fun MaestriTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalMaestriColors provides if (dark) DarkMaestriColors else LightMaestriColors) {
        MaterialTheme(
            colorScheme = if (dark) MaestriDarkScheme else MaestriLightScheme,
            shapes = MaestriShapes,
            content = content,
        )
    }
}
