package app.maestri.live

import android.util.Log
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
) {
    private val _state = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<PiEvent>>(emptyList())
    val events: StateFlow<List<PiEvent>> = _events.asStateFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var timeoutJob: Job? = null
    private var attemptId = 0L
    private var failures = 0
    private var closedByUs = false
    private var authFailed = false
    private var queryFallback = false
    private var pendingResume: Long? = null

    val configured: Boolean get() = url.isNotBlank()

    fun connect() {
        if (!configured) return
        if (_state.value is RelayState.Open || _state.value is RelayState.Connecting) return
        closedByUs = false
        authFailed = false
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
        synchronized(lock) {
            socket?.close(1000, null)
            socket = null
        }
        _state.value = RelayState.Disconnected
    }

    /**
     * Ask the server to replay from [seq]; sent now when open, otherwise held and
     * flushed on the next open.
     */
    fun resumeAfter(seq: Long) {
        synchronized(lock) { pendingResume = seq }
        synchronized(lock) {
            if (_state.value is RelayState.Open) socket?.send(resumeFrame(seq))
        }
    }

    private fun openSocket() {
        if (!configured || closedByUs || authFailed) return
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
        log("connecting host=$host port=$port")
        try {
            synchronized(lock) { socket = okHttp.newWebSocket(request, listener) }
        } catch (e: Exception) {
            timeoutJob?.cancel()
            timeoutJob = null
            val cause = safeFailure(e)
            log("create failed host=$host port=$port $cause")
            _state.value = RelayState.Error("Relay connect failed ($cause).")
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
                log("connect timeout host=$host port=$port after ${connectTimeoutMs}ms")
                scheduleReconnect("Relay connect timed out after ${connectTimeoutMs}ms, retrying.")
            }
        }
    }

    private fun scheduleReconnect(reason: String? = null) {
        synchronized(lock) {
            socket = null
        }
        timeoutJob?.cancel()
        timeoutJob = null
        pingJob?.cancel()
        pingJob = null
        if (closedByUs || authFailed) {
            if (closedByUs) _state.value = RelayState.Disconnected
            return
        }
        if (reason != null) _state.value = RelayState.Error(reason)
        else _state.value = RelayState.Connecting
        val waitMs = backoffBaseMs(failures) + (0L..999L).random()
        failures++
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
            is RelayFrame.Hello -> Unit
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
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            timeoutJob?.cancel()
            timeoutJob = null
            failures = 0
            _state.value = RelayState.Open
            log("opened host=${webSocket.request().url.host} port=${webSocket.request().url.port}")
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
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(500L)
                    openSocket()
                }
                return
            }
            if (response?.code == 401) {
                authFailed = true
                reconnectJob?.cancel()
                synchronized(lock) { socket = null }
                pingJob?.cancel()
                pingJob = null
                val message = relayAuthFailureMessage(token.isBlank())
                log("auth failed ${webSocket.request().url.host} $message")
                _state.value = RelayState.Error(message)
                return
            }
            val cause = safeFailure(t)
            val where = webSocket.request().url
            log("connect failed host=${where.host} port=${where.port} $cause")
            scheduleReconnect("Relay connect failed ($cause).")
        }
    }

    private fun safeFailure(t: Throwable): String {
        val raw = "${t.javaClass.simpleName}: ${t.message}"
        return if (token.isNotBlank()) raw.replace(token, "\u2022\u2022\u2022") else raw
    }

    private fun log(msg: String) {
        try {
            Log.d(RELAY_LOG_TAG, msg)
        } catch (_: RuntimeException) {
        }
    }
}
