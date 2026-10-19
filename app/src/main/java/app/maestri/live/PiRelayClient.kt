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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Client heartbeat while open; the server pings every 20–30s and we answer pong. */
private const val CLIENT_PING_MS = 25_000L

/**
 * Pi relay WebSocket transport (contract v1): `ws(s)://host:port/stream` with an
 * `Authorization: Bearer` header plus a `?token=` fallback after the first 401.
 *
 * Default-off: a blank url or token means [connect] is a no-op — no socket, no
 * network, no retry. Inbound text frames route through [PiRelayParser]; anything
 * malformed is ignored and the socket never takes the client down with it.
 */
class PiRelayClient(
    val url: String,
    val token: String,
    private val okHttp: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<PiEvent>>(emptyList())
    val events: StateFlow<List<PiEvent>> = _events.asStateFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var failures = 0
    private var closedByUs = false
    private var authFailed = false
    private var queryFallback = false
    private var pendingResume: Long? = null

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

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
        val endpoint = try {
            val base = normalizeRelayUrl(url)
            if (base.isBlank()) return
            if (queryFallback) withTokenQuery(base, token) else base
        } catch (_: Exception) {
            _state.value = RelayState.Error("Bad relay URL.")
            return
        }
        val request = try {
            Request.Builder().url(endpoint).header("Authorization", "Bearer $token").build()
        } catch (_: Exception) {
            _state.value = RelayState.Error("Bad relay URL.")
            return
        }
        _state.value = RelayState.Connecting
        try {
            synchronized(lock) { socket = okHttp.newWebSocket(request, listener) }
        } catch (_: Exception) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            socket = null
        }
        pingJob?.cancel()
        pingJob = null
        if (closedByUs || authFailed) {
            if (closedByUs) _state.value = RelayState.Disconnected
            return
        }
        _state.value = RelayState.Connecting
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
            failures = 0
            _state.value = RelayState.Open
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
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401 && !queryFallback) {
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
                _state.value = RelayState.Error("Relay rejected the token (HTTP 401).")
                return
            }
            scheduleReconnect()
        }
    }
}
