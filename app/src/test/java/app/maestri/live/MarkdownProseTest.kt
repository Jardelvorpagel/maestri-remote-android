package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownProseTest {

    private fun spansOf(text: String): List<Pair<String, Set<MdStyle>>> =
        parseInline(text).map { it.text to it.styles }

    @Test
    fun boldItalicCodeAndStrikeBecomeRuns() {
        val spans = spansOf("a **bold** b *it* c `code` d ~~gone~~ e")
        assertEquals(
            listOf(
                "a " to emptySet<MdStyle>(),
                "bold" to setOf(MdStyle.BOLD),
                " b " to emptySet<MdStyle>(),
                "it" to setOf(MdStyle.ITALIC),
                " c " to emptySet<MdStyle>(),
                "code" to setOf(MdStyle.CODE),
                " d " to emptySet<MdStyle>(),
                "gone" to setOf(MdStyle.STRIKE),
                " e" to emptySet<MdStyle>(),
            ),
            spans,
        )
    }

    @Test
    fun nestedEmphasisKeepsBothStyles() {
        val spans = spansOf("*outer **both** outer*")
        assertEquals("outer ", spans[0].first)
        assertEquals(setOf(MdStyle.ITALIC), spans[0].second)
        assertEquals("both" to setOf(MdStyle.BOLD, MdStyle.ITALIC), spans[1])
    }

    @Test
    fun codeSpanIsVerbatim() {
        val spans = spansOf("run `a **b** c` now")
        assertEquals("a **b** c" to setOf(MdStyle.CODE), spans[1])
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        assertEquals(listOf("2 * 3 = 6" to emptySet<MdStyle>()), spansOf("2 * 3 = 6"))
        assertEquals(listOf("**open" to emptySet<MdStyle>()), spansOf("**open"))
        assertEquals(listOf("`tick" to emptySet<MdStyle>()), spansOf("`tick"))
    }

    @Test
    fun headingsUseTheirHashLevel() {
        val blocks = parseMarkdownBlocks("# One\n\n## Two\n\n### Three")
        assertEquals(listOf(1, 2, 3), blocks.map { (it as MdBlock.Heading).level })
        assertEquals(listOf("One", "Two", "Three"), blocks.map { (it as MdBlock.Heading).text })
    }

    @Test
    fun unorderedAndOrderedListsGroupPerKind() {
        val blocks = parseMarkdownBlocks(
            """
            - one
            - two
            * three

            1. first
            2. second
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        val bullets = blocks[0] as MdBlock.Bullets
        assertEquals(false, bullets.ordered)
        assertEquals(listOf("one", "two", "three"), bullets.items)
        val ordered = blocks[1] as MdBlock.Bullets
        assertTrue(ordered.ordered)
        assertEquals(listOf("first", "second"), ordered.items)
    }

    @Test
    fun fencedCodeBlockKeepsItsBodyVerbatim() {
        val blocks = parseMarkdownBlocks("before\n\n```kotlin\nval a = 1\nval b = 2\n```\n\nafter")
        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("val a = 1\nval b = 2", (blocks[1] as MdBlock.Code).code)
        assertEquals("after", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun streamingPartialFenceDegradesToLiteralText() {
        val blocks = parseMarkdownBlocks("text\n\n```kotlin\nval a = 1")
        assertEquals(2, blocks.size)
        assertEquals("```kotlin\nval a = 1", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun quoteAndBlankLineParagraphs() {
        val blocks = parseMarkdownBlocks("para one line one\nline two\n\n> quoted\n> more\n\nlast")
        assertEquals("para one line one\nline two", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("quoted\nmore", (blocks[1] as MdBlock.Quote).text)
        assertEquals("last", (blocks[2] as MdBlock.Paragraph).text)
    }

    @Test
    fun plainTextStaysOneParagraph() {
        assertEquals(listOf(MdBlock.Paragraph("just words")), parseMarkdownBlocks("just words"))
        assertTrue(parseMarkdownBlocks("").isEmpty())
    }
}
