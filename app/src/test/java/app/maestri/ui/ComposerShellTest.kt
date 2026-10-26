package app.maestri.ui

import androidx.compose.ui.graphics.Color
import app.maestri.ui.Style.Gemini
import app.maestri.ui.screens.composerShellBorder
import app.maestri.ui.screens.composerShellFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The 188 split: the chat pill keeps an opaque surface, the pty pill its own panel. */
class ComposerShellTest {

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun theChatPillKeepsAnOpaqueSurface() {
        val fill = composerShellFill(chat = true)
        assertEquals(Gemini.Surface, fill)
        assertEquals(1f, fill.alpha, 0f)
        assertEquals(Gemini.Outline, composerShellBorder(chat = true))
        assertTrue(composerShellBorder(chat = true).alpha > 0f)
    }

    @Test
    fun theTerminalPillKeepsItsOpaquePanel() {
        val fill = composerShellFill(chat = false)
        assertEquals(Color(0xFF10111A), fill.copy(alpha = 1f))
        assertTrue(fill.alpha > 0.9f)
        assertNotEquals(Gemini.Outline, composerShellBorder(chat = false))
        assertNotEquals(Color.Transparent, composerShellBorder(chat = false))
    }

    @Test
    fun chatTextStillReadsOnTheComposerSurface() {
        // body text and placeholder over the pill the field actually sits on, WCAG AA at 15 sp
        val fill = composerShellFill(chat = true)
        assertTrue(contrast(Gemini.Text, fill) >= 4.5)
        assertTrue(contrast(Gemini.TextMuted, fill) >= 4.5)
    }
}
