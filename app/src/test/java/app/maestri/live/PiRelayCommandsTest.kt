package app.maestri.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRelayCommandsTest {

    private fun parsed(json: String): Map<*, *> =
        MiniJson.parse(json) as? Map<*, *> ?: throw AssertionError("unparseable: $json")

    // ------------------------------------------------------- framing

    @Test
    fun sendMessageFrameShape() {
        val fields = parsed(sendMessageFrame("hi", listOf("a1", "a2"), "m1"))
        assertEquals("send_message", fields["kind"])
        assertEquals("hi", fields["text"])
        assertEquals("m1", fields["clientMsgId"])
        assertEquals(listOf("a1", "a2"), fields["attachmentIds"])
    }

    @Test
    fun sendMessageFrameEscapes() {
        val fields = parsed(sendMessageFrame("say \"hi\"\nbye", emptyList(), "m2"))
        assertEquals("say \"hi\"\nbye", fields["text"])
        assertEquals(emptyList<Any>(), fields["attachmentIds"])
    }

    @Test
    fun toolDecisionFrameShape() {
        val fields = parsed(toolDecisionFrame("c9", "deny", "always"))
        assertEquals("tool_decision", fields["kind"])
        assertEquals("c9", fields["callId"])
        assertEquals("deny", fields["decision"])
        assertEquals("always", fields["remember"])
    }

    // ------------------------------------------------------- acks

    @Test
    fun ackAcceptedAndRejected() {
        val ok = parseSendAck(parsed("""{"kind":"send_ack","clientMsgId":"m1","ok":true}"""))!!
        assertTrue(ok.accepted)

        val bare = parseSendAck(parsed("""{"kind":"send_ack","clientMsgId":"m2"}"""))!!
        assertTrue(bare.accepted)

        val err = parseSendAck(
            parsed("""{"kind":"send_ack","clientMsgId":"m3","error":"too long"}"""),
        )!!
        assertFalse(err.accepted)
        assertEquals("too long", err.detail)

        val denied = parseSendAck(
            parsed("""{"kind":"send_ack","clientMsgId":"m4","accepted":false,"status":"rejected"}"""),
        )!!
        assertFalse(denied.accepted)
    }

    @Test
    fun ackWithoutIdIgnored() {
        assertNull(parseSendAck(parsed("""{"kind":"send_ack","ok":true}""")))
        assertNull(parseSendAck(parsed("""{"kind":"send_ack","clientMsgId":""}""")))
    }

    @Test
    fun ackRoutesByClientMsgId() {
        val start = mapOf("m1" to SendOutcome("m1", true, ""))
        val next = applySendAck(start, SendAck("m2", false, "nope"))
        assertEquals(2, next.size)
        assertFalse(next.getValue("m2").accepted)
        assertEquals("nope", next.getValue("m2").detail)
        assertTrue(next.getValue("m1").accepted)
    }

    // ------------------------------------------------------- approvals

    @Test
    fun approvalRequestParses() {
        val full = parseApproval(
            parsed(
                """{"kind":"approval_request","callId":"c1","tool":{"name":"bash"},"summary":"run it","argsPreview":"ls","status":"requested"}""",
            ),
        )!!
        assertEquals("bash", full.tool)
        assertEquals("requested", full.status)

        val flat = parseApproval(parsed("""{"kind":"approval_request","callId":"c2","tool":"bash"}"""))!!
        assertEquals("bash", flat.tool)
        assertEquals("", flat.summary)
        assertEquals("", flat.reason)

        val withReason = parseApproval(
            parsed("""{"kind":"approval_request","callId":"c3","reason":"needs network"}"""),
        )!!
        assertEquals("needs network", withReason.reason)

        assertNull(parseApproval(parsed("""{"kind":"approval_request","tool":"bash"}""")))
    }

    @Test
    fun approvalResolvedParses() {
        val resolved = parseApproval(
            parsed("""{"kind":"approval_resolved","callId":"c1","decision":"deny","status":"resolved"}"""),
        )!!
        assertEquals("deny", resolved.decision)
        assertEquals("resolved", resolved.status)
    }

    // ------------------------------------------------------- attachments

    @Test
    fun attachmentReadyParses() {
        val num = parseAttachment(
            parsed("""{"kind":"attachment_ready","id":"a1","name":"f.png","bytes":41,"path":"/t/f.png"}"""),
        )!!
        assertEquals(41L, num.bytes)

        val str = parseAttachment(parsed("""{"kind":"attachment_ready","id":"a2","bytes":"7"}"""))!!
        assertEquals(7L, str.bytes)
        assertEquals("", str.name)

        assertNull(parseAttachment(parsed("""{"kind":"attachment_ready","name":"x"}""")))
    }

    // ------------------------------------------------------- tolerance

    @Test
    fun unknownKindsNeverCrash() {
        assertNull(PiRelayParser.parseFrame("""{"kind":"future_kind","x":1}"""))
        val surfaced = PiRelayParser.parseFrame(
            """{"kind":"future_kind","seq":9,"text":"kept"}""",
        ) as RelayFrame.Event
        assertEquals("future_kind", surfaced.event.kind)
    }

    @Test
    fun helloCommandsFlagTolerant() {
        val yes = PiRelayParser.parseFrame("""{"kind":"hello","commands":true}""") as RelayFrame.Hello
        assertEquals(true, yes.commands)
        val no = PiRelayParser.parseFrame("""{"kind":"hello","commands":false}""") as RelayFrame.Hello
        assertEquals(false, no.commands)
        val list = PiRelayParser.parseFrame("""{"kind":"hello","commands":["send_message"]}""") as RelayFrame.Hello
        assertEquals(true, list.commands)
        val plain = PiRelayParser.parseFrame("""{"kind":"hello"}""") as RelayFrame.Hello
        assertNull(plain.commands)
    }

    // ------------------------------------------------------- upload pure

    @Test
    fun uploadUrlDerivesFromRelay() {
        assertEquals("http://h:1/upload", uploadUrlFor("ws://h:1/stream"))
        assertEquals("https://h:1/upload", uploadUrlFor("wss://h:1/stream"))
        assertEquals("http://h:1/upload", uploadUrlFor("ws://h:1/stream?token=x"))
        assertEquals("http://h:1/upload", uploadUrlFor("http://h:1/other"))
    }

    @Test
    fun terminalQuerySelectsTheSessionAndNothingElse() {
        // no terminal -> the legacy global stream, untouched
        assertEquals("ws://h:1/stream", withTerminalQuery("ws://h:1/stream", ""))
        // existing query (e.g. a token fallback) keeps its separator
        assertEquals("ws://h:1/stream?token=x&terminal=T-1", withTerminalQuery("ws://h:1/stream?token=x", "T-1"))
        assertEquals("ws://h:1/stream?terminal=T-1", withTerminalQuery("ws://h:1/stream", "T-1"))
        // ids never reach the query unencoded
        assertEquals("ws://h:1/stream?terminal=A%26B", withTerminalQuery("ws://h:1/stream", "A&B"))
        // uploads carry the same selector so the attachment lands in that session
        assertEquals("http://h:1/upload?terminal=T-1", uploadUrlFor("ws://h:1/stream?token=x", "T-1"))
        assertEquals("http://h:1/upload", uploadUrlFor("ws://h:1/stream", ""))
    }

    @Test
    fun uploadRequestAuthGate() {
        val anon = uploadRequest("http://h:1/upload", "", "f.txt", "data".toByteArray())
        assertNull(anon.header("Authorization"))
        assertEquals("f.txt", anon.header("X-Filename"))

        val authed = uploadRequest("http://h:1/upload", "tok", "f.txt", "data".toByteArray())
        assertEquals("Bearer tok", authed.header("Authorization"))
    }

    @Test
    fun uploadResponseParses() {
        val full = parseUploadResponse("""{"id":"a1","name":"f.png","bytes":41,"path":"/t/f.png"}""")!!
        assertEquals("a1", full.id)
        assertEquals(41L, full.bytes)
        assertNull(parseUploadResponse("not json"))
    }

    @Test
    fun uploadRejectionSurfacesCap() {
        val capped = parseUploadRejection(413, """{"error":"too big","maxBytes":1048576}""")
        assertEquals(1048576L, capped.sizeCapBytes)
        assertTrue(capped.message?.contains("too big") == true)

        val plain = parseUploadRejection(400, """{"message":"bad file"}""")
        assertEquals("bad file", plain.message)
        assertNull(plain.sizeCapBytes)

        val empty = parseUploadRejection(500, null)
        assertTrue(empty.message?.contains("500") == true)
        assertNull(empty.sizeCapBytes)
    }

    // ------------------------------------------------------- client level

    private class CapturingSocket(val req: Request) : WebSocket {
        val sent = mutableListOf<String>()
        override fun request(): Request = req
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class Harness(ackTimeoutMs: Long = 10_000L) {
        var request: Request? = null
        var listener: WebSocketListener? = null
        var socket: CapturingSocket? = null
        val client = PiRelayClient(
            "ws://h:1/stream",
            "",
            object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    synchronized(this@Harness) {
                        this@Harness.request = request
                        this@Harness.listener = listener
                    }
                    return CapturingSocket(request).also {
                        synchronized(this@Harness) { this@Harness.socket = it }
                    }
                }
            },
            ackTimeoutMs = ackTimeoutMs,
        )

        fun openWithHello(helloJson: String = """{"kind":"hello","sessionId":"s"}""") = runBlocking {
            client.connect()
            withTimeout(5000) {
                while (synchronized(this@Harness) { socket == null }) delay(10)
            }
            val req = request!!
            val sock = socket!!
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build()
            listener!!.onOpen(sock, response)
            listener!!.onMessage(sock, helloJson)
        }

        fun feed(json: String) {
            listener!!.onMessage(socket!!, json)
        }

        fun close() {
            client.disconnect()
        }
    }

    @Test
    fun sendMessageAckedCommandsSupported() = runBlocking {
        val h = Harness()
        h.openWithHello()
        assertNull(h.client.commandsSupported.value)

        h.client.sendMessage("hi", emptyList(), "m1")
        assertEquals(1, h.socket!!.sent.size)
        assertTrue(parsed(h.socket!!.sent.single())["clientMsgId"] == "m1")

        h.feed("""{"kind":"send_ack","clientMsgId":"m1","ok":true}""")
        withTimeout(5000) {
            h.client.sendOutcomes.first { it.containsKey("m1") }
        }
        assertTrue(h.client.sendOutcomes.value.getValue("m1").accepted)
        assertEquals(true, h.client.commandsSupported.value)
        h.close()
    }

    @Test
    fun rejectedUnsupportedMarksV1() = runBlocking {
        val h = Harness()
        h.openWithHello()
        h.client.sendMessage("hi", emptyList(), "m9")
        h.feed("""{"kind":"send_ack","clientMsgId":"m9","error":"unsupported command kind"}""")
        withTimeout(5000) {
            h.client.sendOutcomes.first { it.containsKey("m9") }
        }
        assertFalse(h.client.sendOutcomes.value.getValue("m9").accepted)
        assertEquals(false, h.client.commandsSupported.value)
        h.close()
    }

    @Test
    fun ackTimeoutMarksUnknownAsV1() = runBlocking {
        val h = Harness(ackTimeoutMs = 200L)
        h.openWithHello()
        h.client.sendMessage("hi", emptyList(), "mT")
        withTimeout(5000) {
            h.client.commandsSupported.first { it == false }
        }
        assertFalse(h.client.sendOutcomes.value.getValue("mT").accepted)
        h.close()
    }

    @Test
    fun helloAdvertisingCommandsMarksSupported() = runBlocking {
        val h = Harness()
        h.openWithHello("""{"kind":"hello","sessionId":"s","commands":true}""")
        withTimeout(5000) {
            h.client.commandsSupported.first { it == true }
        }
        h.close()
    }

    @Test
    fun sendBeforeHelloDropped() = runBlocking {
        val h = Harness()
        h.client.connect()
        withTimeout(5000) {
            while (synchronized(h) { h.socket == null }) delay(10)
        }
        val req = h.request!!
        val sock = h.socket!!
        val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build()
        h.listener!!.onOpen(sock, response)

        h.client.sendMessage("early", emptyList(), "mE")
        assertTrue(h.socket!!.sent.isEmpty())
        assertTrue(h.client.sendOutcomes.value.isEmpty())
        h.close()
    }

    @Test
    fun approvalsTrackedThroughResolution() = runBlocking {
        val h = Harness()
        h.openWithHello()
        h.feed("""{"kind":"approval_request","callId":"c1","tool":"bash","summary":"run"}""")
        withTimeout(5000) {
            h.client.approvals.first { it.containsKey("c1") }
        }
        assertEquals("bash", h.client.approvals.value.getValue("c1").tool)

        h.feed("""{"kind":"approval_resolved","callId":"c1","decision":"deny"}""")
        withTimeout(5000) {
            h.client.approvals.first { it.getValue("c1").decision == "deny" }
        }
        assertEquals("resolved", h.client.approvals.value.getValue("c1").status)
        h.close()
    }

    @Test
    fun attachmentReadyTracked() = runBlocking {
        val h = Harness()
        h.openWithHello()
        h.feed("""{"kind":"attachment_ready","id":"a1","name":"f.png","bytes":41}""")
        withTimeout(5000) {
            h.client.attachments.first { it.containsKey("a1") }
        }
        assertEquals(41L, h.client.attachments.value.getValue("a1").bytes)
        h.close()
    }
}
