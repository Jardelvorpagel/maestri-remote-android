package app.maestri.ui.Style

import androidx.compose.ui.graphics.Color

/**
 * Terminal colour schemes. Two families exist and must not be confused:
 *  - the host-delivered theme family (`maestri-dark` …) that the settings echo confirms and the
 *    terminal screen selects from — the host sends the ANSI tables, so only the family names ship here;
 *  - the built-in schemes whose hex runs were recovered from the binary (Tokyo Night / Daylight).
 */
data class TerminalPalette(
    val name: String,
    val ansi: List<Color>,
    val background: Color? = null,
    val foreground: Color? = null,
)

object TerminalThemes {
    /** Host theme family (CONFIRMED in the settings echo; per-theme ANSI tables are host-delivered). */
    val family: List<String> = listOf(
        "maestri-dark",
        "maestri-light",
        "dracula",
        "catppuccin-mocha",
        "catppuccin-latte",
        "catppuccin-macchiato",
    )

    val base16: List<String> = listOf("base16-default-dark", "base16-default-light")

    /** Recovered hex run, in binary order (magenta, cyan, fg-dim, bg-dim, red, green, yellow, blue). */
    val tokyoNight = TerminalPalette(
        name = "tokyo-night",
        ansi = listOf(
            Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFA9B1D6), Color(0xFF414868),
            Color(0xFFF7768E), Color(0xFF9ECE6A), Color(0xFFE0AF68), Color(0xFF7AA2F7),
        ),
    )

    /** Recovered hex run (red, green, olive, blue, violet, steel). */
    val tokyoDaylight = TerminalPalette(
        name = "tokyo-daylight",
        ansi = listOf(
            Color(0xFFF52A65), Color(0xFF587539), Color(0xFF8C6C3E),
            Color(0xFF2E7DE9), Color(0xFF9854F1), Color(0xFF007197),
        ),
    )

    /** Chrome gray that ships beside the Tokyo runs (RECOVERED). */
    val chromeGray = Color(0xFFE9E9ED)

    /**
     * Tokyo Night chrome literals, verbatim from the design v2 spec (§2). The ANSI table
     * above is the recovered run; these are the surface/ink/ink-accent values the terminal
     * container draws with (bg, border, code bg) plus the prompt blue and the cursor block.
     */
    object TokyoNight {
        val background = Color(0xFF1A1B26)
        val foreground = Color(0xFFA9B1D6)
        val blue = Color(0xFF7AA2F7)
        val green = Color(0xFF9ECE6A)
        val magenta = Color(0xFFBB9AF7)
        val dim = Color(0xFF565F89)
        val border = Color(0xFF23233A)
        val codeBackground = Color(0xFF151518)

        /** Prompt ink: blue at weight 700 (spec §4-03). */
        val prompt = blue

        /** 7 px block cursor cell. */
        val cursor = blue
        val cursorBlock = "\u258A"
    }

    val embedded: List<TerminalPalette> = listOf(tokyoNight, tokyoDaylight)

    /**
     * Preview foreground: the scheme's green when a built-in table exists, else Tokyo Night's.
     * Keeps the terminal preview on a theme-family colour instead of a screen-local literal.
     */
    fun previewForeground(theme: String? = null): Color =
        embeddedPalette(theme)?.ansi?.getOrNull(5) ?: tokyoNight.ansi[5]

    /** Recovered palette for a scheme name, or null for the host-delivered family. */
    fun embeddedPalette(name: String?): TerminalPalette? =
        embedded.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    fun isKnownFamily(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return family.any { it.lowercase() == n } || base16.any { it.lowercase() == n } ||
            embedded.any { it.name == n }
    }
}
