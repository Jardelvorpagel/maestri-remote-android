package app.maestri.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalQuickKeysTest {

    @Test
    fun resolvesPiAndCodexWithoutCaseSensitivity() {
        assertEquals(TerminalHarness.PI, TerminalHarness.fromAgentType("PI-AGENT"))
        assertEquals(TerminalHarness.CODEX, TerminalHarness.fromAgentType("Codex"))
        assertEquals(TerminalHarness.GENERIC, TerminalHarness.fromAgentType("other"))
    }

    @Test
    fun codexScrollHintReplacesEscapeWithoutChangingItsByte() {
        val escape = terminalQuickKeys("codex", codexAwayFromBottom = true)
            .first { it.id == QuickChipKeyId.ESCAPE }

        assertEquals("↓ End", escape.label)
        assertEquals("Back to bottom — Escape", escape.description)
        assertArrayEquals(byteArrayOf(0x1B), escape.bytes)
    }

    @Test
    fun piAndCodexAtBottomKeepTheNormalEscapeKey() {
        val piEscape = terminalQuickKeys("pi", codexAwayFromBottom = true)
            .first { it.id == QuickChipKeyId.ESCAPE }
        val codexEscape = terminalQuickKeys("codex", codexAwayFromBottom = false)
            .first { it.id == QuickChipKeyId.ESCAPE }

        assertEquals("esc", piEscape.label)
        assertEquals("esc", codexEscape.label)
        assertFalse(piEscape.description.contains("bottom", ignoreCase = true))
        assertTrue(codexEscape.bytes.contentEquals(byteArrayOf(0x1B)))
    }
}
