package app.maestri.wire

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Agent-usage stream: `wss /api/agent-usage/stream?token=…`
 *
 * Evidence: knowledge/sources/2026-10-16-maestri-wire-write-schemas.md §5ter
 * "agent-usage (NEW CONFIRMED FAMILY)":
 *  - pushes the FULL snapshot on connect with no request frame;
 *  - unknown request frames are silently ignored (no ack/error) — this client sends none;
 *  - ServerMessage {"type":"agentUsage","usage":{canRefresh,isEnabled,providers[Provider]}};
 *  - Provider {id, name, colorHex (plain 6-hex, NO '#'), lastSuccessAt?, phase, reading:{limitsApply, meters[Meter]}};
 *  - Meter {id, label:{key?,text}, windows[{id, durationSeconds, resetsAt, usedFraction 0…1, ring 0|1|2?}]}.
 * Honest residues (per that source): `phase` values beyond "ready" are docs-typed CANDIDATE and
 * parse as an open string; the `canRefresh` flag has NO client-reachable trigger (every
 * agent-usage REST route probed 404) — the UI must keep any refresh affordance inert.
 */
class AgentUsageSocket(
    clientProvider: () -> OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val socketClient: OkHttpClient get() = clientProvider()

    private val _usage = MutableStateFlow<AgentUsageReading?>(null)
    val usage: StateFlow<AgentUsageReading?> = _usage

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** The latest raw text frame (the payload card renders the real received JSON). */
    private val _lastFrame = MutableStateFlow<String?>(null)
    val lastFrame: StateFlow<String?> = _lastFrame

    private var ws: WebSocket? = null
    private var backoffAttempts = 0
    private var closedByUs = false
    @Volatile
    private var url: String? = null

    fun open(targetUrl: String) {
        closedByUs = false
        url = targetUrl
        backoffAttempts = 0
        connect()
    }

    fun close() {
        closedByUs = true
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
        }
        ws = null
        _state.value = SocketState.IDLE
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
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        if (msg.optString("type") != "agentUsage") return
                        _lastFrame.value = text
                        _usage.value = AgentUsageReading.parse(msg.optJSONObject("usage") ?: JSONObject())
                    } catch (e: Exception) {
                        // Unparseable frame: contract says ignore; the last good reading stays.
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.value = SocketState.CLOSED
                    _error.value = t.message ?: "agentUsage stream failed"
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
            },
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
}

/** The `usage` container of one ServerMessage — the whole current truth, replaced per frame. */
data class AgentUsageReading(
    val canRefresh: Boolean,
    val isEnabled: Boolean,
    val providers: List<AgentUsageProvider>,
) {
    companion object {
        fun parse(o: JSONObject): AgentUsageReading = AgentUsageReading(
            canRefresh = o.optBoolean("canRefresh", false),
            isEnabled = o.optBoolean("isEnabled", false),
            providers = o.optJSONArray("providers")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { AgentUsageProvider.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList(),
        )
    }
}

/** colorHex rides as plain 6-hex WITHOUT the '#'-prefix that notes/groups use. */
data class AgentUsageProvider(
    val id: String,
    val name: String,
    val colorHex: String?,
    val phase: String?,
    val lastSuccessAt: String?,
    val limitsApply: Boolean?,
    val meters: List<AgentUsageMeter>,
) {
    companion object {
        fun parse(o: JSONObject): AgentUsageProvider {
            val reading = o.optJSONObject("reading")
            return AgentUsageProvider(
                id = o.optString("id"),
                name = o.optString("name"),
                colorHex = o.optString("colorHex").takeIf { it.length == 6 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } },
                phase = o.optString("phase").takeIf { it.isNotBlank() },
                lastSuccessAt = if (o.has("lastSuccessAt") && !o.isNull("lastSuccessAt")) o.getString("lastSuccessAt") else null,
                limitsApply = reading?.let { if (it.has("limitsApply") && !it.isNull("limitsApply")) it.optBoolean("limitsApply") else null },
                meters = reading?.optJSONArray("meters")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { AgentUsageMeter.parse(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList(),
            )

        }
    }
}

/** label rides {key?, text} — host-locale wording; either side may be absent. */
data class AgentUsageMeter(
    val id: String,
    val labelKey: String?,
    val labelText: String?,
    val windows: List<AgentUsageWindow>,
) {
    companion object {
        fun parse(o: JSONObject): AgentUsageMeter {
            val label = o.optJSONObject("label")
            return AgentUsageMeter(
                id = o.optString("id"),
                labelKey = label?.optString("key")?.takeIf { it.isNotBlank() },
                labelText = label?.optString("text")?.takeIf { it.isNotBlank() },
                windows = o.optJSONArray("windows")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { AgentUsageWindow.parse(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList(),
            )
        }
    }
}

/** usedFraction is stored raw; validity (finite 0…1) is a render concern, never invented. */
data class AgentUsageWindow(
    val id: String,
    val durationSeconds: Long?,
    val resetsAt: String?,
    val usedFraction: Double?,
    val ring: Int?,
) {
    companion object {
        fun parse(o: JSONObject): AgentUsageWindow = AgentUsageWindow(
            id = o.optString("id"),
            durationSeconds = o.optLong("durationSeconds", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
            resetsAt = if (o.has("resetsAt") && !o.isNull("resetsAt")) o.getString("resetsAt") else null,
            usedFraction = o.optDouble("usedFraction", Double.NaN).takeIf { !it.isNaN() },
            ring = if (o.has("ring") && !o.isNull("ring")) o.optInt("ring") else null,
        )
    }
}