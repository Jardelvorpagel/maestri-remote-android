package app.maestri.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Terminal-adjacent palette: near-black surfaces, green primary (cursor green).
val Green = Color(0xFF7BD88F)
val GreenDark = Color(0xFF39C25C)
val Red = Color(0xFFFF5C5C)
val Amber = Color(0xFFFFC24B)
val Surface0 = Color(0xFF101114)
val Surface1 = Color(0xFF17181C)
val Surface2 = Color(0xFF1F2127)

private val DarkScheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF06130A),
    secondary = Color(0xFF9AA3AF),
    onSecondary = Color(0xFF101114),
    tertiary = Amber,
    background = Color(0xFF0A0B0D),
    onBackground = Color(0xFFE6E8EB),
    surface = Surface0,
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Surface1,
    onSurfaceVariant = Color(0xFFB9BEC7),
    error = Red,
    primaryContainer = Surface2,
    onPrimaryContainer = Color(0xFFD8FFDD),
)

private val LightScheme = lightColorScheme(
    primary = GreenDark,
    background = Color(0xFFFAFAF8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun MaestriTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else DarkScheme, // terminal app: dark always
        content = content
    )
}