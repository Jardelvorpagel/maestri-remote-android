package app.maestri.wire

import android.util.Base64
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
import org.connectbot.terminal.TerminalEmulator
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
    /** Rendered screen text (plain, from the emulator grid). */
    private val _screenText = MutableStateFlow("")
    val screenText: StateFlow<String> = _screenText

    private val _geometry = MutableStateFlow<Geometry?>(null)
    val geometry: StateFlow<Geometry?> = _geometry

    private val _cursor = MutableStateFlow<Pair<Int, Int>?>(null)
    val cursor: StateFlow<Pair<Int, Int>?> = _cursor

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var ws: WebSocket? = null
    private var backoffAttempts = 0
    private var closedByUs = false
    private var url: String? = null
    private var pingJob: Job? = null
    private var term: MiniTerm? = null
    /** Termlib renderer (todo #62): the screen owns one emulator per terminal and
     *  attaches it here; host bytes flow verbatim into `writeInput` (mutex-guarded
     *  inside the lib, so the socket thread can write directly). Nulled on close. */
    var termlib: TerminalEmulator? = null

    /** Tolerant UTF-8 decode: PTY output is usually UTF-8; malformed bytes → U+FFFD. */
    private val utf8Decode = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun open(targetUrl: String) {
        closedByUs = false
        url = targetUrl
        backoffAttempts = 0
        _screenText.value = ""
        connect()
    }

    fun close() {
        closedByUs = true
        pingJob?.cancel()
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
        }
        ws = null
        _state.value = SocketState.IDLE
        _screenText.value = ""
        _geometry.value = null
        _cursor.value = null
        term = null
        termlib = null
    }

    private fun connect() {
        if (closedByUs) return
        val target = url ?: return
        _state.value = SocketState.CONNECTING
        ws = socketClient.newWebSocket(
            Request.Builder().url(target).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
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
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "geometry" -> {
                                // ALWAYS followed by a fresh screen: reset the emulator.
                                val g = Geometry.clamp(
                                    msg.optInt("cols", 80),
                                    msg.optInt("rows", 24),
                                )
                                android.util.Log.d("QATerm", "geometry cols=${msg.optInt("cols")} rows=${msg.optInt("rows")} -> clamped ${g.cols}x${g.rows}")
                                _geometry.value = g
                                term = MiniTerm(g.cols, g.rows)
                                _cursor.value = 0 to 0
                            }

                            "pong" -> Unit
                            else -> Unit // ignore unknown types per contract
                        }
                    } catch (e: Exception) {
                        // Unparseable frame; contract says ignore.
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    android.util.Log.d("QATerm", "binary frame ${bytes.size} bytes")
                    termlib?.writeInput(bytes.toByteArray())
                    val decoded = utf8Decode.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                    val t = term ?: return
                    t.feed(decoded)
                    _screenText.value = t.render()
                    _cursor.value = t.cursor()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.value = SocketState.CLOSED
                    _error.value = t.message ?: "stream failed"
                    android.util.Log.d("QATerm", "stream onFailure: ${t.message}")
                    ws = null
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
        android.util.Log.d("QATerm", "send input: $text")
        ws?.send(JSONObject().put("type", "input").put("data", text).toString())
    }

    /** Raw bytes input (capability terminalInputBytes). */
    fun sendInputBytes(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        android.util.Log.d("QATerm", "send inputBytes: ${bytes.joinToString(",")}")
        ws?.send(JSONObject().put("type", "inputBytes").put("data", b64).toString())
    }

    /** Resize the host PTY — clamped server and client-side to 20..250 × 5..120. */
    fun sendResize(cols: Int, rows: Int) {
        val g = Geometry.clamp(cols, rows)
        android.util.Log.d("QATerm", "send resize: asked ${cols}x$rows -> sent ${g.cols}x${g.rows}")
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