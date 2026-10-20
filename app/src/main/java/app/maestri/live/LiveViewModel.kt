package app.maestri.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

object LiveKinds {
    const val USER_MESSAGE = "user_message"
    const val ASSISTANT_MESSAGE = "assistant_message"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val RUN_START = "run_start"
    const val RUN_END = "run_end"
    const val ERROR = "error"
    const val NOTICE = "notice"
    const val APPROVAL_REQUEST = "approval_request"
    const val APPROVAL_RESOLVED = "approval_resolved"
    const val SEND_ACK = "send_ack"

    val known: Set<String> = setOf(
        USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL, TOOL_RESULT,
        RUN_START, RUN_END, ERROR, NOTICE,
        APPROVAL_REQUEST, APPROVAL_RESOLVED, SEND_ACK,
    )
}

const val MAX_ATTACH_BYTES = 8L * 1024 * 1024
const val MAX_STAGED_FILES = 8
const val SEND_ACK_TIMEOUT_MS = 15_000L

data class LiveDecision(
    val approved: Boolean,
    val rememberSession: Boolean,
)

enum class LiveSendStatus { SENDING, FAILED }

data class LiveStagedFile(
    val key: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
    val uploading: Boolean = false,
    val attachmentId: String? = null,
)

data class LivePendingAttachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val attachmentId: String?,
    val bytes: ByteArray?,
)

data class LivePendingSend(
    val clientMsgId: String,
    val text: String,
    val attachments: List<LivePendingAttachment>,
    val status: LiveSendStatus,
    val detail: String = "",
    val createdAtMs: Long = 0L,
)

/**
 * Watchdog sweep (pure): no SENDING row may outlive [timeoutMs] — any stall the
 * per-send timeout missed still lands FAILED with a Retry hint instead of
 * sitting on screen as an unreadable stuck bubble.
 */
fun sweepStalePending(
    current: List<LivePendingSend>,
    nowMs: Long,
    timeoutMs: Long = SEND_ACK_TIMEOUT_MS,
): List<LivePendingSend> =
    current.map {
        if (it.status == LiveSendStatus.SENDING && nowMs - it.createdAtMs > timeoutMs) {
            it.copy(status = LiveSendStatus.FAILED, detail = "no send_ack from the relay — tap retry")
        } else it
    }

sealed interface LiveComposeState {
    data class Disabled(val reason: String) : LiveComposeState
    data object Ready : LiveComposeState
}

interface PiRelayStore {
    val url: StateFlow<String>
    val token: StateFlow<String>
    fun setUrl(value: String)
    fun setToken(value: String)
}

class InMemoryPiRelayStore : PiRelayStore {
    private val _url = MutableStateFlow("")
    override val url: StateFlow<String> = _url
    private val _token = MutableStateFlow("")
    override val token: StateFlow<String> = _token
    override fun setUrl(value: String) { _url.value = value }
    override fun setToken(value: String) { _token.value = value }
}

class LiveViewModel(
    private val store: PiRelayStore,
    private val clientFactory: (url: String, token: String) -> PiRelayClient = { url, token -> PiRelayClient(url, token) },
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    val url: StateFlow<String> = store.url
    val token: StateFlow<String> = store.token

    private val _relayState = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val relayState: StateFlow<RelayState> = _relayState

    private val _events = MutableStateFlow<List<PiEvent>>(emptyList())
    val events: StateFlow<List<PiEvent>> = _events

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following

    private val _newWhileAway = MutableStateFlow(0)
    val newWhileAway: StateFlow<Int> = _newWhileAway

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private var client: PiRelayClient? = null
    private var collectJob: Job? = null
    private var lastDeliveredSeq: Long = -1L

    private val uploader by lazy { PiRelayUpload() }

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    private val _staged = MutableStateFlow<List<LiveStagedFile>>(emptyList())
    val staged: StateFlow<List<LiveStagedFile>> = _staged

    private val _pending = MutableStateFlow<List<LivePendingSend>>(emptyList())
    val pending: StateFlow<List<LivePendingSend>> = _pending

    private val _composeState = MutableStateFlow<LiveComposeState>(
        LiveComposeState.Disabled("Connect the relay to send.")
    )
    val composeState: StateFlow<LiveComposeState> = _composeState

    private val _decisions = MutableStateFlow<Map<String, LiveDecision>>(emptyMap())
    val decisions: StateFlow<Map<String, LiveDecision>> = _decisions

    private val _expiredApprovals = MutableStateFlow<Set<String>>(emptySet())
    val expiredApprovals: StateFlow<Set<String>> = _expiredApprovals

    private val _approvals = MutableStateFlow<Map<String, Approval>>(emptyMap())
    val approvals: StateFlow<Map<String, Approval>> = _approvals

    private val _decisionPrompt = MutableStateFlow<DecisionPrompt?>(null)
    val decisionPrompt: StateFlow<DecisionPrompt?> = _decisionPrompt

    private val _autoRetry = MutableStateFlow(false)
    val autoRetry: StateFlow<Boolean> = _autoRetry

    init {
        refreshComposeState()
    }

    private fun ioScope(): CoroutineScope = scope ?: viewModelScope

    val configured: Boolean get() = store.url.value.isNotBlank()

    fun setUrl(value: String) {
        store.setUrl(value.trim())
        if (value.isBlank()) disconnect()
        refreshComposeState()
    }

    fun setToken(value: String) {
        store.setToken(value.trim())
    }

    fun connect() {
        val urlValue = store.url.value
        if (urlValue.isBlank()) {
            _notice.value = "Set the relay URL first — no connection attempted."
            return
        }
        // Single-flight (device-proven 275ms double-dial): a duplicate effect firing
        // or an auto+manual race must never interrupt an attempt already in flight.
        if (!shouldRedial(client != null, _relayState.value)) return
        if (client != null) {
            // Re-dial path, Error only: a dead client (e.g. after an auth failure,
            // which sets no retry) must not make a later Connect tap a no-op. Tear
            // down first so the tap always dials; the button only offers Connect
            // when not open, and single-flight above protects in-flight attempts.
            disconnect()
        }
        _notice.value = null
        val made = clientFactory(urlValue, store.token.value)
        client = made
        _relayState.value = RelayState.Connecting
        collectJob = (scope ?: viewModelScope).launch {
            launch { made.state.collect {
                _relayState.value = it
                // Fresh session, old complaints moot: error-era notices clear the
                // moment the socket opens (the error strip is state-driven too).
                if (it is RelayState.Open) _notice.value = null
            } }
            launch { made.commandsSupported.collect { refreshComposeState() } }
            launch { made.approvals.collect { _approvals.value = it } }
            launch { made.decisionPrompt.collect { _decisionPrompt.value = it } }
            launch { made.autoRetry.collect { _autoRetry.value = it } }
            launch {
                made.sendOutcomes.collect { map ->
                    map.values.forEach { outcome ->
                        val row = _pending.value.firstOrNull { it.clientMsgId == outcome.clientMsgId }
                            ?: return@forEach
                        if (row.status != LiveSendStatus.SENDING) return@forEach
                        if (outcome.accepted) {
                            _pending.value = _pending.value - row
                            if (_draft.value == row.text) _draft.value = ""
                        } else {
                            markFailed(outcome.clientMsgId, outcome.detail.ifBlank { "rejected by the relay" })
                        }
                    }
                }
            }
            launch {
                made.events.collect { list ->
                    val wasFollowing = _following.value
                    if (!wasFollowing && list.size > _events.value.size) {
                        _newWhileAway.value += list.size - _events.value.size
                    }
                    _events.value = list
                    list.lastOrNull()?.seq?.let { lastDeliveredSeq = it }
                    reconcilePending(list)
                }
            }
            // Watchdog ticker: bounds any SENDING stall to timeout + tick even when
            // no events or acks arrive to trigger the collectors above. Dies with
            // the session (collectJob scope); the per-send delay stays the fast path.
            launch {
                while (true) {
                    delay(5_000L)
                    if (_pending.value.any { it.status == LiveSendStatus.SENDING }) {
                        _pending.value = sweepStalePending(_pending.value, System.currentTimeMillis())
                    }
                }
            }
        }
        if (lastDeliveredSeq >= 0) runCatching { made.resumeAfter(lastDeliveredSeq) }
        made.connect()
    }

    fun disconnect() {
        collectJob?.cancel()
        collectJob = null
        _decisionPrompt.value = null
        runCatching { client?.disconnect() }
        client = null
        if (_relayState.value != RelayState.Disconnected) {
            _relayState.value = RelayState.Disconnected
        }
    }

    fun setFollowing(value: Boolean) {
        _following.value = value
        if (value) _newWhileAway.value = 0
    }

    fun resumeTail() {
        _following.value = true
        _newWhileAway.value = 0
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun note(text: String) {
        _notice.value = text
    }

    fun updateDraft(value: String) {
        _draft.value = value
    }

    fun stageFiles(files: List<LiveStagedFile>) {
        val over = files.filter { it.sizeBytes > MAX_ATTACH_BYTES }
        val fitting = files.filter { it.sizeBytes <= MAX_ATTACH_BYTES }
        val room = (MAX_STAGED_FILES - _staged.value.size).coerceAtLeast(0)
        _staged.value = _staged.value + fitting.take(room)
        val tooMany = fitting.drop(room).map { it.name }
        val bits = mutableListOf<String>()
        if (over.isNotEmpty()) bits += "over 8 MB: " + over.map { it.name }.take(3).joinToString(", ")
        if (tooMany.isNotEmpty()) bits += "room for 8 max: " + tooMany.take(3).joinToString(", ")
        if (bits.isNotEmpty()) _notice.value = "Not attached — " + bits.joinToString("; ") + "."
    }

    fun removeStaged(key: String) {
        _staged.value = _staged.value.filter { it.key != key }
    }

    fun send() {
        val gate = _composeState.value
        if (gate is LiveComposeState.Disabled) {
            _notice.value = gate.reason
            return
        }
        val target = client
        if (target == null || _relayState.value !is RelayState.Open) {
            _notice.value = "Not connected — connect first, then send."
            return
        }
        val text = _draft.value.trim()
        if (text.isEmpty() && _staged.value.isEmpty()) return
        ioScope().launch {
            val attachments = uploadStaged() ?: return@launch
            _staged.value = emptyList()
            enqueueText(text, attachments)
        }
    }

    /**
     * Decision-banner shortcut: sends the tapped option label as a NORMAL chat
     * message through the same optimistic + ack + retry path as typed sends —
     * no new API, no approval semantics. While any send is in flight the banner
     * disables its buttons; the ack reconciles the row like a typed message.
     */
    fun sendOption(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        val gate = _composeState.value
        if (gate is LiveComposeState.Disabled) {
            _notice.value = gate.reason
            return
        }
        val target = client
        if (target == null || _relayState.value !is RelayState.Open) {
            _notice.value = "Not connected — connect first, then send."
            return
        }
        ioScope().launch { enqueueText(text, emptyList()) }
    }

    /** Shared tail of [send] and [sendOption]: optimistic row, dial, 15s ack watchdog. */
    private suspend fun enqueueText(text: String, attachments: List<LivePendingAttachment>) {
        val target = client ?: return
        val id = UUID.randomUUID().toString()
        _pending.value = _pending.value + LivePendingSend(id, text, attachments, LiveSendStatus.SENDING, createdAtMs = System.currentTimeMillis())
        val sent = runCatching {
            target.sendMessage(text, attachments.mapNotNull { it.attachmentId }, id)
        }.isSuccess
        if (!sent) {
            markFailed(id, "send threw — tap retry")
            return
        }
        delay(SEND_ACK_TIMEOUT_MS)
        if (_pending.value.any { it.clientMsgId == id && it.status == LiveSendStatus.SENDING }) {
            markFailed(id, "no send_ack from the relay — tap retry")
        }
    }

    fun retry(clientMsgId: String) {
        val target = client
        if (target == null || _relayState.value !is RelayState.Open) {
            _notice.value = "Not connected — connect first, then retry."
            return
        }
        val row = _pending.value.firstOrNull { it.clientMsgId == clientMsgId } ?: return
        if (row.status != LiveSendStatus.FAILED) return
        _pending.value = _pending.value.map {
            if (it.clientMsgId == clientMsgId) it.copy(status = LiveSendStatus.SENDING, detail = "", createdAtMs = System.currentTimeMillis()) else it
        }
        ioScope().launch {
            val sent = runCatching {
                target.sendMessage(row.text, row.attachments.mapNotNull { it.attachmentId }, clientMsgId)
            }.isSuccess
            if (!sent) {
                markFailed(clientMsgId, "send threw — tap retry")
                return@launch
            }
            delay(SEND_ACK_TIMEOUT_MS)
            if (_pending.value.any { it.clientMsgId == clientMsgId && it.status == LiveSendStatus.SENDING }) {
                markFailed(clientMsgId, "no send_ack from the relay — tap retry")
            }
        }
    }

    fun decide(callId: String, approved: Boolean, rememberSession: Boolean) {
        if (_decisions.value.containsKey(callId) || _expiredApprovals.value.contains(callId)) return
        val target = client
        if (target == null || _relayState.value !is RelayState.Open) {
            _notice.value = "Not connected — connect first."
            return
        }
        if (target.commandsSupported.value == false) {
            _notice.value = "Relay is v1-only — decisions unavailable."
            return
        }
        _decisions.value = _decisions.value + (callId to LiveDecision(approved, rememberSession))
        runCatching {
            target.sendDecision(callId, if (approved) "approve" else "deny", if (rememberSession) "always" else "once")
        }.onFailure {
            _decisions.value = _decisions.value - callId
            _notice.value = "Decision send threw — the card stays open on the host."
        }
    }

    fun markApprovalExpired(callId: String) {
        if (_decisions.value.containsKey(callId)) return
        _expiredApprovals.value = _expiredApprovals.value + callId
    }

    private fun refreshComposeState() {
        _composeState.value = when {
            !configured -> LiveComposeState.Disabled("Connect the relay to send.")
            client?.commandsSupported?.value == false ->
                LiveComposeState.Disabled("Relay is v1-only — send unavailable.")
            else -> LiveComposeState.Ready
        }
    }

    private suspend fun uploadStaged(): List<LivePendingAttachment>? {
        val out = mutableListOf<LivePendingAttachment>()
        val relayUrl = store.url.value
        val relayToken = store.token.value
        for (f in _staged.value) {
            val existing = f.attachmentId
            if (existing != null) {
                out += LivePendingAttachment(f.name, f.mime, f.sizeBytes, existing, f.bytes)
                continue
            }
            markUploading(f.key, true)
            val outcome = runCatching { uploader.upload(relayUrl, relayToken, f.name, f.bytes) }
            markUploading(f.key, false)
            val attachment = outcome.getOrNull()
            if (outcome.isFailure || attachment == null || attachment.id.isBlank()) {
                val cause = (outcome.exceptionOrNull() as? UploadRejected)
                val cap = cause?.sizeCapBytes
                _notice.value = "Upload failed for ${f.name}: " +
                    (cause?.message ?: "no attachment id") +
                    (if (cap != null && cap > 0) " (server cap ${formatCap(cap)})" else "") +
                    " — files kept, nothing sent."
                return null
            }
            out += LivePendingAttachment(f.name, f.mime, f.sizeBytes, attachment.id, f.bytes)
        }
        return out
    }

    private fun formatCap(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private fun markUploading(key: String, uploading: Boolean) {
        _staged.value = _staged.value.map { if (it.key == key) it.copy(uploading = uploading) else it }
    }

    private fun markFailed(clientMsgId: String, detail: String) {
        _pending.value = _pending.value.map {
            if (it.clientMsgId == clientMsgId && it.status == LiveSendStatus.SENDING) {
                it.copy(status = LiveSendStatus.FAILED, detail = detail)
            } else it
        }
    }

    private fun reconcilePending(list: List<PiEvent>) {
        _pending.value = sweepStalePending(_pending.value, System.currentTimeMillis())
        val awaiting = _pending.value.filter { it.status == LiveSendStatus.SENDING }
        if (awaiting.isEmpty()) return
        val echoed = list.filter { it.kind == LiveKinds.USER_MESSAGE }.mapNotNull { it.text }.toSet()
        val done = awaiting.filter { it.text in echoed }
        if (done.isEmpty()) return
        _pending.value = _pending.value - done.toSet()
        done.firstOrNull { it.text == _draft.value }?.let { _draft.value = "" }
    }

    override fun onCleared() {
        runCatching { client?.disconnect() }
    }
}

class LiveViewModelFactory(
    private val store: PiRelayStore,
    private val clientFactory: (url: String, token: String) -> PiRelayClient = { url, token -> PiRelayClient(url, token) },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LiveViewModel(store, clientFactory) as T
    }
}
