package app.maestri.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyLogTest {

    @Test
    fun aLineCarriesInstantAreaActionOutcomeAndDetail() {
        assertEquals(
            "1970-01-01T00:00:00Z update check newer installed=195 published=196",
            journeyLine(0L, "update", "check", "newer", "installed=195 published=196"),
        )
        assertEquals(
            "1970-01-01T00:00:00Z feed createTerminal tap",
            journeyLine(0L, "feed", "createTerminal", "tap", null),
        )
    }

    @Test
    fun aDetailWithLineBreaksStaysOneLine() {
        assertEquals(
            "1970-01-01T00:00:00Z feed createTerminal ok terminalId=a nodeId=b",
            journeyLine(0L, "feed", "createTerminal", "ok", "terminalId=a\nnodeId=b"),
        )
    }

    @Test
    fun aDetailIsCapped() {
        val line = journeyLine(0L, "feed", "createTerminal", "ok", "i".repeat(400))
        assertEquals(160, line.substringAfterLast(' ').length)
    }

    @Test
    fun rotationKeepsTheNewestLinesWithinTheBound() {
        val lines = (1..100).map { "line-$it" }
        assertEquals(lines.takeLast(10), journeyTail(lines, 10))
        assertEquals(lines, journeyTail(lines, 500))
        assertEquals(emptyList<String>(), journeyTail(lines, 0))
    }
}
