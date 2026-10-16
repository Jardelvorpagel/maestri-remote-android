package app.maestri.ui.Style

import androidx.compose.ui.graphics.Color

/**
 * Literal style tokens recovered from the Maestri iOS/macOS binaries.
 * Evidence and labels (RECOVERED / INFERRED / UNRECOVERABLE) live in
 * knowledge/sources/2026-10-16-maestri-ios-style-tokens.md.
 */
object Palette {
    // System accents, light-appearance literals (RECOVERED).
    val Blue = Color(0xFF007AFF)
    val Red = Color(0xFFFF3B30)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Teal = Color(0xFF5AC8FA)
    val Yellow = Color(0xFFFFCC00)

    // Dark-appearance equivalents (RECOVERED; the pair ships together in the binary).
    val BlueDark = Color(0xFF0A84FF)
    val RedDark = Color(0xFFFF453A)
    val GreenDark = Color(0xFF32D74B)
    val YellowDark = Color(0xFFFFD60A)
    val PurpleDark = Color(0xFFBF5AF2)
    val TealDark = Color(0xFF64D2FF)

    // Canvas/content chrome (RECOVERED). The brief pins the light chrome at #1A1A1E;
    // the doc reads the pair as the dark canvas surface — light branch kept per the brief.
    val ChromeDark = Color(0xFF0A0B0D)
    val Chrome = Color(0xFF1A1A1E)
    val ChromeElevated = Color(0xFF1D1D1F)

    // Neutral text (INFERRED from the system label set).
    val LabelLight = Color(0xFFE6E8EB)
    val LabelDark = Color(0xFF1C1C1E)
    val LabelSecondaryLight = Color(0xFFB9BEC7)
    val LabelSecondaryDark = Color(0xFF6E6E73)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceLightAlt = Color(0xFFF2F2F7)
    val Gray = Color(0xFF86868B)
    val GrayLightAlt = Color(0xFFE5E5EA)

    // Vendor agent identities (RECOVERED, names adjacent to the literals).
    val Claude = Color(0xFFD97757)
    val Codex = Color(0xFF10A37F)
    val Gemini = Color(0xFF4285F4)

    // Extended picker literals (RECOVERED; consumer INFERRED).
    val DeepRed = Color(0xFFC41A16)
    val DeepGreen = Color(0xFF007400)
    val Olive = Color(0xFF826B28)
    val DeepBlue = Color(0xFF0451A5)
    val Violet = Color(0xFFA831C4)
    val Steel = Color(0xFF0F7B8D)
}

/** Agent identity chips by vendor name (aliases normalised). */
val VendorColors: Map<String, Color> = mapOf(
    "claude" to Palette.Claude,
    "codex" to Palette.Codex,
    "openai" to Palette.Codex,
    "gemini" to Palette.Gemini,
    "google" to Palette.Gemini,
)

/** Vendor colour for a free-form agent/vendor name, or null when unknown. */
fun vendorColor(vendor: String?): Color? =
    vendor?.trim()?.lowercase()?.let { VendorColors[it] }
