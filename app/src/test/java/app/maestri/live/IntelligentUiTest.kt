package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligentUiTest {

    private fun event(seq: Long, kind: String, text: String = "", tool: String = ""): PiEvent =
        PiEvent(
            v = 1,
            seq = seq,
            sessionId = "s",
            entryId = "e$seq",
            ts = "2026-10-25T12:00:00Z",
            kind = kind,
            role = null,
            text = text,
            tool = if (tool.isBlank()) null else ToolInfo(name = tool, callId = "c$seq"),
        )

    private val uiJson = """
        {"title":"Sprint 42","subtitle":"3 of 5 done","metrics":[
          {"label":"Coverage","value":"78%","ratio":0.78},
          {"label":"Tests","value":"412"}],
         "steps":[{"label":"Parse spec","done":true},{"label":"Render card","done":false}],
         "actions":[{"label":"Ship it","prompt":"ship the release"},{"label":"Detail"}]}
    """.trimIndent()

    @Test
    fun mixedProseNativeCardAndHtmlWidgetParseInOrder() {
        val text = buildString {
            appendLine("Plan for this week:")
            appendLine("```ui")
            appendLine(uiJson)
            appendLine("```")
            appendLine("Preview below.")
            appendLine("```ui-html Dashboard <!-- note -->")
            appendLine("<!-- title: Build health -->")
            appendLine("<button id=\"go\">Go</button>")
            appendLine("```")
        }
        val segments = parseIntelligentSegments(text)

        assertEquals(4, segments.size)
        assertEquals("Plan for this week:", (segments[0] as UiSegment.Prose).text)

        val card = (segments[1] as UiSegment.NativeCard).spec
        assertEquals("Sprint 42", card.title)
        assertEquals("3 of 5 done", card.subtitle)
        assertEquals(2, card.metrics.size)
        assertEquals("78%", card.metrics[0].value)
        assertEquals(0.78, card.metrics[0].ratio!!.toDouble(), 0.0001)
        assertEquals(null, card.metrics[1].ratio)
        assertEquals(listOf(true, false), card.steps.map { it.done })
        assertEquals("ship the release", card.actions[0].prompt)
        assertEquals("Detail", card.actions[1].prompt)

        assertEquals("Preview below.", (segments[2] as UiSegment.Prose).text)

        val widget = segments[3] as UiSegment.HtmlWidget
        assertEquals("Dashboard", widget.title)
        assertTrue(widget.html.contains("<button id=\"go\">Go</button>"))
        assertTrue(!widget.html.contains("title:"))
    }

    @Test
    fun htmlTitleFallsBackToFirstComment() {
        val segments = parseIntelligentSegments(
            "```ui-html\n<!-- title: Timeline -->\n<svg></svg>\n```",
        )
        val widget = segments.single() as UiSegment.HtmlWidget
        assertEquals("Timeline", widget.title)
        assertTrue(widget.html.trim().startsWith("<svg>"))
    }

    @Test
    fun malformedJsonDegradesToProse() {
        val segments = parseIntelligentSegments("```ui\n{not json}\n```")
        assertEquals(1, segments.size)
        assertTrue(segments.single() is UiSegment.Prose)
    }

    @Test
    fun emptyOrUnclosedFenceDegradesToProse() {
        assertTrue(parseIntelligentSegments("```ui\n").single() is UiSegment.Prose)
        assertTrue(parseIntelligentSegments("```ui\n{}").single() is UiSegment.Prose)
        assertTrue(parseIntelligentSegments("just words").single() is UiSegment.Prose)
    }

    @Test
    fun ratioClampsAndBlankProseIsDropped() {
        val segments = parseIntelligentSegments(
            "```ui\n{\"metrics\":[{\"label\":\"Load\",\"value\":\"9\",\"ratio\":4.2}]}\n```",
        )
        val card = (segments.single() as UiSegment.NativeCard).spec
        assertEquals(1.0, card.metrics.single().ratio!!.toDouble(), 0.0001)
    }

    @Test
    fun singleCardOrWidgetIsNotProseOnly() {
        val cardOnly = parseIntelligentSegments("```ui\n{\"title\":\"Only a card\"}\n```")
        assertTrue(cardOnly.single() is UiSegment.NativeCard)
        assertFalse(isPlainProseOnly(cardOnly))

        val widgetOnly = parseIntelligentSegments("```ui-html\n<svg></svg>\n```")
        assertTrue(widgetOnly.single() is UiSegment.HtmlWidget)
        assertFalse(isPlainProseOnly(widgetOnly))

        assertTrue(isPlainProseOnly(parseIntelligentSegments("plain words")))
        assertTrue(isPlainProseOnly(emptyList()))
    }

    @Test
    fun fourConsecutiveToolCallsCollapseIntoOneGroup() {
        val rows = (1L..4L).map { DisplayRow.Tool(event(it, LiveKinds.TOOL_CALL, tool = "bash"), null) }
        val items = groupFeedRows(rows)

        val group = items.single() as FeedRenderItem.ToolGroup
        assertEquals(4, group.count)
        assertEquals("bash ×4", group.summary)
        assertEquals(ToolGroupState.RUNNING, group.state)
    }

    @Test
    fun interleavedMessageSplitsGroupsAndSingleCallStaysOneRow() {
        val message = DisplayRow.Message(event(2, LiveKinds.ASSISTANT_MESSAGE, "a"))
        val ok = DisplayRow.Tool(
            event(3, LiveKinds.TOOL_CALL, tool = "bash"),
            event(4, LiveKinds.TOOL_RESULT).copy(tool = ToolInfo(name = "bash", status = "ok")),
        )
        val errored = DisplayRow.Tool(
            event(5, LiveKinds.TOOL_CALL, tool = "read"),
            event(6, LiveKinds.TOOL_RESULT).copy(tool = ToolInfo(name = "read", status = "error")),
        )
        val items = groupFeedRows(
            listOf(
                DisplayRow.Tool(event(1, LiveKinds.TOOL_CALL, tool = "bash"), null),
                message,
                ok,
                errored,
            ),
        )

        assertEquals(3, items.size)
        assertTrue(items[0] is FeedRenderItem.ToolGroup)
        assertTrue(items[1] is FeedRenderItem.Single)
        val mixed = items[2] as FeedRenderItem.ToolGroup
        assertEquals(2, mixed.count)
        assertEquals("bash, read ×2", mixed.summary)
        assertEquals(ToolGroupState.ERROR, mixed.state)
    }

    @Test
    fun allOkRunReportsOk() {
        val run = listOf(
            DisplayRow.Tool(
                event(1, LiveKinds.TOOL_CALL, tool = "bash"),
                event(2, LiveKinds.TOOL_RESULT).copy(tool = ToolInfo(name = "bash", status = "ok")),
            ),
        )
        assertEquals(ToolGroupState.OK, toolGroupState(run))
    }
}
