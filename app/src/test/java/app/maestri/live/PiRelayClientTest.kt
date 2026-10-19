package app.maestri.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRelayClientTest {

    private fun event(seq: Long, kind: String = "assistant_message"): PiEvent =
        PiEvent(1, seq, "s", "e$seq", "ts", kind, null, "t$seq", null)

    @Test
    fun fullEnvelopeParsesWithToolBlock() {
        val json = """{"v":1,"seq":7,"sessionId":"s1","entryId":"e1","ts":"2026-10-18T00:00:00Z",""" +
            """"kind":"tool_call","role":"assistant","text":null,"mystery":"ignored","tool":{""" +
            """"name":"bash","callId":"c1","status":"running","summary":"ls","argsPreview":"-la","resultPreview":null,"extra":1}}"""
        val frame = PiRelayParser.parseFrame(json) as RelayFrame.Event
        assertEquals(7L, frame.event.seq)
        assertEquals("tool_call", frame.event.kind)
        assertEquals("s1", frame.event.sessionId)
        assertEquals("bash", frame.event.tool?.name)
        assertEquals("running", frame.event.tool?.status)
    }

    @Test
    fun unknownKindSurfacesWithKindPreserved() {
        val json = """{"seq":3,"sessionId":"s","entryId":"e","ts":"t","kind":"brain_melt","text":"hi"}"""
        val frame = PiRelayParser.parseFrame(json) as RelayFrame.Event
        assertEquals("brain_melt", frame.event.kind)
        assertEquals("hi", frame.event.text)
    }

    @Test
    fun unknownFieldsIgnored() {
        val json = """{"seq":1,"kind":"notice","future":{"deep":[1,2,{"x":true}]},"text":"n"}"""
        val frame = PiRelayParser.parseFrame(json) as RelayFrame.Event
        assertEquals("notice", frame.event.kind)
        assertEquals("n", frame.event.text)
    }

    @Test
    fun malformedInputYieldsNullWithoutThrowing() {
        listOf("", "   ", "{", """{"seq":", "not json", "[1,2", """, "{\"kind\":\"ping\"", "[1,2,3]", "42", "null").forEach {
            assertNull("input: $it", PiRelayParser.parseFrame(it))
        }
    }

    @Test
    fun eventWithoutSeqIsDropped() {
        assertNull(PiRelayParser.parseFrame("""{"kind":"notice","text":"no seq"}"""))
    }

    @Test
    fun frameWithoutKindIsDropped() {
        assertNull(PiRelayParser.parseFrame("""{"seq":9,"text":"no kind"}"""))
    }

    @Test
    fun helloParses() {
        val frame = PiRelayParser.parseFrame(
            """{"v":1,"kind":"hello","sessionId":"abc","replayFrom":10,"replayTo":200}""",
        ) as RelayFrame.Hello
        assertEquals("abc", frame.sessionId)
        assertEquals(10L, frame.replayFrom)
        assertEquals(200L, frame.replayTo)
    }

    @Test
    fun pingPongResumeGapParse() {
        assertTrue(PiRelayParser.parseFrame("""{"kind":"ping"}""") is RelayFrame.Ping)
        assertTrue(PiRelayParser.parseFrame("""{"kind":"pong","ts":"x"}""") is RelayFrame.Pong)
        val gap = PiRelayParser.parseFrame("""{"kind":"resume_gap","oldestSeq":150}""") as RelayFrame.ResumeGap
        assertEquals(150L, gap.oldestSeq)
    }

    @Test
    fun serverSideResumeKindIgnored() {
        assertNull(PiRelayParser.parseFrame("""{"kind":"resume","afterSeq":5}"""))
    }

    @Test
    fun controlKindsNeverParseAsEvents() {
        assertNull(PiRelayParser.parseEvent("""{"kind":"hello","sessionId":"s"}"""))
        assertNull(PiRelayParser.parseEvent("""{"kind":"ping"}"""))
    }

    @Test
    fun outOfOrderInsertsSortBySeq() {
        var list = emptyList<PiEvent>()
        list = insertRelayEvent(list, event(3))
        list = insertRelayEvent(list, event(1))
        list = insertRelayEvent(list, event(2))
        assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
    }

    @Test
    fun duplicateSeqDedups() {
        var list = insertRelayEvent(emptyList(), event(1))
        list = insertRelayEvent(list, event(1, "notice"))
        assertEquals(1, list.size)
        assertEquals("assistant_message", list.single().kind)
    }

    @Test
    fun bufferBoundsToLast2000() {
        var list = emptyList<PiEvent>()
        for (seq in 1L..2005L) list = insertRelayEvent(list, event(seq))
        assertEquals(2000, list.size)
        assertEquals(6L, list.first().seq)
        assertEquals(2005L, list.last().seq)
    }

    @Test
    fun previewsTruncateAt500() {
        val big = "x".repeat(600)
        val json = """{"seq":1,"kind":"tool_result","tool":{"name":"bash","resultPreview":"$big"}}"""
        val frame = PiRelayParser.parseFrame(json) as RelayFrame.Event
        val tool = frame.event.tool ?: throw AssertionError("tool null")
        assertEquals(500, tool.resultPreview.length)
    }

    @Test
    fun absentToolFieldsDefaultEmpty() {
        val frame = PiRelayParser.parseFrame(
            """{"seq":1,"kind":"tool_call","tool":{"name":"bash"}}""",
        ) as RelayFrame.Event
        val tool = frame.event.tool ?: throw AssertionError("tool null")
        assertEquals("", tool.callId)
        assertEquals("", tool.status)
        assertEquals("", tool.argsPreview)
    }

    @Test
    fun numericFormsTolerated() {
        val fromString = PiRelayParser.parseFrame("""{"seq":"42","kind":"notice"}""") as RelayFrame.Event
        assertEquals(42L, fromString.event.seq)
        val fromDouble = PiRelayParser.parseFrame("""{"seq":43.0,"kind":"notice"}""") as RelayFrame.Event
        assertEquals(43L, fromDouble.event.seq)
    }

    @Test
    fun backoffProgressesAndCaps() {
        assertEquals(1_000L, backoffBaseMs(0))
        assertEquals(2_000L, backoffBaseMs(1))
        assertEquals(4_000L, backoffBaseMs(2))
        assertEquals(16_000L, backoffBaseMs(4))
        assertEquals(30_000L, backoffBaseMs(5))
        assertEquals(30_000L, backoffBaseMs(99))
    }

    @Test
    fun tokenQueryUsesRightSeparator() {
        assertEquals("ws://h:1/stream?token=abc", withTokenQuery("ws://h:1/stream", "abc"))
        assertEquals("ws://h:1/s?x=1&token=abc", withTokenQuery("ws://h:1/s?x=1", "abc"))
    }

    @Test
    fun clientFramesWellFormed() {
        assertEquals("""{"kind":"ping"}""", pingFrame())
        assertEquals("""{"kind":"pong"}""", pongFrame())
        assertEquals("""{"kind":"resume","afterSeq":12}""", resumeFrame(12))
    }

    @Test
    fun httpUrlsNormalizeToWs() {
        assertEquals("ws://h:1/stream", normalizeRelayUrl("http://h:1/stream"))
        assertEquals("wss://h:1/stream", normalizeRelayUrl("https://h:1/stream"))
        assertEquals("ws://h:1/stream", normalizeRelayUrl("  ws://h:1/stream  "))
    }

    @Test
    fun blankUrlStaysDisconnectedAndBlankTokenIsConfigured() {
        val noUrl = PiRelayClient("", "tok")
        assertFalse(noUrl.configured)
        noUrl.connect()
        assertTrue(noUrl.state.value is RelayState.Disconnected)
        noUrl.disconnect()

        val noToken = PiRelayClient("ws://h:1/stream", "")
        assertTrue(noToken.configured)
        noToken.disconnect()
    }

    @Test
    fun blankTokenSendsNoAuthHeader() {
        val request = buildRelayRequest("ws://h:1/stream", "", false)
        assertNull(request.header("Authorization"))
        assertEquals("http://h:1/stream", request.url.toString())
    }

    @Test
    fun tokenPresentSendsBearer() {
        val direct = buildRelayRequest("ws://h:1/stream", "tok", false)
        assertEquals("Bearer tok", direct.header("Authorization"))
        assertEquals("http://h:1/stream", direct.url.toString())

        val fallback = buildRelayRequest("ws://h:1/stream", "tok", true)
        assertEquals("Bearer tok", fallback.header("Authorization"))
        assertEquals("http://h:1/stream?token=tok", fallback.url.toString())
    }

    @Test
    fun authFailureCopyFitsBothModes() {
        assertEquals(
            "This relay requires a token (HTTP 401).",
            relayAuthFailureMessage(true),
        )
        assertEquals(
            "Relay rejected the token (HTTP 401).",
            relayAuthFailureMessage(false),
        )
    }
}
