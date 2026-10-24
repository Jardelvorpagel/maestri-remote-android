package app.maestri.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Client heartbeat while open; the server pings every 20–30s and we answer pong. */
private const val CLIENT_PING_MS = 25_000L

/**
 * 401 cadence: a rejected token retries on this floor, never hammering, never
 * dying silently. User Stop still kills the loop; manual Connect redials at once.
 */
const val AUTH_RETRY_FLOOR_MS = 60_000L

/** Transport-boundary log tag. Attempt lines carry host/port + outcome only, never the token. */
private const val RELAY_LOG_TAG = "PiRelay"

/**
 * Pi relay WebSocket transport (contract v1): `ws(s)://host:port/stream` with an
 * `Authorization: Bearer` header plus a `?token=` fallback after the first 401.
 *
 * Default-off: a blank url means [connect] is a no-op — no socket, no
 * network, no retry. Inbound text frames route through [PiRelayParser]; anything
 * malformed is ignored and the socket never takes the client down with it.
 */
class PiRelayClient(
    val url: String,
    val token: String,
    private val okHttp: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000L,
    private val ackTimeoutMs: Long = 10_000L,
) {
    private val _state = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<PiEvent>>(emptyList())
    val events: StateFlow<List<PiEvent>> = _events.asStateFlow()

    /**
     * v2 command support, tolerant detection: null until known; true on the
     * first routed `send_ack` or a hello advertising commands; false on an ack
     * timeout with no outcome while still unknown, a hello disclaiming
     * commands, or a rejection whose detail signals an unimplemented command.
     */
    private val _commandsSupported = MutableStateFlow<Boolean?>(null)
    val commandsSupported: StateFlow<Boolean?> = _commandsSupported.asStateFlow()

    /** `send_message` outcomes keyed by clientMsgId, including ack timeouts. */
    private val _sendOutcomes = MutableStateFlow<Map<String, SendOutcome>>(emptyMap())
    val sendOutcomes: StateFlow<Map<String, SendOutcome>> = _sendOutcomes.asStateFlow()

    /** Tool approvals keyed by callId, request through resolution. */
    private val _approvals = MutableStateFlow<Map<String, Approval>>(emptyMap())
    val approvals: StateFlow<Map<String, Approval>> = _approvals.asStateFlow()

    /** Ready attachments keyed by id. */
    private val _attachments = MutableStateFlow<Map<String, Attachment>>(emptyMap())
    val attachments: StateFlow<Map<String, Attachment>> = _attachments.asStateFlow()

    /**
     * Retry policy, stated: transport failures (timeout/refused/closed, plus a
     * synchronous dial throw) reschedule with [backoffBaseMs] (1s doubling to
     * the 30s cap + ≤1s jitter); 401s reschedule on [AUTH_RETRY_FLOOR_MS] (60s —
     * polite, and a transient rejection can no longer strand the loop); user
     * Stop never schedules (false). The Chat error strip copies this into the
     * body so the retry position is on screen, not in logcat.
     */
    private val _autoRetry = MutableStateFlow(false)
    val autoRetry: StateFlow<Boolean> = _autoRetry.asStateFlow()

    /**
     * The relay has this terminal's socket open but no pi session bound to it yet
     * (the terminal's session has not declared itself). Distinct from Error: the
     * transport is fine, there is simply nothing to serve, and never another's chat.
     */
    private val _waitingForSession = MutableStateFlow(false)
    val waitingForSession: StateFlow<Boolean> = _waitingForSession.asStateFlow()

    /**
     * Pi's own decision box, mirrored: the latest open prompt, null when closed
     * or never seen. The phone never answers these — no resolver in the API.
     */
    private val _decisionPrompt = MutableStateFlow<DecisionPrompt?>(null)
    val decisionPrompt: StateFlow<DecisionPrompt?> = _decisionPrompt.asStateFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var timeoutJob: Job? = null
    private var attemptId = 0L
    private var failures = 0
    private var closedByUs = false
    private var queryFallback = false
    private var pendingResume: Long? = null
    private var boundSessionId: String? = null
    private var helloSeen = false
    private val ackJobs = mutableMapOf<String, Job>()

    val configured: Boolean get() = url.isNotBlank()

    fun connect() {
        if (!configured) return
        if (_state.value is RelayState.Open || _state.value is RelayState.Connecting) return
        closedByUs = false
        scope.launch { openSocket() }
    }

    fun disconnect() {
        closedByUs = true
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        _autoRetry.value = false
        synchronized(lock) {
            ackJobs.values.forEach { it.cancel() }
            ackJobs.clear()
            helloSeen = false
        }
        _commandsSupported.value = null
        _decisionPrompt.value = null
        _waitingForSession.value = false
        synchronized(lock) {
            socket?.close(1000, null)
            socket = null
        }
        _state.value = RelayState.Disconnected
    }

    /**
     * Ask the relay agent to speak: sent only on an open socket after hello.
     * Blank ids are replaced with a generated `msg-` id so acks still route.
     */
    fun sendMessage(text: String, attachmentIds: List<String>, clientMsgId: String) {
        val id = clientMsgId.ifBlank { "msg-" + java.util.UUID.randomUUID().toString() }
        if (!sendCommand("send_message", id, sendMessageFrame(text, attachmentIds, id))) return
        armAckTimeout(id)
    }

    /** Answer one tool approval prompt. Values pass through for the server. */
    fun sendDecision(callId: String, decision: String, remember: String) {
        sendCommand("tool_decision", callId, toolDecisionFrame(callId, decision, remember))
    }

    private fun sendCommand(kind: String, id: String, frame: String): Boolean {
        synchronized(lock) {
            if (!helloSeen || _state.value !is RelayState.Open || socket == null) {
                relayLog(RELAY_LOG_TAG, "command dropped kind=$kind len=${frame.length}")
                return false
            }
            socket?.send(frame)
        }
        relayLog(RELAY_LOG_TAG, "command sent kind=$kind id=$id len=${frame.length}")
        return true
    }

    private fun armAckTimeout(clientMsgId: String) {
        synchronized(lock) { ackJobs[clientMsgId]?.cancel() }
        val job = scope.launch {
            delay(ackTimeoutMs)
            var apply = false
            synchronized(lock) {
                if (!_sendOutcomes.value.containsKey(clientMsgId) &&
                    _commandsSupported.value == null
                ) {
                    apply = true
                }
                ackJobs.remove(clientMsgId)
            }
            if (apply) {
                _commandsSupported.value = false
                recordOutcome(
                    SendOutcome(clientMsgId, false, "no ack — relay may not support commands"),
                )
                relayLog(RELAY_LOG_TAG, "ack timeout id=$clientMsgId")
            }
        }
        synchronized(lock) { ackJobs[clientMsgId] = job }
    }

    private fun recordOutcome(outcome: SendOutcome) {
        _sendOutcomes.value = _sendOutcomes.value + (outcome.clientMsgId to outcome)
    }
    fun resumeAfter(seq: Long) {
        synchronized(lock) { pendingResume = seq }
        synchronized(lock) {
            if (_state.value is RelayState.Open) socket?.send(resumeFrame(seq))
        }
    }

    private fun openSocket() {
        if (!configured || closedByUs) return
        val request = try {
            buildRelayRequest(url, token, queryFallback)
        } catch (_: Exception) {
            _state.value = RelayState.Error("Bad relay URL.")
            return
        }
        val host = request.url.host
        val port = request.url.port
        val id: Long
        synchronized(lock) {
            attemptId++
            id = attemptId
        }
        _state.value = RelayState.Connecting
        relayLog(RELAY_LOG_TAG, "connecting host=$host port=$port")
        try {
            synchronized(lock) { socket = okHttp.newWebSocket(request, listener) }
        } catch (e: Exception) {
            timeoutJob?.cancel()
            timeoutJob = null
            val cause = safeFailure(e)
            relayLog(RELAY_LOG_TAG, "create failed host=$host port=$port $cause")
            // Never die silently here: a synchronous throw (exhausted dispatcher,
            // socket factory failure) enters the same bounded retry as any transport
            // failure instead of stranding the UI on Connect with a dead loop.
            scheduleReconnect("Relay connect failed ($cause).")
            return
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(connectTimeoutMs)
            var expired = false
            synchronized(lock) {
                if (attemptId == id && _state.value is RelayState.Connecting) {
                    socket?.cancel()
                    socket = null
                    expired = true
                }
            }
            if (expired) {
                relayLog(RELAY_LOG_TAG, "connect timeout host=$host port=$port after ${connectTimeoutMs}ms")
                scheduleReconnect("Relay connect timed out after ${connectTimeoutMs}ms, retrying.")
            }
        }
    }

    /**
     * Bounded retry, stated: transport failures wait [backoffBaseMs] (1s doubling
     * to the 30s cap + ≤1s jitter); 401s wait [floorMs] ([AUTH_RETRY_FLOOR_MS] —
     * polite, and a transient 401 can no longer kill the loop until the next
     * manual tap). Only user Stop ([closedByUs]) schedules nothing.
     */
    private fun scheduleReconnect(reason: String? = null, floorMs: Long = 0L) {
        synchronized(lock) {
            socket = null
        }
        timeoutJob?.cancel()
        timeoutJob = null
        pingJob?.cancel()
        pingJob = null
        if (closedByUs) {
            _state.value = RelayState.Disconnected
            return
        }
        if (reason != null) _state.value = RelayState.Error(reason)
        else _state.value = RelayState.Connecting
        val waitMs = maxOf(floorMs, backoffBaseMs(failures) + (0L..999L).random())
        failures++
        _autoRetry.value = true
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            openSocket()
        }
    }

    private fun onFrameText(text: String) {
        val frame = try {
            PiRelayParser.parseFrame(text)
        } catch (_: Exception) {
            null
        } ?: return
        when (frame) {
            is RelayFrame.Hello -> {
                synchronized(lock) { helloSeen = true }
                // The relay re-bound this socket to another session (the terminal
                // now runs a different pi session): the old transcript must go, and
                // a resume from the old seq would be meaningless in the new one.
                _waitingForSession.value = frame.waiting == "terminal"
                if (frame.sessionId.isNotBlank() && frame.sessionId != boundSessionId) {
                    boundSessionId = frame.sessionId
                    synchronized(lock) { pendingResume = null }
                    _events.value = emptyList()
                }
                frame.commands?.let { _commandsSupported.value = it }
                relayLog(RELAY_LOG_TAG, "hello session=${frame.sessionId.take(8)} commands=${frame.commands}")
            }
            is RelayFrame.Ping -> synchronized(lock) { socket?.send(pongFrame()) }
            is RelayFrame.Pong -> Unit
            is RelayFrame.ResumeGap -> {
                _events.value = emptyList()
                val after = maxOf(0L, (frame.oldestSeq ?: 1L) - 1L)
                synchronized(lock) {
                    pendingResume = after
                    if (_state.value is RelayState.Open) socket?.send(resumeFrame(after))
                }
            }
            is RelayFrame.Event -> {
                _events.value = insertRelayEvent(_events.value, frame.event)
            }
            is RelayFrame.SendAckFrame -> onSendAck(frame.ack)
            is RelayFrame.ApprovalRequest -> {
                val a = frame.approval
                if (a.callId.isNotBlank()) {
                    _approvals.value = _approvals.value + (a.callId to a)
                    relayLog(RELAY_LOG_TAG, "approval request call=${a.callId.take(16)} tool=${a.tool.take(32)}")
                }
            }
            is RelayFrame.ApprovalResolved -> {
                val a = frame.approval
                if (a.callId.isNotBlank()) {
                    val prev = _approvals.value[a.callId]
                    _approvals.value = _approvals.value + (a.callId to a.copy(
                        summary = a.summary.ifBlank { prev?.summary ?: "" },
                        status = a.status.ifBlank { "resolved" },
                    ))
                    relayLog(RELAY_LOG_TAG, "approval resolved call=${a.callId.take(16)} decision=${a.decision}")
                }
            }
            is RelayFrame.AttachmentReady -> {
                val a = frame.attachment
                if (a.id.isNotBlank()) {
                    _attachments.value = _attachments.value + (a.id to a)
                    relayLog(RELAY_LOG_TAG, "attachment ready id=${a.id.take(16)} bytes=${a.bytes}")
                }
            }
            is RelayFrame.DecisionPromptFrame -> {
                _decisionPrompt.value = if (frame.prompt.isOpen) frame.prompt else null
                relayLog(RELAY_LOG_TAG, "decision prompt phase=${frame.prompt.phase} kind=${frame.prompt.promptKind.take(32)}")
            }
        }
    }

    private fun onSendAck(ack: SendAck) {
        synchronized(lock) { ackJobs.remove(ack.clientMsgId)?.cancel() }
        recordOutcome(SendOutcome(ack.clientMsgId, ack.accepted, ack.detail))
        if (ack.accepted) {
            _commandsSupported.value = true
        } else if (looksUnsupported(ack.detail)) {
            _commandsSupported.value = false
        }
        relayLog(RELAY_LOG_TAG, "ack id=${ack.clientMsgId} accepted=${ack.accepted}")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            timeoutJob?.cancel()
            timeoutJob = null
            failures = 0
            _autoRetry.value = false
            _state.value = RelayState.Open
            relayLog(RELAY_LOG_TAG, "opened host=${webSocket.request().url.host} port=${webSocket.request().url.port}")
            synchronized(lock) {
                pendingResume?.let { webSocket.send(resumeFrame(it)) }
            }
            pingJob?.cancel()
            pingJob = scope.launch {
                while (true) {
                    delay(CLIENT_PING_MS)
                    var alive = false
                    synchronized(lock) {
                        alive = _state.value is RelayState.Open && socket != null
                        if (alive) socket?.send(pingFrame())
                    }
                    if (!alive) return@launch
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                onFrameText(text)
            } catch (_: Exception) {
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect("Relay connection closed, retrying.")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            timeoutJob?.cancel()
            timeoutJob = null
            if (response?.code == 401 && !queryFallback && token.isNotBlank()) {
                queryFallback = true
                failures = 0
                _autoRetry.value = true
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(500L)
                    openSocket()
                }
                return
            }
            if (response?.code == 401) {
                reconnectJob?.cancel()
                synchronized(lock) { socket = null }
                pingJob?.cancel()
                pingJob = null
                val message = relayAuthFailureMessage(token.isBlank())
                relayLog(RELAY_LOG_TAG, "auth failed ${webSocket.request().url.host} $message")
                scheduleReconnect(message, floorMs = AUTH_RETRY_FLOOR_MS)
                return
            }
            val cause = safeFailure(t)
            val where = webSocket.request().url
            relayLog(RELAY_LOG_TAG, "connect failed host=${where.host} port=${where.port} $cause")
            scheduleReconnect("Relay connect failed ($cause).")
        }
    }

    private fun safeFailure(t: Throwable): String {
        val raw = "${t.javaClass.simpleName}: ${t.message}"
        return if (token.isNotBlank()) raw.replace(token, "\u2022\u2022\u2022") else raw
    }
}
