package app.maestri.ui.Style

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Chrome tokens the Material [ColorScheme] cannot carry (canvas surface, vendor chips).
 * Read via [LocalMaestriColors]; the active value follows the system appearance.
 */
@Immutable
data class MaestriColors(
    val isLight: Boolean,
    val canvasChrome: Color,
    val canvasChromeElevated: Color,
    val canvasSurface: Color,
    val accent: Color,
    val accentSoft: Color,
    val warning: Color,
    val danger: Color,
    val positive: Color,
    val secondaryLabel: Color,
)

val DarkMaestriColors = MaestriColors(
    isLight = false,
    canvasChrome = Palette.ChromeDark,
    canvasChromeElevated = Palette.ChromeElevated,
    canvasSurface = Palette.Chrome,
    accent = Palette.BlueDark,
    accentSoft = Palette.BlueDark.copy(alpha = 0.22f),
    warning = Palette.Orange,
    danger = Palette.RedDark,
    positive = Palette.GreenDark,
    secondaryLabel = Palette.LabelSecondaryLight,
)

val LightMaestriColors = MaestriColors(
    isLight = true,
    canvasChrome = Palette.Chrome,
    canvasChromeElevated = Palette.ChromeElevated,
    canvasSurface = Palette.Chrome,
    accent = Palette.Blue,
    accentSoft = Palette.Blue.copy(alpha = 0.18f),
    warning = Palette.Orange,
    danger = Palette.Red,
    positive = Palette.Green,
    secondaryLabel = Palette.LabelSecondaryDark,
)

val LocalMaestriColors = staticCompositionLocalOf { DarkMaestriColors }

/** Blue-first dark scheme: iOS dark accent on the recovered chrome surfaces. */
val MaestriDarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.BlueDark,
    onPrimary = Color(0xFF00182E),
    primaryContainer = Palette.ChromeElevated,
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Palette.LabelSecondaryLight,
    onSecondary = Palette.ChromeDark,
    tertiary = Palette.TealDark,
    background = Palette.ChromeDark,
    onBackground = Palette.LabelLight,
    surface = Palette.Chrome,
    onSurface = Palette.LabelLight,
    surfaceVariant = Palette.ChromeElevated,
    onSurfaceVariant = Palette.LabelSecondaryLight,
    error = Palette.RedDark,
    onError = Color(0xFF2B0004),
)

/** Blue-first light scheme: the light half of every shipped accent pair. */
val MaestriLightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF002B55),
    secondary = Palette.LabelSecondaryDark,
    onSecondary = Color.White,
    tertiary = Palette.Steel,
    background = Palette.SurfaceLightAlt,
    onBackground = Palette.LabelDark,
    surface = Palette.SurfaceLight,
    onSurface = Palette.LabelDark,
    surfaceVariant = Palette.GrayLightAlt,
    onSurfaceVariant = Palette.LabelSecondaryDark,
    error = Palette.Red,
    onError = Color.White,
)
