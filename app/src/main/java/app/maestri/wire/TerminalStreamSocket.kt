package app.maestri.wire

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * Live terminal stream: `WS /api/terminals/{id}/stream?token=…`
 *
 * On connect the server sends {"type":"geometry","cols","rows"} followed by a BINARY
 * frame with the current screen (ANSI). Every later binary frame is raw PTY output.
 * A geometry frame can arrive any time and is ALWAYS followed by a new screen — we
 * reset the emulator when it arrives.
 *
 * Client → server (JSON text frames):
 *   ping / input (UTF-8) / inputBytes (base64) / resize (cols 20..250, rows 5..120)
 * OkHttp masks every client frame automatically (RFC 6455 requirement).
 */
class TerminalStreamSocket(
    clientProvider: () -> OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val socketClientProvider: () -> OkHttpClient = clientProvider
    private val socketClient: OkHttpClient get() = socketClientProvider()

    private val _geometry = MutableStateFlow<Geometry?>(null)
    val geometry: StateFlow<Geometry?> = _geometry

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var ws: WebSocket? = null
    private var backoffAttempts = 0
    private var closedByUs = false
    private var url: String? = null
    private var owner: String? = null
    private var pingJob: Job? = null
    /**
     * The renderer's byte tap: the xterm page attaches one on the main thread and gets
     * every binary PTY frame verbatim, in order, before any other consumer would.
     */
    @Volatile
    var frameSink: ((ByteArray) -> Unit)? = null

    /**
     * The renderer's grid tap: fires in the same read order as [frameSink], so the page can
     * queue a geometry change and the frames that follow it as one ordered stream.
     */
    @Volatile
    var gridSink: ((Int, Int) -> Unit)? = null

    fun open(targetUrl: String, ownerId: String? = null) {
        closedByUs = false
        url = targetUrl
        owner = ownerId
        backoffAttempts = 0
        connect()
    }

    /** Reconnect in place (same URL): used when a dropped frame needs a fresh screen. */
    fun reopen() {
        closedByUs = false
        backoffAttempts = 0
        connect()
    }

    /**
     * Tears the stream down. With an [ownerId] the call is ignored unless that terminal is
     * still the current owner, so a screen leaving the composition cannot close the socket
     * a newer screen has already opened.
     */
    fun close(ownerId: String? = null) {
        if (ownerId != null && owner != ownerId) return
        closedByUs = true
        owner = null
        pingJob?.cancel()
        val socket = ws
        ws = null
        try {
            socket?.close(1000, "bye")
            socket?.cancel()
        } catch (e: Exception) {
        }
        _state.value = SocketState.IDLE
        _geometry.value = null
    }

    // Ownership guard (QA round-2 teardown fix): every callback must belong to the CURRENT
    // WebSocket. A stale listener from a replaced/closed connection must not touch shared
    // state: after the webterm's close(), a late onFailure from the old connection poisoned
    // _error with "stream failed" (the banner the phone lane saw), and a late onClosed that
    // raced a reopen (open() resets closedByUs) fired a stray scheduleReconnect, minting a
    // competing reader that fought the live one over geometry. All five callbacks now bail
    // unless `webSocket === ws`. Stale frames are simply dropped; the close handshake's
    // trailing events are inert instead of lies.
    private fun connect() {
        if (closedByUs) return
        val target = url ?: return
        // A second connect must not leave the previous socket alive: its callbacks would
        // never match `ws` and the connection would leak until the host timed it out.
        val previous = ws
        ws = null
        try {
            previous?.cancel()
        } catch (e: Exception) {
        }
        _state.value = SocketState.CONNECTING
        ws = socketClient.newWebSocket(
            Request.Builder().url(target).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== ws) return
                    backoffAttempts = 0
                    _state.value = SocketState.OPEN
                    _error.value = null
                    pingJob?.cancel()
                    pingJob = scope.launch {
                        while (isActive) {
                            delay(30_000)
                            ws?.send("""{"type":"ping"}""")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== ws) return
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "geometry" -> {
                                // ALWAYS followed by a fresh screen: reset the emulator.
                                val g = Geometry.clamp(
                                    msg.optInt("cols", 80),
                                    msg.optInt("rows", 24),
                                )
                                _geometry.value = g
                                gridSink?.invoke(g.cols, g.rows)
                            }

                            "pong" -> Unit
                            else -> Unit // ignore unknown types per contract
                        }
                    } catch (e: Exception) {
                        // Unparseable frame; contract says ignore.
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (webSocket !== ws) return
                    frameSink?.invoke(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== ws) return
                    _state.value = SocketState.CLOSED
                    _error.value = t.message ?: "stream failed"
                    ws = null
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== ws) return
                    if (!closedByUs) {
                        _state.value = SocketState.CLOSED
                        ws = null
                        scheduleReconnect()
                    } else {
                        _state.value = SocketState.IDLE
                    }
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (closedByUs) return
        val attempt = backoffAttempts++
        val delayMs = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L)
        scope.launch {
            delay(delayMs)
            if (!closedByUs) connect()
        }
    }

    // ------------------------------------------------------------------ client input

    /** UTF-8 text input (capability terminalInputText). */
    fun sendInput(text: String) {
        ws?.send(JSONObject().put("type", "input").put("data", text).toString())
    }

    /** Resize the host PTY — clamped server and client-side to 20..250 × 5..120. */
    fun sendResize(cols: Int, rows: Int) {
        val g = Geometry.clamp(cols, rows)
        ws?.send(
            JSONObject()
                .put("type", "resize")
                .put("cols", g.cols)
                .put("rows", g.rows)
                .toString()
        )
    }

    fun sendPing() {
        ws?.send("""{"type":"ping"}""")
    }
}