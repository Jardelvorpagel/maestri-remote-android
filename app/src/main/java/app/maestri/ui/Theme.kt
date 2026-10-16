package app.maestri.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import app.maestri.ui.Style.DarkMaestriColors
import app.maestri.ui.Style.LightMaestriColors
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriDarkScheme
import app.maestri.ui.Style.MaestriLightScheme
import app.maestri.ui.Style.MaestriShapes
import app.maestri.ui.Style.Palette

// Blue-first accent, matching the iOS accent pair. The legacy names below are kept only so
// un-migrated screens still compile; new code reads MaterialTheme.colorScheme / LocalMaestriColors.
@Deprecated("Use MaterialTheme.colorScheme.primary or LocalMaestriColors.accent")
val Green = Palette.BlueDark
@Deprecated("Use MaterialTheme.colorScheme.primary (light)")
val GreenDark = Palette.Blue
@Deprecated("Use MaterialTheme.colorScheme.error")
val Red = Palette.RedDark
@Deprecated("Use LocalMaestriColors.warning")
val Amber = Palette.Orange
@Deprecated("Use LocalMaestriColors.canvasChrome")
val Surface0 = Palette.ChromeDark
@Deprecated("Use LocalMaestriColors.canvasChromeElevated")
val Surface1 = Palette.Chrome
@Deprecated("Use LocalMaestriColors.canvasChromeElevated")
val Surface2 = Palette.ChromeElevated

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

/** Kept for callers that still name a transparent surface. */
val Transparent = Color(0x00000000)
