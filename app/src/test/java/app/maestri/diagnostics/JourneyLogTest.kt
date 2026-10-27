package app.maestri.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JourneyLogTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test
    fun aLogThatPassesItsCapsRotatesAndKeepsTheNewestLine() {
        val file = File(temp.root, "journey.log")
        val log = JourneyLog(file, maxBytes = 512L, keepLines = 400)
        repeat(40) { log.record("feed", "createTerminal", "ok", "n=$it " + "i".repeat(100)) }
        log.read()

        assertTrue("file length ${file.length()}", file.length() <= 512L)
        val text = file.readText()
        assertTrue(text.contains("n=39"))
        assertFalse(text.contains("n=0 "))
    }
}
