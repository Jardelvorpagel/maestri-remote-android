package app.maestri.ui

/**
 * Workspace/terminal `icon` fields are SF Symbol names (Mac-side) or single emoji.
 * Android can't render SF Symbols, so names are replaced by a neutral glyph; emoji pass.
 */
fun iconGlyph(raw: String?, fallback: String = "▤"): String =
    raw?.takeIf { it.isNotBlank() && it.any { c -> c.code > 0x2200 } && it.length <= 8 } ?: fallback