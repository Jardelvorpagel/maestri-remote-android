package app.maestri.wire

import android.content.Context
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
import java.util.concurrent.atomic.AtomicLong

enum class SocketState { IDLE, CONNECTING, OPEN, CLOSED }

/**
 * Live feed socket for one workspace: `WS /api/feed/stream?ws={ws}&floor=…&token=…`.
 *
 * Server → client:
 *   {"type":"feed","snapshot":…}      — source of truth, full state (debounced ≥750 ms)
 *   {"type":"mutation","mutation":…}  — attribution only; dedupe by (epoch, sequence)
 *   {"type":"pong"}                   — ignore
 *
 * A NEW epoch means this server run restarted: resync and forget dedupe state.
 * Unknown `type`s are ignored per contract.
 */
class FeedSocket(
    clientProvider: () -> OkHttpClient,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val socketClientProvider: () -> OkHttpClient = clientProvider
    private val socketClient: OkHttpClient get() = socketClientProvider()
    private val orderCounter = AtomicLong(System.nanoTime())

    private val _snapshot = MutableStateFlow<FeedSnapshot?>(null)
    val snapshot: StateFlow<FeedSnapshot?> = _snapshot

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    val lastMutationKind = MutableStateFlow<String?>(null)

    /** Last mutation event as parsed (canvas writes reconcile their optimistic overlay against it). */
    private val _lastMutation = MutableStateFlow<MutationEvent?>(null)
    val lastMutation: StateFlow<MutationEvent?> = _lastMutation

    /** Our own last presence report — rendered locally as the self cursor. */
    private val _selfPresence = MutableStateFlow<PresencePeer?>(null)
    val selfPresence: StateFlow<PresencePeer?> = _selfPresence

    /** Peer presence frames, when the host sends any (the contract defines no peer roster). */
    private val _peers = MutableStateFlow<List<PresencePeer>>(emptyList())
    val peers: StateFlow<List<PresencePeer>> = _peers

    /** Identity used to tell our own presence frames apart from a peer's. */
    @Volatile
    var selfDeviceId: String = ""

    @Volatile
    var selfLabel: String = "You"

    private var ws: WebSocket? = null
    private var connectJob: Job? = null
    private var pingJob: Job? = null
    private var backoffAttempts = 0
    private var closedByUs = false

    /** Last (epoch, sequence) keys applied, for dedupe. Cleared on epoch change. */
    private var appliedEpoch: String? = null
    private val appliedSequences = HashSet<Long>()

    /** Presence payload to (re)send after every (re)connect, per contract. */
    @Volatile
    private var pendingPresence: JSONObject? = null

    /**
     * The network came back: reconnect NOW instead of waiting out the backoff. The state
     * guard matters — the callback also fires when it registers, and replacing a healthy
     * socket would drop a live feed for nothing.
     */
    private val networkRecovery = NetworkRecovery(context, scope) {
        if (_state.value == SocketState.CLOSED) {
            backoffAttempts = 0
            connect()
        }
    }

    /** The URL to connect to (contains ws id, optional floor, token). */
    @Volatile
    private var url: String? = null
    val currentUrl: String? get() = url

    /** Hook for a full HTTP snapshot fetch when an epoch change is observed. */
    var resync: (suspend () -> FeedSnapshot?)? = null

    fun open(targetUrl: String) {
        closedByUs = false
        url = targetUrl
        appliedEpoch = null
        appliedSequences.clear()
        _snapshot.value = null
        networkRecovery.start()
        connect()
    }

    /** Re-open against a new floor without dropping the workspace. */
    fun retarget(targetUrl: String) {
        val changed = url != targetUrl
        url = targetUrl
        if (changed) {
            appliedEpoch = null
            appliedSequences.clear()
            _snapshot.value = null
            _state.value = SocketState.CONNECTING
            ws?.close(1000, "retarget")
            networkRecovery.start()
            connect()
        }
    }

    /** Seed from HTTP so content displays before the socket's first snapshot. */
    fun seed(s: FeedSnapshot) {
        if (_snapshot.value == null) _snapshot.value = s
    }

    /** Drop current snapshot (floor retarget seeds afresh). */
    fun reset() {
        _snapshot.value = null
        appliedEpoch = null
        appliedSequences.clear()
    }

    fun close() {
        closedByUs = true
        connectJob?.cancel()
        pingJob?.cancel()
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
        }
        ws = null
        _state.value = SocketState.IDLE
        _selfPresence.value = null
        _peers.value = emptyList()
        networkRecovery.stop()
    }

    private fun connect() {
        if (closedByUs) return
        val target = url ?: return
        _state.value = SocketState.CONNECTING
        connectJob = scope.launch {
            try {
                ws = socketClient.newWebSocket(
                    Request.Builder()
                        .url(target)
                        .build(),
                    Listener(),
                )
            } catch (e: Exception) {
                _lastError.value = e.message ?: "socket error"
                scheduleReconnect()
            }
        }
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

    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                ws?.send("""{"type":"ping"}""")
            }
        }
    }

    fun sendPresence(nodeId: String?) {
        val body = nodeId?.let {
            JSONObject().put("type", "presence").put("nodeId", it)
        } ?: JSONObject().put("type", "presence")
        pendingPresence = body
        ws?.send(body.toString())
        // "Os dois ausentes significa lugar nenhum": leaving a node clears our own report.
        _selfPresence.value = nodeId?.let {
            PresencePeer(
                id = selfDeviceId.ifBlank { "self" },
                name = selfLabel,
                nodeId = it,
                atMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Report where this device is looking, in canvas points — the documented "real pointer" form
     * of presence. The host draws our cursor with it; the canvas renders the same report locally.
     */
    fun sendPresencePoint(x: Float, y: Float) {
        val body = JSONObject()
            .put("type", "presence")
            .put("point", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
        pendingPresence = body
        ws?.send(body.toString())
        _selfPresence.value = PresencePeer(
            id = selfDeviceId.ifBlank { "self" },
            name = selfLabel,
            x = x.toDouble(),
            y = y.toDouble(),
            atMillis = System.currentTimeMillis(),
        )
    }

    inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffAttempts = 0
            _state.value = SocketState.OPEN
            _lastError.value = null
            startPinging()
            pendingPresence?.let { ws?.send(it.toString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "feed" -> {
                        val snap = FeedSnapshot.parse(msg.optJSONObject("snapshot") ?: JSONObject(),
                            orderCounter.incrementAndGet())
                        onSnapshot(snap)
                    }

                    "mutation" -> {
                        val m = MutationEvent.parse(msg) ?: return
                        lastMutationKind.value = m.kind
                        _lastMutation.value = m
                        onMutation(m)
                    }

                    "presence" -> {
                        // Undocumented as server -> client; tolerated when a host sends it.
                        val self = selfDeviceId.ifBlank { null }
                        val peer = PresencePeer.parse(msg, self, selfLabel, System.currentTimeMillis())
                        if (peer != null) {
                            if (peer.id == self) _selfPresence.value = peer
                            else _peers.value = _peers.value.filterNot { it.id == peer.id } + peer
                        }
                    }

                    "pong" -> Unit
                    else -> Unit // ignore unknown types per contract
                }
            } catch (e: Exception) {
                // Never crash the socket on parse errors of unanticipated payloads.
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The feed socket only sends text frames; ignore binaries defensively.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = SocketState.CLOSED
            _lastError.value = t.message ?: "socket failed"
            android.util.Log.d("QAFeed", "feed onFailure: ${t.message}")
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

    private fun onSnapshot(snap: FeedSnapshot) {
        val epochChanged = appliedEpoch != null && appliedEpoch != snap.epoch
        if (epochChanged) appliedSequences.clear()
        android.util.Log.d("QAFeed", "snapshot epoch=${snap.epoch} epochChanged=$epochChanged seqApplied=${appliedSequences.size}")
        appliedEpoch = snap.epoch
        // Never let an older HTTP refetch overwrite a newer socket snapshot.
        val current = _snapshot.value
        if (current == null || snap.receivedOrder >= current.receivedOrder) {
            _snapshot.value = snap
        }
        if (epochChanged) {
            // New server run: resync from snapshot ASAP; HTTP refetch is authoritative.
            scope.launch {
                val fetched = resync?.invoke()
                if (fetched != null && fetched.epoch == appliedEpoch) {
                    val cur = _snapshot.value
                    if (cur == null || fetched.receivedOrder >= cur.receivedOrder) {
                        _snapshot.value = fetched
                    }
                }
            }
        }
    }

    private fun onMutation(m: MutationEvent) {
        android.util.Log.d("QAFeed", "mutation kind=${m.kind} epoch=${m.epoch} seq=${m.sequence}")
        if (m.epoch != appliedEpoch) {
            // Epoch change via mutation alone (snapshot may lag behind, debounced).
            android.util.Log.d("QAFeed", "EPOCH change ->${m.epoch} via mutation; resync")
            appliedEpoch = m.epoch
            appliedSequences.clear()
            appliedSequences.add(m.sequence)
            scope.launch {
                val fetched = resync?.invoke()
                if (fetched != null && fetched.epoch == m.epoch) {
                    val cur = _snapshot.value
                    if (cur == null || fetched.receivedOrder >= cur.receivedOrder) {
                        _snapshot.value = fetched
                    }
                }
            }
            return
        }
        // Dedupe by (epoch, sequence) — never by mutationId.
        if (m.sequence >= 0 && !appliedSequences.add(m.sequence)) {
            android.util.Log.d("QAFeed", "DUPLICATE dropped epoch=${m.epoch} seq=${m.sequence}")
            return
        }
    }
}