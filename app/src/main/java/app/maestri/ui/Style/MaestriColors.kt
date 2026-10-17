package app.maestri.ui.Style

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Chrome tokens the Material [ColorScheme] cannot carry (canvas surfaces, hairlines,
 * gradients, bubbles). Read via [LocalMaestriColors]; the value follows system appearance.
 * v2.1: warm-only neutrals on both branches, terracotta accent, ring-depth hairlines.
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
    // ---- v2 additions (defaults keep any un-updated construction valid) ----
    /**
     * Small error TEXT on the active branch: lifted crimson on the dark warm field (5.23:1 on
     * `#141413`), crimson on parchment. Fills/rings keep [danger] wherever they paint a surface.
     */
    val errorText: Color = danger,
    /** Card/list surface on the app background. */
    val card: Color = canvasSurface,
    /** Ring-depth hairline (`box-shadow: 0 0 0 1px`) replacing hard borders. */
    val hairline: Color = Color(0xFF3D3D3A),
    /** Text on the primary accent. */
    val onAccent: Color = Color(0xFFFAF9F5),
    /** Primary CTA gradient pair (135°). */
    val gradientStart: Color = accent,
    val gradientEnd: Color = accent,
    /** Chat bubbles. */
    val bubbleMine: Color = accent,
    val bubbleMineText: Color = Color(0xFFFAF9F5),
    val bubbleAgent: Color = canvasSurface,
    /** Code span / terminal code background. */
    val codeSurface: Color = Color(0xFF151518),
    /** Glass overlay tint for floating bars (translucent by design). */
    val glass: Color = canvasChrome.copy(alpha = 0.92f),
)

val DarkMaestriColors = MaestriColors(
    isLight = false,
    canvasChrome = Palette.NearBlack,
    canvasChromeElevated = Palette.ScreenLift,
    canvasSurface = Palette.DarkSurface,
    accent = Palette.Terracotta,
    accentSoft = Palette.Terracotta.copy(alpha = 0.22f),
    warning = Palette.Warn,
    danger = Palette.Crimson,
    positive = Palette.Success,
    secondaryLabel = Palette.WarmSilver,
    errorText = Palette.ErrorLifted,
    card = Palette.DarkSurface,
    hairline = Palette.DarkWarm,
    onAccent = Palette.Ivory,
    gradientStart = Palette.TerracottaLight,
    gradientEnd = Palette.Terracotta,
    bubbleMine = Palette.Terracotta,
    bubbleMineText = Palette.Ivory,
    bubbleAgent = Palette.DarkSurface,
    codeSurface = Color(0xFF151518),
    glass = Palette.NearBlack.copy(alpha = 0.92f),
)

val LightMaestriColors = MaestriColors(
    isLight = true,
    canvasChrome = Palette.Parchment,
    canvasChromeElevated = Palette.Paper,
    canvasSurface = Color(0xFFFEFDFA),
    accent = Palette.Terracotta,
    accentSoft = Palette.Terracotta.copy(alpha = 0.18f),
    warning = Color(0xFF9A6A00),
    danger = Palette.Crimson,
    positive = Color(0xFF12813C),
    secondaryLabel = Color(0xFF6B6A62),
    errorText = Palette.Crimson,
    card = Color(0xFFFEFDFA),
    hairline = Palette.PaperRing,
    onAccent = Palette.Ivory,
    gradientStart = Palette.TerracottaLight,
    gradientEnd = Palette.Terracotta,
    bubbleMine = Palette.Terracotta,
    bubbleMineText = Palette.Ivory,
    bubbleAgent = Palette.Paper,
    codeSurface = Color(0xFFEFEEE7),
    glass = Palette.Parchment.copy(alpha = 0.94f),
)

val LocalMaestriColors = staticCompositionLocalOf { DarkMaestriColors }

/** Terracotta-first dark scheme on the warm claude surfaces. */
val MaestriDarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.Terracotta,
    onPrimary = Palette.Ivory,
    primaryContainer = Palette.Terracotta.copy(alpha = 0.20f),
    onPrimaryContainer = Palette.Ivory,
    secondary = Palette.WarmSilver,
    onSecondary = Palette.NearBlack,
    tertiary = Palette.TerracottaLight,
    background = Palette.NearBlack,
    onBackground = Palette.Ivory,
    surface = Palette.NearBlack,
    onSurface = Palette.Ivory,
    surfaceVariant = Palette.DarkSurface,
    onSurfaceVariant = Palette.WarmSilver,
    error = Palette.ErrorLifted,
    onError = Palette.NearBlack,
    outline = Palette.DarkWarm,
    outlineVariant = Palette.DarkWarm,
)

/** Parchment light scheme — the same warm family, paper side. */
val MaestriLightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Terracotta,
    onPrimary = Palette.Ivory,
    primaryContainer = Palette.Terracotta.copy(alpha = 0.16f),
    onPrimaryContainer = Palette.NearBlack,
    secondary = Color(0xFF6B6A62),
    onSecondary = Palette.Ivory,
    tertiary = Palette.Terracotta,
    background = Palette.Parchment,
    onBackground = Palette.NearBlack,
    surface = Color(0xFFFEFDFA),
    onSurface = Palette.NearBlack,
    surfaceVariant = Palette.Paper,
    onSurfaceVariant = Color(0xFF6B6A62),
    error = Palette.Crimson,
    onError = Palette.Ivory,
    outline = Palette.PaperRing,
    outlineVariant = Palette.PaperRing,
)
