package app.maestri.wire.session

import android.content.Context
import app.maestri.store.Pairing
import app.maestri.store.PairingStore
import app.maestri.wire.ApiException
import app.maestri.wire.FeedSocket
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.ServerInfo
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalStreamSocket
import app.maestri.wire.WireClient
import app.maestri.wire.WorkspaceMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * App-level session over Maestri Wire: owns the pairing store, the pinned HTTP client,
 * the live feed socket and the terminal stream socket. UI screens observe this class.
 */
class WireSession(context: Context) {

    val store = PairingStore(context)
    val client = WireClient()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing

    /** Persistent 401 → token revoked/forgotten → return to pairing UX. */
    /**
     * Stable identity for local per-server state (persisted camera, follow-Mac): the pinned SPKI
     * hash — our closest analogue to the iOS `serverId`, and stable across host address changes.
     */
    val serverKey: String
        get() = _pairing.value?.serverKeyHashB64?.takeIf { it.isNotBlank() }
            ?: _pairing.value?.let { "${it.host}:${it.port}" }
            ?: "unknown"

    private val _authLost = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val authLost: SharedFlow<String> = _authLost

    val feed = FeedSocket(
        clientProvider = {
            _pairing.value?.let { p -> client.pinnedForSocket(p.serverKeyHashB64) } ?: okhttp3.OkHttpClient()
        },
        scope = scope,
    )
    val terminalStream = TerminalStreamSocket(
        clientProvider = {
            _pairing.value?.let { p -> client.pinnedForSocket(p.serverKeyHashB64) } ?: okhttp3.OkHttpClient()
        },
        scope = scope,
    )

    @Volatile
    var serverInfo: ServerInfo? = null
        private set

    init {
        scope.launch {
            store.pairing.collect { p ->
                _pairing.value = p
                if (p != null) client.pinned(p.serverKeyHashB64)
                feed.selfDeviceId = p?.deviceId ?: ""
                feed.selfLabel = p?.deviceName?.takeIf { it.isNotBlank() } ?: "You"
            }
        }
        scope.launch {
            store.snapshot()?.let {
                runCatching {
                    client.pinned(it.serverKeyHashB64)
                    serverInfo = client.api(it.host, it.port, "api/info", it.token).let(ServerInfo::parse)
                }
            }
        }
        feed.resync = {
            val p = _pairing.value
            val room = parseFeedRoom(feed.currentUrl)
            if (p == null || room == null) null
            else try {
                feedOnce(p, room.first, room.second)
            } catch (e: ApiException) {
                if (e.status == 401) onAuthLost()
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Parse ws/floor out of the current feed socket URL for the epoch-change resync. */
    private fun parseFeedRoom(url: String?): Pair<String, String?>? {
        if (url.isNullOrBlank()) return null
        val q = url.substringAfter('?', "")
        fun param(name: String): String? =
            q.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        val ws = param("ws") ?: return null
        return ws to param("floor")
    }

    fun onAuthLost() {
        // Persistent 401: stop trying (per contract), return to pairing UX.
        android.util.Log.d("QAAuth", "persistent 401 -> closing sockets, re-pair UX")
        feed.close()
        terminalStream.close()
        _authLost.tryEmit("Device token revoked or forgotten on the host — pair again.")
    }

    /** Forget only the credential (re-pair UX); keep host + security key. */
    suspend fun forgetToken() {
        feed.close()
        terminalStream.close()
        store.forgetToken()
        serverInfo = null
    }

    /** Full unpair ("forget this server"). */
    suspend fun forgetEverything() {
        feed.close()
        terminalStream.close()
        store.clearAll()
        serverInfo = null
    }

    // ------------------------------------------------------------------ discovery / pairing

    /** First contact: GET /api/info + the presented key (pin candidate). Nothing stored. */
    suspend fun probe(host: String, port: Int): Pair<ServerInfo, String> =
        WireClient.probeInfo(host, port)

    /** POST /pair with the 6-digit code (or the Manual-tab password), then store. */
    suspend fun pair(
        host: String,
        port: Int,
        deviceName: String,
        code: String?,
        password: String?,
    ): ServerInfo {
        val resp = WireClient.pair(host, port, deviceName, code, password)
        return adopt(
            host = host,
            port = port,
            deviceName = deviceName,
            token = resp.getString("token"),
            deviceId = resp.optString("deviceId"),
        )
    }

    /** Store a token (from pairing, or owner-token reuse in the advanced path). */
    suspend fun adopt(
        host: String,
        port: Int,
        deviceName: String,
        token: String,
        deviceId: String,
    ): ServerInfo {
        val presentedPin = probe(host, port).second
        val info = try {
            client.pinned(presentedPin)
            ServerInfo.parse(client.api(host, port, "api/info", token))
        } catch (e: ApiException) {
            if (e.status == 401) onAuthLost()
            throw e
        }
        if (info.protocolVersion != 1) {
            throw ApiException(0, "protocolVersion", "protocolVersion ${info.protocolVersion} != 1")
        }
        // hosts[] from info join the stored alternate list for failover.
        val alternates = info.hosts.filter { it != host }
        store.save(
            host = host,
            port = port,
            serverKeyHashB64 = presentedPin,
            token = token,
            deviceId = deviceId,
            deviceName = deviceName,
            hosts = alternates,
        )
        serverInfo = info
        return info
    }

    // ------------------------------------------------------------------ API

    private suspend fun <T> apiGuard(block: suspend (Pairing) -> T): T {
        val p = _pairing.value ?: throw ApiException(0, "unauthorized", "not paired")
        try {
            return block(p)
        } catch (e: ApiException) {
            if (e.status == 401) onAuthLost()
            throw e
        }
    }

    suspend fun workspaces(): List<WorkspaceMeta> = apiGuard { p ->
        val resp = client.api(p.host, p.port, "api/workspaces", p.token)
        val arr = resp.optJSONArray("workspaces")
            ?: return@apiGuard emptyList()
        (0 until arr.length()).mapNotNull { idx ->
            runCatching { WorkspaceMeta.parse(arr.getJSONObject(idx)) }.getOrNull()
        }
    }

    suspend fun feedOnce(p: Pairing, wsId: String, floor: String?): FeedSnapshot {
        val path = "api/workspaces/$wsId/feed" + (floor?.let { "?floor=$it" } ?: "")
        return FeedSnapshot.parse(client.api(p.host, p.port, path, p.token), -1)
    }

    suspend fun terminalPost(terminalId: String, op: String, body: JSONObject? = null): JSONObject =
        apiGuard { p ->
            client.api(p.host, p.port, "api/terminals/$terminalId/$op", p.token, "POST", body)
        }

    suspend fun prompt(terminalId: String, text: String) =
        terminalPost(terminalId, "prompt", JSONObject().put("text", text))

    suspend fun approve(terminalId: String) = loggedTerminalOp(terminalId, "approve")
    suspend fun reject(terminalId: String) = loggedTerminalOp(terminalId, "reject")

    /** QA instrumentation: proves whether the approve/reject request actually leaves the app. */
    private suspend fun loggedTerminalOp(terminalId: String, op: String): JSONObject {
        android.util.Log.d("QAFeed", "POST api/terminals/$terminalId/$op -> sending")
        return try {
            terminalPost(terminalId, op).also {
                android.util.Log.d("QAFeed", "POST api/terminals/$terminalId/$op -> answer $it")
            }
        } catch (e: Exception) {
            android.util.Log.e("QAFeed", "POST api/terminals/$terminalId/$op -> failed: $e")
            throw e
        }
    }

    /** `POST …/seen` on terminal open (clears attention on the host badge). */
    suspend fun seen(terminalId: String) = runCatching { terminalPost(terminalId, "seen") }.isSuccess

    // ------------------------------------------------------------------ live feeds

    fun feedUrlFor(wsId: String, floor: String?): String {
        val p = _pairing.value ?: return ""
        return "wss://${p.host}:${p.port}/api/feed/stream?ws=$wsId" +
            (floor?.let { "&floor=$it" } ?: "") +
            "&token=${p.token}"
    }

    fun terminalStreamUrlFor(terminalId: String): String {
        val p = _pairing.value ?: return ""
        return "wss://${p.host}:${p.port}/api/terminals/$terminalId/stream?token=${p.token}"
    }

    fun openFeed(wsId: String, floor: String?) {
        feed.open(feedUrlFor(wsId, floor))
    }

    fun openTerminalStream(terminalId: String) {
        terminalStream.open(terminalStreamUrlFor(terminalId))
    }

    /** Optimistic overlay for canvas writes (frames/removals awaiting the host's snapshot). */
    val canvasOptimism = app.maestri.wire.CanvasOptimism()

    // ------------------------------------------------------------------ canvas writes
    //
    // Every canvas route lives under the workspace. Bodies are built by CanvasWrites (path + body
    // shapes recovered from the host); a failure surfaces to the caller — canvas writes are never
    // retried automatically, because an ambiguous outcome must not be sent twice.

    suspend fun canvasWrite(
        path: String,
        body: JSONObject? = null,
        method: String = "POST",
    ): JSONObject = apiGuard { p -> client.api(p.host, p.port, path, p.token, method, body) }

    val feedState: StateFlow<SocketState> get() = feed.state
    val feedSnapshot: StateFlow<FeedSnapshot?> get() = feed.snapshot
}