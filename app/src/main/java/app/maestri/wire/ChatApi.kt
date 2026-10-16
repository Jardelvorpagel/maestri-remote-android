package app.maestri.wire

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Terminal chat (capability `terminalChat`) — the second face of a terminal.
 *
 * Truth: wire-docs.txt §"Chat do terminal" + schema doc §3 + behavior spec §3.1–3.8.
 *
 * Contract rules honored here:
 *  - `GET …/chat` → ChatSnapshot; `WS …/chat/stream?token=` → snapshot-REPLACEMENT
 *    protocol (`{"type":"chat","snapshot":…}`): a snapshot is the whole truth for the
 *    conversation — frames are never merged as append events.
 *  - ORDERING: an HTTP GET snapshot must never overwrite a newer socket snapshot. The
 *    socket counts receives; the holder adopts HTTP results only as seeds.
 *  - Tail is seeded with the 80 newest and never front-trimmed while open; paging via
 *    `POST …/chat/history {conversationId, beforeEntryId}` → `{ok}` — the page (60)
 *    arrives in the NEXT socket snapshot, never in the HTTP response.
 *  - `hasOlderOnRecord` is tri-state: true → offer the paging POST; false → boundary
 *    marker; ABSENT → old host: the retained transcript lives behind `GET …/chat/history`.
 *  - Sends: `POST …/prompt {segments:[{text}|{attachmentId}], clientMessageId?}` —
 *    clientMessageId correlates the local send with the host-assigned entry id; it is
 *    NOT an idempotency token. AMBIGUOUS SENDS ARE NEVER RE-SENT by this layer.
 *  - Staging: `POST …/attachments?filename=` (raw bytes + Content-Type) →
 *    `{attachmentId, fileName, mimeType, expiresAt}` (15 min, ≤8 MiB, ≤64/draft);
 *    `DELETE …/attachments/{attachmentId}` discards.
 *  - Threads: `PUT …/chat/thread {slot}` selects (400 invalid slot, 409 locked),
 *    `DELETE …/chat/thread {slot}` clears a slot without selecting (same locks),
 *    `GET …/chat/threads` read-only previews (40 newest each). Absent `threads` =
 *    single-conversation host; callers must not offer thread controls.
 *  - One chat stream per terminal per session; close on background, reconnect on return.
 */
class ChatApi(private val session: app.maestri.wire.session.WireSession) {

    // ------------------------------------------------------------------ HTTP surface

    suspend fun chatGet(terminalId: String): ChatSnapshot =
        api("GET", "api/terminals/$terminalId/chat").let { Companion.parseChatSnapshot(it) }

    /** PUT …/chat {enabled, showsShellWindow?} → ChatSnapshot (face toggle; history preserved). */
    suspend fun chatSetFace(terminalId: String, enabled: Boolean, showsShellWindow: Boolean?): ChatSnapshot {
        val body = JSONObject().put("enabled", enabled)
        if (showsShellWindow != null) body.put("showsShellWindow", showsShellWindow)
        return api("PUT", "api/terminals/$terminalId/chat", body).let { Companion.parseChatSnapshot(it) }
    }

    /** PUT …/chat/thread {slot} — select one of the 7 fixed slots (0…6). */
    suspend fun selectThread(terminalId: String, slot: Int): ChatSnapshot =
        api("PUT", "api/terminals/$terminalId/chat/thread", JSONObject().put("slot", slot))
            .let { Companion.parseChatSnapshot(it) }

    /** DELETE …/chat/thread {slot} — clear a slot without selecting it. */
    suspend fun clearThread(terminalId: String, slot: Int) {
        api("DELETE", "api/terminals/$terminalId/chat/thread", JSONObject().put("slot", slot))
    }

    /** GET …/chat/threads — previews of the OTHER non-empty threads (40 newest each). */
    suspend fun threadPreviews(terminalId: String): List<ChatThreadPreview> {
        val resp = api("GET", "api/terminals/$terminalId/chat/threads")
        val arr = resp.optJSONArray("threads") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            runCatching { ChatThreadPreview.parse(arr.getJSONObject(idx)) }.getOrNull()
        }
    }

    /** GET …/chat/history — the WHOLE retained transcript (old hosts / deep entry). */
    suspend fun historyAll(terminalId: String): Pair<String, List<ChatEntry>> {
        val resp = api("GET", "api/terminals/$terminalId/chat/history")
        val conversationId = resp.optString("conversationId", "")
        val entries = resp.optJSONArray("entries")
            ?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { ChatEntry.parse(a.getJSONObject(i)) }.getOrNull() } }
            ?: emptyList()
        return conversationId to entries
    }

    /**
     * POST …/chat/history {conversationId, beforeEntryId} — request the page BEFORE the
     * anchor. The page arrives in the NEXT SOCKET SNAPSHOT; this response is just {ok}.
     */
    suspend fun historyPage(terminalId: String, conversationId: String, beforeEntryId: String) {
        api(
            "POST", "api/terminals/$terminalId/chat/history",
            JSONObject().put("conversationId", conversationId).put("beforeEntryId", beforeEntryId),
        )
    }

    /** POST …/chat/read — mark read (clears unread on the host surface). */
    suspend fun markRead(terminalId: String) {
        api("POST", "api/terminals/$terminalId/chat/read")
    }

    /** DELETE …/chat/entries/{entryId} — durable delete; every observer's tail changes. */
    suspend fun deleteEntry(terminalId: String, entryId: String) {
        api("DELETE", "api/terminals/$terminalId/chat/entries/$entryId")
    }

    /** DELETE …/chat/history — clears Maestri's transcript + retained attachments (not agent memory). */
    suspend fun clearHistory(terminalId: String) {
        api("DELETE", "api/terminals/$terminalId/chat/history")
    }

    /** GET …/chat/attachments/{attachmentId} — retained bytes, UUID-addressed. 404 = gone. */
    suspend fun attachmentBytes(terminalId: String, attachmentId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val resp = raw("GET", "api/terminals/$terminalId/chat/attachments/$attachmentId", null, null)
            resp.use { it.body?.bytes() ?: ByteArray(0) }
        }

    /** DELETE …/chat/attachments/{attachmentId} — removes from every entry holding it. */
    suspend fun deleteChatAttachment(terminalId: String, attachmentId: String) {
        api("DELETE", "api/terminals/$terminalId/chat/attachments/$attachmentId")
    }

    // ------------------------------------------------------------------ composer sends

    /**
     * POST …/prompt with segments in composer order, one of {text}|{attachmentId} each,
     * plus a clientMessageId correlation UUID. NEVER retried here; ambiguity is surfaced
     * by the caller (Delivery Status Unknown).
     */
    suspend fun sendPrompt(
        terminalId: String,
        segments: List<Pair<String, *>>,   // ("text", String) | ("attachmentId", String)
        clientMessageId: String = UUID.randomUUID().toString(),
    ): String {
        val segs = JSONArray()
        for ((kind, value) in segments) {
            when (kind) {
                "text" -> {
                    if ((value as? String ?: "").isNotEmpty()) {
                        segs.put(JSONObject().put("text", value))
                    }
                }
                "attachmentId" -> segs.put(JSONObject().put("attachmentId", value.toString()))
            }
        }
        val body = JSONObject().put("segments", segs).put("clientMessageId", clientMessageId)
        api("POST", "api/terminals/$terminalId/prompt", body)
        return clientMessageId
    }

    // ------------------------------------------------------------------ attachment staging

    /**
     * POST …/attachments?filename= — RAW bytes + Content-Type →
     * {attachmentId, fileName, mimeType, expiresAt}. Host enforces ≤8 MiB / ≤64 per draft.
     */
    suspend fun uploadAttachment(
        terminalId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StagedAttachment = withContext(Dispatchers.IO) {
        val p = session.pairing.value ?: throw ApiException(0, "unauthorized", "not paired")
        val url = HttpUrl.Builder()
            .scheme("https").host(p.host).port(p.port)
            .addPathSegments("api/terminals/$terminalId/attachments")
            .addQueryParameter("filename", fileName)
            .build()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${p.token}")
            .post(
                bytes.toRequestBody(
                    runCatching { mimeType.toMediaType() }
                        .getOrDefault("application/octet-stream".toMediaType())
                )
            )
            .build()
        session.client.pinned(p.serverKeyHashB64).newCall(req).execute().use { resp: Response ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) { session.onAuthLost(); throw parseWireError(401, text) }
            if (!resp.isSuccessful) throw parseWireError(resp.code, text)
            StagedAttachment.parse(JSONObject(text), fileName)
        }
    }

    /** DELETE …/attachments/{attachmentId} — discard a staged upload. */
    suspend fun discardStaged(terminalId: String, attachmentId: String) {
        api("DELETE", "api/terminals/$terminalId/attachments/$attachmentId")
    }

    // ------------------------------------------------------------------ mentions + files

    /** GET …/mentions → {mentions:[Mention]} — agent/floor/note/portal/maestro + actions. */
    suspend fun mentions(terminalId: String): List<Mention> {
        val resp = api("GET", "api/terminals/$terminalId/mentions")
        val arr = resp.optJSONArray("mentions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            runCatching { Mention.parse(arr.getJSONObject(idx)) }.getOrNull()
        }
    }

    /**
     * GET …/files?query=&scope=&limit= — the host composer's own file index rooted at the
     * terminal's working directory. 404 when the terminal has no readable directory.
     */
    suspend fun fileSuggestions(
        terminalId: String,
        query: String,
        scope: String = "",
        limit: Int = 200,
    ): FileSuggestions {
        val resp = api(
            "GET",
            "api/terminals/$terminalId/files?query=${urlEnc(query)}&scope=${urlEnc(scope)}&limit=$limit",
        )
        return FileSuggestions.parse(resp)
    }

    // ------------------------------------------------------------------ private plumbing

    /** JSON REST (host, port, token) with 401 → the session's re-pair UX. */
    private suspend fun api(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val p = session.pairing.value
            ?: throw ApiException(0, "unauthorized", "not paired")
        try {
            session.client.api(p.host, p.port, path, p.token, method, body)
        } catch (e: ApiException) {
            if (e.status == 401) session.onAuthLost()
            throw e
        }
    }

    /** Raw-bytes REST on the pinned client (attachment GET/POST does not speak JSON). */
    private fun raw(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
    ): Response {
        val p = session.pairing.value ?: throw ApiException(0, "unauthorized", "not paired")
        val url = HttpUrl.Builder()
            .scheme("https").host(p.host).port(p.port)
            .addPathSegments(path.trimStart('/'))
            .build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${p.token}")
        when (method.uppercase()) {
            "POST" -> builder.post(
                (body ?: ByteArray(0)).toRequestBody(
                    runCatching { (contentType ?: "application/octet-stream").toMediaType() }
                        .getOrDefault("application/octet-stream".toMediaType())
                )
            )
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
        }
        return session.client.pinned(p.serverKeyHashB64).newCall(builder.build()).execute()
    }

    private fun urlEnc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        fun parseChatSnapshot(o: JSONObject): ChatSnapshot {
            val threads = o.optJSONObject("threads")
                ?.let { runCatching { ChatThreads.parse(it) }.getOrNull() }
            return ChatSnapshot(
                terminalId = o.optString("terminalId", ""),
                conversationId = o.optString("conversationId", ""),
                enabled = o.optBoolean("enabled", false),
                status = o.optString("status", "idle"),
                entries = o.optJSONArray("entries")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { ChatEntry.parse(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList(),
                unreadCount = o.optInt("unreadCount", 0),
                canClear = o.optBoolean("canClear", false),
                talkingToAgentName = o.optStringOrNull("talkingToAgentName"),
                answeringAgentName = o.optStringOrNull("answeringAgentName"),
                threads = threads,
                hasOlderOnRecord = o.optBooleanOrNull("hasOlderOnRecord"),
                turnInterrupted = if (o.has("turnInterrupted")) o.optBooleanOrNull("turnInterrupted") else null,
                showsShellWindow = o.optBooleanOrNull("showsShellWindow"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "") else null

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key) else null
    }
}

// -------------------------------------------------------------------------- models

/** status: idle | working | needsAttention | notRunning | unloaded (open string). */
data class ChatSnapshot(
    val terminalId: String,
    val conversationId: String,
    val enabled: Boolean,
    val status: String,
    val entries: List<ChatEntry>,
    val unreadCount: Int,
    val canClear: Boolean,
    val talkingToAgentName: String?,
    val answeringAgentName: String?,
    val threads: ChatThreads?,          // absent = single-conversation host
    val hasOlderOnRecord: Boolean?,     // tri-state: true / false / ABSENT (old host)
    val turnInterrupted: Boolean?,      // rides ALONGSIDE status, not instead of it
    val showsShellWindow: Boolean?,     // absent = a host that always opened the shell window
)

/**
 * ChatEntry. `text` is canonical (no delivery envelope). User segments preserve composer
 * order, each exactly one of {"text":…} | {"attachment":{…}}; agent/divider entries ride
 * on text with an empty segment list. ids are UUID strings, echoed verbatim.
 */
data class ChatEntry(
    val id: String,
    val role: String,               // user | agent | (open)
    val ts: Long?,                  // epoch millis when the host sends it
    val kind: String,               // message | progress | final | divider | (open)
    val text: String,
    val segments: List<ChatSegment>,
    val authorName: String?,
    val agentType: String?,
    val clientMessageId: String?,   // correlation key for optimistic sends
) {
    val isUser: Boolean get() = role == "user"

    companion object {
        fun parse(o: JSONObject): ChatEntry {
            val segs = mutableListOf<ChatSegment>()
            val arr = o.optJSONArray("segments")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.has("text")) {
                        segs.add(ChatSegment.Text(s.optString("text", "")))
                    } else if (s.has("attachment")) {
                        val a = s.optJSONObject("attachment") ?: continue
                        segs.add(
                            ChatSegment.Attachment(
                                id = a.optString("id", ""),
                                isImage = a.optBoolean("isImage", false),
                                filename = a.optString("filename", ""),
                                contentType = if (a.has("contentType") && !a.isNull("contentType"))
                                    a.optString("contentType", "") else null,
                            )
                        )
                    }
                }
            }
            return ChatEntry(
                id = o.getString("id"),
                role = o.optString("role", ""),
                ts = if (o.has("ts") && !o.isNull("ts")) o.optLong("ts") else null,
                kind = o.optString("kind", "message"),
                text = o.optString("text", ""),
                segments = segs,
                authorName = o.optStringOrNull("authorName"),
                agentType = o.optStringOrNull("agentType"),
                clientMessageId = o.optStringOrNull("clientMessageId"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "") else null
    }
}

sealed interface ChatSegment {
    data class Text(val text: String) : ChatSegment
    data class Attachment(
        val id: String,
        val isImage: Boolean,
        val filename: String,
        val contentType: String?,
    ) : ChatSegment
}

/** threads: {activeSlot, canSelect, items:[{id, color, hasMessages}]} — 7 stable slots 0…6. */
data class ChatThreads(
    val activeSlot: Int,
    val canSelect: Boolean,
    val items: List<ChatThreadSlot>,
) {
    companion object {
        fun parse(o: JSONObject): ChatThreads = ChatThreads(
            activeSlot = o.optInt("activeSlot", 0),
            canSelect = o.optBoolean("canSelect", true),
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    val s = a.optJSONObject(idx) ?: return@mapNotNull null
                    ChatThreadSlot(
                        id = s.optInt("id", 0),
                        color = if (s.has("color") && !s.isNull("color")) s.optString("color", "graphite") else "graphite",
                        hasMessages = s.optBoolean("hasMessages", false),
                    )
                }
            } ?: emptyList(),
        )
    }
}

data class ChatThreadSlot(val id: Int, val color: String, val hasMessages: Boolean)

/** GET …/chat/threads previews: the OTHER threads' 40 newest entries each. */
data class ChatThreadPreview(
    val id: Int,
    val conversationId: String,
    val entries: List<ChatEntry>,
) {
    companion object {
        fun parse(o: JSONObject): ChatThreadPreview = ChatThreadPreview(
            id = o.optInt("id", 0),
            conversationId = o.optString("conversationId", ""),
            entries = o.optJSONArray("entries")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { ChatEntry.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList(),
        )
    }
}

/**
 * Mention: kind (agent | floor | note | portal | maestro | createNote… — tolerate others),
 * name, tokenName?, floorName, isOnActiveFloor, section + ordering keys.
 */
data class Mention(
    val kind: String,
    val id: String?,
    val name: String,
    val tokenName: String?,
    val icon: String?,
    val floorId: String?,
    val floorName: String?,
    val isOnActiveFloor: Boolean,
    val section: String?,
    val sectionOrder: Int,
    val order: Int,
) {
    companion object {
        fun parse(o: JSONObject): Mention = Mention(
            kind = o.optString("kind", ""),
            id = o.optStringOrNull("id"),
            name = o.optString("name", ""),
            tokenName = o.optStringOrNull("tokenName"),
            icon = o.optStringOrNull("icon"),
            floorId = o.optStringOrNull("floorId"),
            floorName = o.optStringOrNull("floorName"),
            isOnActiveFloor = o.optBoolean("isOnActiveFloor", false),
            section = o.optStringOrNull("section"),
            sectionOrder = o.optInt("sectionOrder", 0),
            order = o.optInt("order", 0),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "") else null
    }
}

data class FileSuggestion(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
)

data class FileSuggestions(
    val query: String,
    val root: String,
    val entries: List<FileSuggestion>,
    val isTruncated: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): FileSuggestions = FileSuggestions(
            query = o.optString("query", ""),
            root = o.optString("root", ""),
            entries = o.optJSONArray("entries")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    val e = a.optJSONObject(idx) ?: return@mapNotNull null
                    FileSuggestion(
                        relativePath = e.optString("relativePath", ""),
                        name = e.optString("name", ""),
                        isDirectory = e.optBoolean("isDirectory", false),
                    )
                }
            } ?: emptyList(),
            isTruncated = o.optBoolean("isTruncated", false),
        )
    }
}

/** {attachmentId, fileName, mimeType, expiresAt} — the 15-min staging ticket. */
data class StagedAttachment(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val expiresAtMs: Long?,          // ISO 8601 → epoch millis (lenient)
) {
    val isExpired: Boolean
        get() = expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()

    companion object {
        fun parse(o: JSONObject, fallbackName: String): StagedAttachment = StagedAttachment(
            attachmentId = o.optString("attachmentId", ""),
            fileName = o.optString("fileName", "").ifBlank { fallbackName },
            mimeType = o.optString("mimeType", "application/octet-stream"),
            expiresAtMs = o.optStringOrNull("expiresAt")?.let(::parseIsoLenient),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "") else null

        /** ISO 8601 dates are plain strings on the wire; best-effort epoch conversion. */
        private fun parseIsoLenient(value: String): Long? = runCatching {
            java.time.Instant.parse(value).toEpochMilli()
        }.getOrNull()
    }
}

// --------------------------------------------------------------- chat stream socket

/**
 * `WS /api/terminals/{id}/chat/stream?token=…` — an immediate
 * `{"type":"chat","snapshot":ChatSnapshot}` frame, then a snapshot substitute whenever
 * the observed state changes. Snapshot replacement: the newest frame REPLACES state.
 * One stream per terminal per session; the UI owns the open/close lifecycle.
 */
class ChatStreamSocket(
    clientProvider: () -> OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val socketClientProvider: () -> OkHttpClient = clientProvider
    private val socketClient: OkHttpClient get() = socketClientProvider()

    private val _snapshot = MutableStateFlow<ChatSnapshot?>(null)
    val snapshot: StateFlow<ChatSnapshot?> = _snapshot

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _receiveOrder = MutableStateFlow(0L)
    val receiveOrder: StateFlow<Long> = _receiveOrder

    private var ws: WebSocket? = null
    private var closedByUs = false
    private var backoffAttempts = 0
    private var url: String? = null
    private var pingJob: Job? = null

    fun open(targetUrl: String) {
        closedByUs = false
        url = targetUrl
        backoffAttempts = 0
        if (_snapshot.value == null) _state.value = SocketState.CONNECTING
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
    }

    /**
     * HTTP seed: adopt a GET …/chat snapshot ONLY while the socket has delivered nothing
     * yet — the socket's immediate frame wins the race once it exists. Guards the
     * HTTP-never-overwrites-socket ordering rule at the boundary of boot.
     */
    fun seedIfNothing(observed: ChatSnapshot) {
        if (_receiveOrder.value == 0L && _snapshot.value == null) {
            _snapshot.value = observed
            _receiveOrder.value = 1L
        }
    }

    private fun connect() {
        if (closedByUs) return
        val target = url ?: return
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
                            "chat" -> {
                                val snap = msg.optJSONObject("snapshot") ?: return
                                _snapshot.value = ChatApi.parseChatSnapshot(snap)
                                _receiveOrder.value = _receiveOrder.value + 1
                            }
                            "pong" -> Unit
                            else -> Unit // unknown ignored per contract
                        }
                    } catch (e: Exception) {
                        // Unparseable frame; contract says ignore.
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closedByUs) return
                    _state.value = SocketState.CLOSED
                    _error.value = t.message ?: "chat stream failed"
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
}

// ------------------------------------------------------------------ composer drafts

/**
 * Per-conversation composer drafts (iOS pref shape `remote.composer.draft.<id>`): text +
 * staged attachments survive restarts; send failures explicitly preserve the draft.
 * Staged local copies live in the app cache so they survive process death too.
 */
class ChatDraftStore(context: Context, private val serverKey: () -> String) {

    private val prefs = context.getSharedPreferences("maestri-chat", Context.MODE_PRIVATE)

    fun key(conversationId: String, fallbackTerminalId: String): String {
        val sk = serverKey().removePrefix("unknown").ifBlank { "unknown" }
        val conv = conversationId.ifBlank { fallbackTerminalId }
        return "remote.composer.draft.$sk.$conv"
    }

    data class Draft(
        val text: String,
        val staged: List<DraftStaged>,
    ) {
        val isEmpty: Boolean get() = text.isBlank() && staged.isEmpty()
    }

    data class DraftStaged(
        val localPath: String,      // copy inside cacheDir/composer-draft
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    fun load(conversationId: String, fallbackTerminalId: String): Draft = runCatching {
        val rawStr = prefs.getString(key(conversationId, fallbackTerminalId), null) ?: return Draft("", emptyList())
        val o = JSONObject(rawStr)
        val staged = o.optJSONArray("staged")?.let { a ->
            (0 until a.length()).mapNotNull { idx ->
                val s = a.optJSONObject(idx) ?: return@mapNotNull null
                DraftStaged(
                    localPath = s.optString("localPath", ""),
                    fileName = s.optString("fileName", ""),
                    mimeType = s.optString("mimeType", "application/octet-stream"),
                    sizeBytes = s.optLong("sizeBytes", 0),
                )
            }
        } ?: emptyList()
        Draft(o.optString("text", ""), staged)
    }.getOrDefault(Draft("", emptyList()))

    fun save(conversationId: String, fallbackTerminalId: String, draft: Draft) {
        val k = key(conversationId, fallbackTerminalId)
        if (draft.isEmpty) {
            prefs.edit().remove(k).apply()
            return
        }
        val o = JSONObject()
            .put("text", draft.text)
            .put("staged", JSONArray().apply {
                draft.staged.forEach { s ->
                    put(
                        JSONObject()
                            .put("localPath", s.localPath)
                            .put("fileName", s.fileName)
                            .put("mimeType", s.mimeType)
                            .put("sizeBytes", s.sizeBytes)
                    )
                }
            })
        prefs.edit().putString(k, o.toString()).apply()
    }

    fun clear(conversationId: String, fallbackTerminalId: String) {
        prefs.edit().remove(key(conversationId, fallbackTerminalId)).apply()
    }
}