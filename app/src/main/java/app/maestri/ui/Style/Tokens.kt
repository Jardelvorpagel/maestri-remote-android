package app.maestri.ui.Style

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color

/**
 * Design v2.1-modern claude literals (warm-only neutrals, terracotta chrome accent).
 *
 * Token values follow the v2.1-modern artifact (:root, 2026-10-17 — byte-identical to the
 * Oct-16 v2.0 artifact): ambient #161614, cards #2B2A26, raised #1C1B18, sunken #232220,
 * ink #141413 / ink-2 #3D3D3A on the paper branch, radii 12/18/24, rhythm 4/8/12/16/20.
 * The spec doc's #141413/#30302E base predates the artifact family and is superseded.
 *
 * Two families live here on purpose:
 *  - [Palette] — the v2 warm set, used by the live schemes;
 *  - the deprecated iOS-parity aliases below — kept so no call site breaks while the
 *    screens migrate; they resolve to warm equivalents per the v2 deviation log
 *    (#0A84FF demoted, cool grays dropped).
 * Evidence: knowledge/sources/2026-10-16-maestri-design-v2-spec.md §2/§8.
 */
object Palette {
    // claude dark branch (v2.1-modern ambient family).
    val Ambient = Color(0xFF161614)         // --bg: warm near-black app background
    val NearBlack = Color(0xFF141413)       // --ink: text on parchment; legacy dark bg literal
    val AmbientRaised = Color(0xFF1C1B18)   // --bg-2: raised ambient (chips, wells, 2nd buttons)
    val DarkSurface = Color(0xFF2B2A26)     // --card: cards, spine, list groups
    val ScreenLift = Color(0xFF1C1B18)      // pushed-screen lift — now equals --bg-2
    val SunkenSurface = Color(0xFF232220)   // --card-2: wells, segmented tracks
    val GlassBase = Color(0xFF181715)       // --glass tint rgb(24,23,21)
    val Ink = Color(0xFF141413)             // --ink (paper branch)
    val InkSecondary = Color(0xFF3D3D3A)    // --ink-2 (paper branch secondary)
    val Ivory = Color(0xFFFAF9F5)          // primary text on dark
    val WarmSilver = Color(0xFFB0AEA5)     // secondary text + ring-depth alpha base
    val StoneGray = Color(0xFF87867F)      // metadata, overlines, mono times
    val DarkWarm = Color(0xFF3D3D3A)       // legacy ring-track gray (value = --ink-2)

    // claude light branch (parchment).
    val Parchment = Color(0xFFF5F4ED)
    val Paper = Color(0xFFE8E6DC)
    val PaperRing = Color(0xFFD1CFC5)

    // chrome accent (CTA, active tab, chevrons, pill-active) + its gradient pair.
    val Terracotta = Color(0xFFC96442)
    val TerracottaLight = Color(0xFFD97B54)

    // claude semantics.
    val Success = Color(0xFF17A34A)
    val Warn = Color(0xFFEAB308)
    val Crimson = Color(0xFFB53333)

    /**
     * Lifted crimson for SMALL error text on the dark warm field (spec §2 fix, wave 3 residual 2).
     * `#B53333` measures **3.06:1** on `#141413` — under the 4.5:1 text floor; this lifts it to
     * **5.23:1** on `#141413` while keeping the crimson hue. Fills/rings keep [Crimson]
     * (`#B53333`, 5.46:1 on the parchment branch). Higher-contrast alternative considered:
     * `#FF6B5E` (6.60:1) — rejected as too bright a salmon for the warm family.
     */
    val ErrorLifted = Color(0xFFE0604F)

    /** Accessibility focus rings only; also the literal named in the Settings row. */
    val FocusBlue = Color(0xFF3898EC)

    /**
     * Terminal composer send accent (design 03 `--gemini #4285F4`). Same literal as [Gemini]
     * but a chrome role, so it carries its own name instead of overloading the vendor token.
     */
    val ComposerAccent = Color(0xFF4285F4)

    // Vendor agent identities (data roles — dots/arcs/rows only, never chrome).
    val Claude = Color(0xFFD97757)
    val Codex = Color(0xFF10A37F)
    val Gemini = Color(0xFF4285F4)

    // ---------------------------------------------------------------- deprecated (v1 iOS)
    // Kept for the migrating screens; each maps onto the warm ramp above.
    @Deprecated("v2 warm ramp", ReplaceWith("NearBlack"))
    val ChromeDark = NearBlack
    @Deprecated("v2 warm ramp", ReplaceWith("ScreenLift"))
    val Chrome = ScreenLift
    @Deprecated("v2 warm ramp", ReplaceWith("DarkSurface"))
    val ChromeElevated = DarkSurface
    @Deprecated("v2 warm ramp", ReplaceWith("Ivory"))
    val LabelLight = Ivory
    @Deprecated("v2 warm ramp", ReplaceWith("WarmSilver"))
    val LabelSecondaryLight = WarmSilver
    @Deprecated("v2 warm ramp", ReplaceWith("StoneGray"))
    val LabelSecondaryDark = StoneGray
    @Deprecated("v2 warm ramp", ReplaceWith("Parchment"))
    val SurfaceLightAlt = Parchment
    @Deprecated("v2 warm ramp", ReplaceWith("NearBlack"))
    val LabelDark = NearBlack
    @Deprecated("v2 warm ramp", ReplaceWith("Paper"))
    val GrayLightAlt = Paper
    @Deprecated("v2 accent", ReplaceWith("Terracotta"))
    val Blue = Terracotta
    @Deprecated("v2 accent", ReplaceWith("Terracotta"))
    val BlueDark = Terracotta
    @Deprecated("v2 accent", ReplaceWith("TerracottaLight"))
    val TealDark = TerracottaLight
    @Deprecated("v2 accent", ReplaceWith("Terracotta"))
    val Steel = Terracotta
    @Deprecated("v2 accent", ReplaceWith("Terracotta"))
    val Purple = TerracottaLight
    @Deprecated("v2 accent", ReplaceWith("TerracottaLight"))
    val PurpleDark = TerracottaLight
    @Deprecated("v2 accent", ReplaceWith("Terracotta"))
    val Pink = TerracottaLight
    @Deprecated("v2 semantics", ReplaceWith("Success"))
    val Green = Success
    @Deprecated("v2 semantics", ReplaceWith("Success"))
    val GreenDark = Success
    @Deprecated("v2 semantics", ReplaceWith("Warn"))
    val Orange = Warn
    @Deprecated("v2 semantics", ReplaceWith("Warn"))
    val Yellow = Warn
    @Deprecated("v2 semantics", ReplaceWith("Warn"))
    val YellowDark = Warn
    @Deprecated("v2 semantics", ReplaceWith("Crimson"))
    val Red = Crimson
    @Deprecated("v2 semantics", ReplaceWith("Crimson"))
    val RedDark = Crimson
}

/** Motion tokens (spec §4: 150 ms press/state, 240 ms transition, one easing). */
object Motion {
    const val Fast = 150
    const val Slow = 240
    val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** Press scale for bubbles/pills/cards. */
    const val PressScale = 0.97f
}

/** The 4/8/12/16/20 rhythm (claude scale, phone gutter overridden to 16). */
object Rhythm {
    val Fine = 4
    val Gap = 8
    val Mid = 12
    val Gutter = 16
    val Section = 20
    val CardPadding = 20
    /** Spine: 49 nav + 34 gesture zone. */
    val SpineHeight = 83
    val TouchTarget = 44
}

/** Terracotta gradient for primary CTAs (135° = top-left → bottom-right). */
fun terracottaGradient(dark: Boolean): List<Color> =
    if (dark) listOf(Palette.TerracottaLight, Palette.Terracotta)
    else listOf(Palette.TerracottaLight, Palette.Terracotta)

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
