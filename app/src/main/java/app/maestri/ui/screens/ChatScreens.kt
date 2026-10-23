package app.maestri.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.ChatStrings as S
import app.maestri.wire.ApiException
import app.maestri.wire.ChatApi
import app.maestri.wire.ChatDraftStore
import app.maestri.wire.ChatEntry
import app.maestri.wire.ChatSegment
import app.maestri.wire.ChatVisibility
import app.maestri.wire.ChatSnapshot
import app.maestri.wire.ChatStreamSocket
import app.maestri.wire.ChatThreadPreview
import app.maestri.wire.ChatThreads
import app.maestri.wire.FileSuggestion
import app.maestri.wire.Mention
import app.maestri.wire.SocketState
import app.maestri.wire.StagedAttachment
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import app.maestri.ui.HostRequests
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.vendorColor
import app.maestri.ui.Style.Palette
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.border
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Motion
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.controlShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import app.maestri.ui.Style.BackLabel
import app.maestri.ui.Style.IconOnly

/**
 * ChatThreadScreen — the chat face of a terminal, iOS parity per behavior spec §3.
 *
 * Contract wiring honored here:
 *  - ChatStreamSocket (snapshot-replacement protocol) + ChatApi GET seed; HTTP never
 *    overwrites a newer socket snapshot (ordering guard inside [ChatFaceState]).
 *  - Scroll pinner (ChatScrollPinner semantics): the list pins to the newest entry only
 *    while at the bottom; leaving the bottom unpins; "Scroll to bottom" jumps back.
 *  - "Load earlier messages" → POST …/chat/history {beforeEntryId} — the page arrives in
 *    the NEXT socket snapshot; hasOlderOnRecord tri-state gates the button / the
 *    "Earlier conversation happened in the terminal" boundary card; ABSENT → old host:
 *    full transcript fetched once via GET …/chat/history and merged HEAD-first.
 *  - Thread switcher: 7 fixed slots 0…6, host palette (blue/purple/pink/red/orange/
 *    yellow/green | graphite, localized client-side), previews via GET …/chat/threads,
 *    per-slot clear via DELETE …/chat/thread {slot}; hidden on single-conversation hosts.
 *  - Composer pill: staged attachment slots, 8 MB-per-item / 64-draft wording, file
 *    import, paste, camera, quote; drafts persisted per conversation ("Your draft was
 *    preserved" on every send failure).
 *  - @-mentions: a trailing "@" opens the selector — agent mentions (GET …/mentions) and
 *    file suggestions (GET …/files?query=&scope=) with the Files ⇄ Back-to-mentions toggle.
 *  - Send-flight states: Sending the prompt / Uploading attachments → landed through
 *    clientMessageId correlation, or a NEVER-RETRIED failure mapping incl.
 *    "Delivery Status Unknown".
 */
@Composable
fun ChatThreadScreen(
    session: WireSession,
    wsId: String,
    terminalId: String,
    terminalName: String,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val holder = remember(terminalId) { ChatFaceState(session, wsId, terminalId, appContext) }
    DisposableEffect(terminalId) {
        ChatVisibility.enterChat(terminalId) // P4-A: on-screen chat suppresses its own notifications
        holder.boot()
        onDispose {
            holder.close()
            ChatVisibility.exitChat(terminalId)
        }
    }

    val snapshot by holder.snapshot.collectAsState()
    val socketState by holder.socketState.collectAsState()
    val notice by holder.notice.collectAsState()
    val flight by holder.flight.collectAsState()
    val composerText by holder.composerText.collectAsState()
    val staged by holder.staged.collectAsState()
    val quote by holder.quote.collectAsState()
    val mentionPanel by holder.mentionPanel.collectAsState()
    val verb by holder.workingVerb.collectAsState()
    val shellStrip by holder.shellStrip.collectAsState()
    val shellPreview by holder.shellPreview.collectAsState()
    val pendingPromptData by holder.pendingPrompt.collectAsState()
    val scope = rememberCoroutineScope()

    var showThreadSheet by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var attachmentPreviewTarget by remember { mutableStateOf<AttachmentPreviewTarget?>(null) }
    var entryMenuTarget by remember { mutableStateOf<EntryMenuTarget?>(null) }
    // Camera dest survives process death (the external camera backgrounds us and
    // the OS may kill the process): File itself is not saveable — its path is.
    // Restored at the ComposerPill callsite; the launcher callback clears it on
    // every return, so cancel drops stale dest (todo #60).
    var cameraDestPath by rememberSaveable { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // ---- scroll pinner: pinned to the newest entry ONLY while at the bottom -------
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 60
        }
    }
    holder.setPinned(atBottom) // bookkeeping only

    // design-v2 strict pill (Mac ChatScrollAffordance parity): "scroll to bottom" shows
    // ONLY when the user is away from the bottom AND a new entry arrived while away —
    // never on a plain upward scroll, never while pinned (new entries land there anyway).
    var newWhileAway by remember { mutableStateOf(false) }
    val atBottomNow by rememberUpdatedState(atBottom)

    // new tail: pinned → stay on the newest entry (jump, content just grew); away →
    // raise the strict pill; conversation switch → neither (the switch effect re-seeds).
    // The raise is ID-keyed (prevNewestId changed = a genuinely new newest entry), so
    // deletes of old entries (size shrinks, newest unchanged) never raise the pill.
    // lastConvSeen guards thread switches: same-size same-key snapshots on a switch must
    // not raise it either.
    val lastConvSeen = remember { mutableStateOf<String?>(null) }
    val prevNewestId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot?.entries?.size, snapshot?.conversationId) {
        val snap = snapshot ?: return@LaunchedEffect
        val newest = snap.entries.firstOrNull()?.id
        if (lastConvSeen.value != snap.conversationId) {
            lastConvSeen.value = snap.conversationId
            prevNewestId.value = newest
        } else if (atBottomNow) {
            prevNewestId.value = newest
            listState.scrollToItem(0)
        } else if (newest != null && newest != prevNewestId.value) {
            newWhileAway = true
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) newWhileAway = false
    }
    // conversation switch (thread change) = re-seed scroll at the bottom (contract)
    LaunchedEffect(snapshot?.conversationId) {
        if (snapshot != null) listState.scrollToItem(0)
    }
    LaunchedEffect(atBottom) { holder.setPinnedFlow(atBottom) }

    // mark read while pinned with unread
    LaunchedEffect(atBottom, snapshot?.unreadCount) {
        if (atBottom && (snapshot?.unreadCount ?: 0) > 0) holder.markReadSoon()
    }
    // shell strip toggle starts/stops the 5 s preview poll
    LaunchedEffect(shellStrip) { holder.setShellStripOn(shellStrip) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .background(LocalMaestriColors.current.canvasChrome)
    ) {
        ChatTopBar(
            terminalName = terminalName,
            snapshot = snapshot,
            socketState = socketState,
            verb = verb,
            shellStripOn = shellStrip,
            onBack = onBack,
            onToggleShellStrip = { holder.toggleShellStrip() },
            onExitChat = { scope.launch { holder.toggleFace() } },
            onOpenThreads = { showThreadSheet = true },
            onClearHistory = { showClearHistoryDialog = true },
        )

        notice?.let { n ->
            Text(
                n,
                color = LocalMaestriColors.current.warning,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // needsAttention: the host raised a pending prompt — same card as the feed
        if (snapshot?.status == "needsAttention" && pendingPromptData != null) {
            val (card, prompt) = pendingPromptData!!
            PendingPromptCard(card, prompt, onOpen = { _, _ -> }, session, scope)
        }

        Box(Modifier.weight(1f)) {
            if (snapshot == null) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Loading chat…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    if (socketState == SocketState.CLOSED) {
                        Text("chat stream offline — retrying…", color = LocalMaestriColors.current.warning, fontSize = 12.sp)
                    }
                }
            } else {
                EntryList(
                    holder = holder,
                    snapshot = snapshot!!,
                    listState = listState,
                    openEntryMenu = { t -> entryMenuTarget = t },
                    openAttachmentPreview = { t -> attachmentPreviewTarget = t },
                    onOpenThreads = { showThreadSheet = true },
                    onClearHistory = { showClearHistoryDialog = true },
                    terminalName = terminalName,
                )
                if (!atBottom && newWhileAway) {
                    TextButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .heightIn(min = Rhythm.TouchTarget.dp)
                            .background(LocalMaestriColors.current.canvasChromeElevated, pillShape())
                            .border(1.dp, LocalMaestriColors.current.hairline, pillShape()),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        Text(S.scrollToBottom, style = monoHud(11, color = MaterialTheme.colorScheme.onSurface))
                    }
                }
                if (shellStrip) {
                    ShellStrip(shellPreview)
                }
            }
        }

        val snap = snapshot
        if (snap != null && !snap.enabled) {
            FaceGate(onEnterChat = { scope.launch { holder.toggleFace() } })
        } else {
            ComposerPill(
                holder = holder,
                terminalName = terminalName,
                text = composerText,
                staged = staged,
                quote = quote,
                mentionPanel = mentionPanel,
                flight = flight,
                showAttachMenu = showAttachMenu,
                onDismissMenu = { showAttachMenu = false },
                onOpenAttachMenu = { showAttachMenu = true },
                onTextChange = { holder.updateComposerText(it) },
                onSend = { scope.launch { holder.send() } },
                onRemoveStaged = { holder.removeStagedItem(it) },
                onRemoveQuote = { holder.quote.value = null },
                // File is not saveable — its absolute path is. Rebuilt here after
                // process death; null clears (cancel-safe via the callback below).
                cameraDest = cameraDestPath?.let(::File),
                setCameraDest = { cameraDestPath = it?.absolutePath },
            )
        }
    }

    // ---- sheets + dialogs ---------------------------------------------------------

    if (showThreadSheet && snapshot?.threads != null) {
        ThreadSwitcherSheet(
            holder = holder,
            threads = snapshot!!.threads!!,
            onDismiss = { showThreadSheet = false },
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(S.clearChatHistoryAsk) },
            text = { Text(S.clearHistoryBodies, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryDialog = false
                    scope.launch { holder.clearHistory() }
                }) { Text(S.clearChatHistory, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel") }
            },
        )
    }

    entryMenuTarget?.let { target ->
        EntryMenuDialog(
            target = target,
            onDismiss = { entryMenuTarget = null },
            onQuote = { holder.stageQuote(target.entry()); entryMenuTarget = null },
            onDelete = {
                entryMenuTarget = null
                scope.launch { holder.deleteEntry(target.entryId()) }
            },
        )
    }

    attachmentPreviewTarget?.let { target ->
        AttachmentPreviewDialog(
            holder = holder,
            target = target,
            onDismiss = { attachmentPreviewTarget = null },
        )
    }
}

// ============================================================== face state holder

/**
 * Send-flight lifecycle (behavior spec §3.8) — NEVER auto-retried; draft preserved.
 * headline: the stage's canonical wording or a specific failure wording; detail: the
 * host's own message or flight progress. UNKNOWN preserves delivery ambiguity.
 */
data class SendFlight(
    val stage: Stage,
    val headline: String? = null,
    val detail: String? = null,
) {
    enum class Stage { UPLOADING, SENDING, CORRELATING, UNKNOWN, FAILED }

    fun title(): String = headline ?: when (stage) {
        Stage.UPLOADING -> S.uploadingAttachments
        Stage.SENDING, Stage.CORRELATING -> S.sendingThePrompt
        Stage.UNKNOWN -> S.deliveryStatusUnknown
        Stage.FAILED -> S.promptCantBeSentDraftPreserved
    }

    fun body(): String? = when (stage) {
        Stage.UNKNOWN -> detail ?: S.deliveryStatusUnknownDetail
        else -> detail
    }
}

/** Composer staged item: a local file (pre-upload) or an uploaded ticket. */
data class StagedItem(
    val localPath: String?,           // cache copy (survives restarts)
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val upload: StagedAttachment?,    // set once POSTed to the host
)

data class EntryMenuTarget(private val entry: ChatEntry, private val copy: String) {
    fun copyText(): String = copy
    fun entryId(): String = entry.id
    fun entry(): ChatEntry = entry
}

data class AttachmentPreviewTarget(
    val entryId: String,
    val attachmentId: String,
    val fileName: String,
)

class ChatFaceState(
    val session: WireSession,
    val wsId: String,
    private val terminalId: String,
    private val appContext: Context,
) {
    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val api = ChatApi(session)
    val socket = ChatStreamSocket(
        clientProvider = {
            session.pairing.value?.let { p -> session.client.pinnedForSocket(p.serverKeyHashB64) }
                ?: okhttp3.OkHttpClient()
        },
        scope = holderScope,
    )

    val drafts = ChatDraftStore(appContext) { session.serverKey }

    private val _snapshot = MutableStateFlow<ChatSnapshot?>(null)
    val snapshot: StateFlow<ChatSnapshot?> = _snapshot

    val socketState: StateFlow<SocketState> get() = socket.state

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    /** Non-holder events (composer-side prep failures) raise the notice line. Never silent. */
    fun note(text: String) { _notice.value = text }

    val flight = MutableStateFlow<SendFlight?>(null)

    val composerText = MutableStateFlow("")

    val staged = MutableStateFlow<List<StagedItem>>(emptyList())

    val quote = MutableStateFlow<String?>(null)

    private val _workingVerb = MutableStateFlow("")
    val workingVerb: StateFlow<String> get() = _workingVerb

    val shellStrip = MutableStateFlow(false)
    val shellPreview = MutableStateFlow<List<String>>(emptyList())

    /** Feed pending prompt (rendered when the chat status is needsAttention). */
    val pendingPrompt = MutableStateFlow<Pair<TerminalCard, String>?>(null)

    /** Load-earlier POST in flight (the page itself rides the NEXT socket snapshot). */
    val paging = MutableStateFlow(false)

    /** Old-host (hasOlderOnRecord ABSENT) GET-history merge state. */
    var fullHistoryLoaded = false

    val threadPreviews = MutableStateFlow<List<ChatThreadPreview>>(emptyList())

    data class MentionPanelState(
        val mode: Mode,
        val query: String,
        val mentions: List<Mention> = emptyList(),
        val files: List<FileSuggestion> = emptyList(),
        val filesRoot: String = "",
        val filesScope: String = "",
        val isTruncated: Boolean = false,
        val loading: Boolean = false,
        val errorWording: String? = null,
    ) {
        enum class Mode { MENTIONS, FILES }
    }

    val mentionPanel = MutableStateFlow<MentionPanelState?>(null)

    private val pinnedValue = MutableStateFlow(false)

    private var markReadJob: Job? = null
    private var shellPollJob: Job? = null
    private var verbJob: Job? = null
    private var mentionFetchJob: Job? = null
    private var draftSaveJob: Job? = null

    private var booted = false
    private var lastConversationId: String? = null
    private var draftLoadedFor: String? = null
    private var pendingCorrelation: String? = null
    private var correlationDeadline = 0L
    private var pasteCounter = 0

    fun boot() {
        if (booted) return
        booted = true
        val p = session.pairing.value
        if (p == null) {
            _notice.value = "not paired"
            return
        }
        holderScope.launch {
            socket.snapshot.collect { s -> if (s != null) applyFullSnapshot(s) }
        }
        socket.open("wss://${p.host}:${p.port}/api/terminals/$terminalId/chat/stream?token=${p.token}")
        holderScope.launch {
            // HTTP seed — adopted ONLY while the socket has delivered nothing (ordering
            // rule): the socket's immediate frame wins the race once it exists.
            delay(150)
            try {
                val snap = api.chatGet(terminalId)
                if (socket.receiveOrder.value == 0L && socket.state.value != SocketState.OPEN) {
                    socket.seedIfNothing(snap)
                }
            } catch (e: Exception) {
                android.util.Log.d("QAChat", "chat GET seed failed: $e")
            }
            loadDraft()
            refreshVerb()
            refreshFeedState()
        }
    }

    fun close() {
        drafts.save(
            conversationIdOr(), terminalId,
            ChatDraftStore.Draft(composerText.value, staged.value.map { it.asDraftStaged() }),
        )
        socket.close()
        shellPollJob?.cancel()
        verbJob?.cancel()
        mentionFetchJob?.cancel()
        markReadJob?.cancel()
        draftSaveJob?.cancel()
        holderScope.cancel()
    }

    private fun conversationIdOr(): String = _snapshot.value?.conversationId ?: ""

    // ------------------------------------------------------------------ snapshots

    /**
     * THE single entrypoint for every snapshot (socket frames AND HTTP answers):
     * snapshot replacement — the newest frame replaces state. A conversation-id change
     * (thread select) replaces the transcript and restarts scroll + send state.
     */
    /** Incoming-motion diff (design-v2): ids appearing at the NEWEST end after seeding. */
    private val _freshEntryIds = MutableStateFlow<Set<String>>(emptySet())
    val freshEntryIds: StateFlow<Set<String>> = _freshEntryIds
    private val seenEntryIds = LinkedHashSet<String>()
    private var seededFor: String? = null

    private fun applyFullSnapshot(snap: ChatSnapshot) {
        if (seededFor != snap.conversationId) {
            seededFor = snap.conversationId
            seenEntryIds.clear()
            snap.entries.takeLast(400).forEach { seenEntryIds.add(it.id) }
            _freshEntryIds.value = emptySet()
        } else {
            val freshIds = snap.entries.takeLast(6)
                .map { it.id }.filter { it !in seenEntryIds }.toSet()
            if (freshIds.isNotEmpty()) {
                _freshEntryIds.value = (_freshEntryIds.value + freshIds).toList().takeLast(24).toSet()
                seenEntryIds.addAll(freshIds)
                if (seenEntryIds.size > 400) {
                    val it = seenEntryIds.iterator()
                    while (seenEntryIds.size > 400) { it.next(); it.remove() }
                }
            }
        }
        _snapshot.value = snap
        refreshVerb()
        // send correlation bookkeeping
        pendingCorrelation?.let { cmid ->
            if (snap.entries.any { it.clientMessageId == cmid }) {
                pendingCorrelation = null
                flight.value = null
            }
        }
        if (lastConversationId != snap.conversationId) {
            lastConversationId = snap.conversationId
            flight.value = null
            pendingCorrelation = null
            composerText.value = ""
            staged.value = emptyList()
            loadDraft()
        }
    }

    private fun loadDraft() {
        val conv = lastConversationId ?: ""
        if (draftLoadedFor == conv) return
        draftLoadedFor = conv
        val d = drafts.load(conv, terminalId)
        if (!d.isEmpty) {
            composerText.value = d.text
            staged.value = d.staged.map {
                StagedItem(
                    localPath = it.localPath.ifBlank { null },
                    fileName = it.fileName,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes,
                    upload = null,
                )
            }
        }
    }

    private fun refreshVerb() {
        if (_snapshot.value?.status == "working" && verbJob == null) {
            var idx = (System.currentTimeMillis() / 3000L).toInt() % S.workingVerbs.size
            verbJob = holderScope.launch {
                while (true) {
                    if (_snapshot.value?.status == "working") {
                        _workingVerb.value = S.workingVerbs[idx % S.workingVerbs.size]
                    } else {
                        _workingVerb.value = ""
                    }
                    delay(2600)
                    idx += 1
                }
            }
        }
    }

    // ------------------------------------------------------------------ reads + pinner

    fun setPinnedFlow(atBottom: Boolean) {
        pinnedValue.value = atBottom
        if (atBottom) markReadSoon()
    }

    fun setPinned(atBottom: Boolean) {
        pinnedValue.value = atBottom
    }

    fun markReadSoon() {
        if (markReadJob?.isActive == true) return
        markReadJob = holderScope.launch {
            delay(900)
            if ((_snapshot.value?.unreadCount ?: 0) > 0) {
                runCatching { api.markRead(terminalId) }
                    .onFailure { android.util.Log.d("QAChat", "chat/read failed: $it") }
            }
        }
    }

    // ------------------------------------------------------------------ shell strip

    fun toggleShellStrip() {
        shellStrip.value = !shellStrip.value
    }

    fun setShellStripOn(on: Boolean) {
        if (on && shellPollJob?.isActive != true) {
            shellPollJob = holderScope.launch {
                while (shellStrip.value) {
                    fetchFeedState()
                    delay(5000)
                }
            }
        } else if (!on) {
            shellPollJob?.cancel()
            shellPollJob = null
        }
    }

    private suspend fun fetchFeedState() {
        try {
            val p = session.pairing.value ?: return
            val feed = session.feedOnce(p, wsId, null)
            val card = feed.terminalCards.firstOrNull { it.id == terminalId }
            shellPreview.value = card?.preview ?: shellPreview.value
            pendingPrompt.value = feed.items
                .filterIsInstance<app.maestri.wire.FeedItem.PendingPromptFeedItem>()
                .firstOrNull { it.terminal.id == terminalId }
                ?.let { it.terminal to it.prompt }
        } catch (e: Exception) {
            // shell preview is a luxury; keep last known
        }
    }

    private fun refreshFeedState() {
        holderScope.launch { fetchFeedState() }
    }

    // ------------------------------------------------------------------ history + paging

    /**
     * "Load earlier messages":
     *  - hasOlderOnRecord == true → POST …/chat/history {beforeEntryId}; the page arrives
     *    in the NEXT socket snapshot (the response is just {ok}).
     *  - hasOlderOnRecord == ABSENT → old host: the retained transcript lives behind
     *    GET …/chat/history — fetched once and merged HEAD-first.
     *  - hasOlderOnRecord == false → boundary card only (the tail reached the edge).
     */
    fun loadEarlier() {
        val snap = _snapshot.value ?: return
        val anchor = snap.entries.firstOrNull()?.id ?: return
        if (paging.value) return
        holderScope.launch {
            try {
                paging.value = true
                when (snap.hasOlderOnRecord) {
                    true -> {
                        api.historyPage(terminalId, snap.conversationId, anchor)
                        paging.value = false // the page replaces state on the next snapshot
                    }
                    null -> {
                        val (conversationId, all) = api.historyAll(terminalId)
                        if (conversationId == snap.conversationId) {
                            val known = snap.entries.mapTo(mutableSetOf()) { it.id }
                            val head = all.takeWhile { it.id !in known }
                            if (head.isNotEmpty()) {
                                applyFullSnapshot(snap.copy(entries = head + snap.entries))
                            }
                        }
                        fullHistoryLoaded = true
                        paging.value = false
                    }
                    else -> {
                        // hasOlderOnRecord == false — the retained tail ends here; the host
                        // holds nothing older. This branch USED to be a silent no-op (the
                        // device's dead tap: button rendered, nothing happened). Honest now:
                        // the reason surfaces in the notice line. Never silent again.
                        _notice.value = S.noEarlierMessagesOnRecord
                        paging.value = false
                    }
                }
            } catch (e: ApiException) {
                _notice.value = "HTTP ${e.status}: ${e.message}"
                paging.value = false
            } catch (e: Exception) {
                _notice.value = e.message
                paging.value = false
            }
        }
    }

    // ------------------------------------------------------------------ threads

    fun selectThread(slot: Int) {
        holderScope.launch {
            try {
                applyFullSnapshot(api.selectThread(terminalId, slot))
            } catch (e: ApiException) {
                _notice.value = when (e.status) {
                    409 -> "Thread switching is locked right now (HTTP 409). ${e.message}"
                    else -> "HTTP ${e.status}: ${e.message}"
                }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun clearSlot(slot: Int) {
        holderScope.launch {
            try {
                api.clearThread(terminalId, slot)
                refreshThreadPreviews()
            } catch (e: ApiException) {
                _notice.value = when (e.status) {
                    409 -> "This thread can't be cleared right now (HTTP 409). ${e.message}"
                    else -> "HTTP ${e.status}: ${e.message}"
                }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun refreshThreadPreviews() {
        holderScope.launch {
            try {
                threadPreviews.value = api.threadPreviews(terminalId)
            } catch (e: Exception) {
                threadPreviews.value = emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ face toggle

    /** PUT …/chat {enabled: !current} — history preserved; snapshot replaces. */
    suspend fun toggleFace() {
        val snap = _snapshot.value ?: return
        try {
            applyFullSnapshot(api.chatSetFace(terminalId, !snap.enabled, null))
        } catch (e: ApiException) {
            _notice.value = "HTTP ${e.status}: ${e.message}"
        } catch (e: Exception) {
            _notice.value = e.message
        }
    }

    // ------------------------------------------------------------------ composer

    fun updateComposerText(value: String) {
        composerText.value = value
        planMention(value)
        scheduleDraftSave()
    }

    fun removeStagedItem(item: StagedItem) {
        val idx = staged.value.indexOfFirst { it === item }
        if (idx < 0) return
        staged.value = staged.value.filterIndexed { i, _ -> i != idx }
        item.upload?.let { up ->
            holderScope.launch { runCatching { api.discardStaged(terminalId, up.attachmentId) } }
        }
        scheduleDraftSave()
    }

    /** Quote pill: the quoted message rides as a markdown block prepended on send. */
    fun stageQuote(entry: ChatEntry) {
        quote.value = entry.text.take(400)
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        if (draftSaveJob?.isActive == true) return
        draftSaveJob = holderScope.launch {
            delay(600)
            drafts.save(
                conversationIdOr(), terminalId,
                ChatDraftStore.Draft(composerText.value, staged.value.map { it.asDraftStaged() }),
            )
        }
    }

    private fun StagedItem.asDraftStaged() = ChatDraftStore.DraftStaged(
        localPath = localPath ?: "",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )

    // ------------------------------------------------------------------ staging

    /** Files from picker/camera: 8 MB-per-item check, cache copy, stage. */
    fun stageLocalFiles(uris: List<Uri>, labels: Map<Uri, String> = emptyMap()) {
        holderScope.launch {
            var rejected = 0
            val accepted = mutableListOf<StagedItem>()
            try {
                for (uri in uris) {
                    // Edge URIs (revoked grant, offline cloud file) reject singly —
                    // never abort the rest of the batch (todo #60 audit).
                    val meta = runCatching { resolveUriMeta(uri, labels[uri]) }.getOrNull()
                    if (meta == null || meta.bytes.size > 8L * 1024 * 1024) {
                        rejected++
                        continue
                    }
                    val copy = copyToDraftDir(meta.fileName, meta.bytes)
                    accepted += StagedItem(copy.path, meta.fileName, meta.mime, meta.bytes.size.toLong(), null)
                }
                val combined = staged.value + accepted
                when {
                    combined.size > 64 -> {
                        _notice.value = S.tooManyAttachmentsToSend
                        if (rejected > 0) _notice.value = S.attachmentsRejected(rejected)
                    }
                    combined.sumOf { it.sizeBytes } > 64L * 1024 * 1024 -> {
                        _notice.value = S.attachmentsRejected(accepted.size)
                    }
                    else -> {
                        staged.value = combined
                        if (rejected > 0) _notice.value = S.attachmentsRejected(rejected)
                    }
                }
                scheduleDraftSave()
            } catch (e: Exception) {
                _notice.value = S.attachmentCantBePreparedDraftPreserved
            }
        }
    }

    /** Paste: image from the clip when the platform offers one, else "Pasted text #N". */
    fun stagePaste() {
        holderScope.launch {
            try {
                val payload = readClipboardPayload(appContext) ?: return@launch
                val (bytes, mime, name) = payload
                if (mime.startsWith("image/")) {
                    val copy = copyToDraftDir(name, bytes)
                    staged.value = staged.value + StagedItem(copy.path, name, mime, bytes.size.toLong(), null)
                } else {
                    pasteCounter += 1
                    val name2 = S.pastedTextN(pasteCounter) + ".txt"
                    val copy = copyToDraftDir(name2, bytes)
                    staged.value = staged.value + StagedItem(copy.path, name2, "text/plain", bytes.size.toLong(), null)
                }
                scheduleDraftSave()
            } catch (e: Exception) {
                _notice.value = S.attachmentCantBePreparedDraftPreserved
            }
        }
    }

    fun canPasteImage(): Boolean = clipboardImageMime(appContext) != null

    private fun copyToDraftDir(fileName: String, bytes: ByteArray): File {
        val dir = File(appContext.cacheDir, "composer-draft").apply { mkdirs() }
        val clean = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return File(dir, UUID.randomUUID().toString().take(8) + "-" + clean).apply { writeBytes(bytes) }
    }

    private data class UriMeta(val fileName: String, val mime: String, val bytes: ByteArray)

    private fun resolveUriMeta(uri: Uri, label: String?): UriMeta? {
        val cr = appContext.contentResolver
        val guessed = cr.getType(uri) ?: mimeFromName(label ?: uri.lastPathSegment.orEmpty())
        val fileName = label ?: queryDisplayName(cr, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return null
        return UriMeta(fileName, guessed ?: "application/octet-stream", bytes)
    }

    // ------------------------------------------------------------------ mentions

    /** Trailing "@word" (or a bare "@") at the text end opens the selector. */
    private fun planMention(text: String) {
        val m = Regex("""(^|\s)@([^\s]*)$""").find(text)
        if (m == null) {
            mentionPanel.value = null
            return
        }
        val q = m.groupValues[2]
        val cur = mentionPanel.value
        mentionPanel.value = MentionPanelState(
            mode = cur?.mode ?: MentionPanelState.Mode.MENTIONS,
            query = q,
            mentions = cur?.mentions ?: emptyList(),
            files = cur?.files ?: emptyList(),
            filesRoot = cur?.filesRoot ?: "",
            filesScope = cur?.filesScope ?: "",
            isTruncated = cur?.isTruncated == true,
            loading = cur?.loading == true,
        )
        scheduleMentionFetch()
    }

    fun toggleMentionMode() {
        val cur = mentionPanel.value ?: return
        val mode = if (cur.mode == MentionPanelState.Mode.MENTIONS) MentionPanelState.Mode.FILES
        else MentionPanelState.Mode.MENTIONS
        mentionPanel.value = cur.copy(mode = mode)
        scheduleMentionFetch()
    }

    fun mentionPanelScope(dir: FileSuggestion) {
        val cur = mentionPanel.value ?: return
        if (!dir.isDirectory) return
        mentionPanel.value = cur.copy(filesScope = dir.relativePath, files = emptyList(), loading = true)
        scheduleMentionFetch()
    }

    private fun scheduleMentionFetch() {
        mentionFetchJob?.cancel()
        val cur = mentionPanel.value ?: return
        val mode = cur.mode
        mentionFetchJob = holderScope.launch {
            if (mode == MentionPanelState.Mode.FILES) delay(250)
            if (mentionPanel.value == null) return@launch
            try {
                if (mode == MentionPanelState.Mode.FILES) {
                    val scopeFolder = mentionPanel.value?.filesScope ?: ""
                    mentionPanel.value = mentionPanel.value?.copy(loading = true, errorWording = null)
                    val res = api.fileSuggestions(terminalId, cur.query, scopeFolder, 200)
                    if (mentionPanel.value?.mode == mode && mentionPanel.value?.query == cur.query) {
                        mentionPanel.value = mentionPanel.value?.copy(
                            files = res.entries,
                            filesRoot = res.root,
                            isTruncated = res.isTruncated,
                            loading = false,
                            errorWording = null,
                        )
                    }
                } else {
                    mentionPanel.value = mentionPanel.value?.copy(loading = true, errorWording = null)
                    val ms = api.mentions(terminalId)
                    if (mentionPanel.value?.mode == mode && mentionPanel.value?.query == cur.query) {
                        mentionPanel.value = mentionPanel.value?.copy(
                            mentions = ms,
                            loading = false,
                            errorWording = null,
                        )
                    }
                }
            } catch (e: Exception) {
                if (mentionPanel.value?.mode == mode) {
                    mentionPanel.value = mentionPanel.value?.copy(
                        loading = false,
                        errorWording = if (mode == MentionPanelState.Mode.FILES) S.couldnTLoadFiles
                        else S.mentionsAreTemporarilyUnavailable,
                    )
                }
            }
        }
    }

    fun countMentionSuggestions(count: Int): String = S.mentionSuggestionsCountFmt(count)

    /** Mention insertion: "@<tokenName|name>" — exact host token syntax is CANDIDATE. */
    fun insertMention(m: Mention) {
        val token = m.tokenName?.takeIf { it.isNotBlank() } ?: m.name
        val cur = composerText.value
        val idx = cur.lastIndexOf('@')
        composerText.value = (if (idx >= 0) cur.substring(0, idx) else cur) + "@" + token + " "
        mentionPanel.value = null
        scheduleDraftSave()
    }

    fun insertFile(f: FileSuggestion) {
        val cur = composerText.value
        val idx = cur.lastIndexOf('@')
        composerText.value = (if (idx >= 0) cur.substring(0, idx) else cur) + f.relativePath + " "
        mentionPanel.value = null
        scheduleDraftSave()
    }

    // ------------------------------------------------------------------ send flights

    /**
     * Composer order: quote + text as ONE text segment, then staged attachments in stage
     * order (wire contract: segments in composer order). NEVER auto-retry: any ambiguous
     * or failed send becomes a retained flight; the draft is explicitly preserved.
     */
    suspend fun send() {
        if (flight.value != null) return // a send is already in flight; do not double-send
        val text = composerText.value
        val items = staged.value
        if (text.isBlank() && items.isEmpty()) return

        val snap = _snapshot.value
        val caps = session.serverInfo
        if (items.isNotEmpty() && caps?.has("attachmentStaging") == false) {
            flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.thisComputerCantReceiveAttachments)
            return
        }
        when (snap?.status) {
            "notRunning" -> {
                flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.thisTerminalIsntRunning)
                return
            }
            "unloaded" -> {
                flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.thisTerminalIsUnloaded)
                return
            }
            else -> {}
        }
        if (items.size > 64) {
            flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.tooManyAttachmentsToSend)
            return
        }

        try {
            val segs = mutableListOf<Pair<String, String>>()
            val quoted = quote.value
            val bodyText = if (quoted.isNullOrBlank()) text
            else "> " + quoted.replace("\n", "\n> ") + "\n\n" + text
            if (bodyText.isNotBlank()) segs.add("text" to bodyText)

            // --- uploads first ("Uploading attachments"); expired tickets re-upload
            items.forEachIndexed { _, item ->
                val ticket = item.upload?.takeIf { !it.isExpired }
                if (ticket == null) {
                    flight.value = SendFlight(
                        SendFlight.Stage.UPLOADING,
                        detail = item.fileName,
                    )
                    val fresh = try {
                        api.uploadAttachment(terminalId, item.fileName, item.mimeType, item.loadBytes())
                    } catch (e: ApiException) {
                        when {
                            e.status == 413 ->
                                flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.theAttachmentCantBeAddedChoose8Mb)
                            e.status == 0 && e.code == "attachments" ->
                                // expired ticket AND the local copy is gone: expiry wording
                                flight.value = SendFlight(SendFlight.Stage.FAILED, headline = S.attachmentExpiredOrTerminalUnavailable)
                            else ->
                                flight.value = SendFlight(
                                    SendFlight.Stage.FAILED,
                                    headline = S.attachmentCantBeCopiedDraftPreserved,
                                    detail = e.message,
                                )
                        }
                        return
                    } catch (e: Exception) {
                        flight.value = SendFlight(
                            SendFlight.Stage.FAILED,
                            headline = S.attachmentCantBeCopiedDraftPreserved,
                            detail = e.message,
                        )
                        return
                    }
                    // persist the fresh ticket so the kept draft carries it
                    staged.value = staged.value.map { old -> if (old === item) old.copy(upload = fresh) else old }
                    segs.add("attachmentId" to fresh.attachmentId)
                } else {
                    segs.add("attachmentId" to ticket.attachmentId)
                }
            }

            // --- prompt send ("Sending the prompt")
            flight.value = SendFlight(SendFlight.Stage.SENDING)
            val cmid = api.sendPrompt(terminalId, segs)
            pendingCorrelation = cmid
            correlationDeadline = System.currentTimeMillis() + 20_000
            flight.value = SendFlight(SendFlight.Stage.CORRELATING, detail = cmid)
            if (awaitCorrelation()) {
                composerText.value = ""
                quote.value = null
                staged.value = emptyList()
                drafts.clear(conversationIdOr(), terminalId)
                flight.value = null
            } else {
                // The request left without a usable response: ambiguity preserved,
                // draft kept, NEVER re-sent.
                flight.value = SendFlight(SendFlight.Stage.UNKNOWN, detail = null)
            }
        } catch (e: ApiException) {
            val wording = when (e.status) {
                409 -> S.terminalBusyOrNotRunning
                413 -> S.promptTooLongToSend
                else -> S.promptCantBeSentDraftPreserved
            }
            flight.value = SendFlight(SendFlight.Stage.FAILED, headline = wording, detail = e.message)
        } catch (e: Exception) {
            // Connection lost before/while the request ran: it may have started.
            flight.value = SendFlight(SendFlight.Stage.UNKNOWN, detail = null)
        }
        scheduleDraftSave()
    }

    private suspend fun awaitCorrelation(): Boolean {
        while (System.currentTimeMillis() < correlationDeadline) {
            val snap = _snapshot.value
            if (snap != null && snap.entries.any { it.clientMessageId == pendingCorrelation }) {
                return true
            }
            delay(250)
            if (flight.value?.stage == SendFlight.Stage.CORRELATING &&
                System.currentTimeMillis() > correlationDeadline
            ) {
                // deadline passed mid-await: one explicit unknown, draft preserved
                flight.value = SendFlight(SendFlight.Stage.UNKNOWN, detail = null)
                return false
            }
        }
        return false
    }

    fun dismissFlight() {
        flight.value = null
    }

    private fun StagedItem.loadBytes(): ByteArray {
        val f = localPath?.let { File(it) }
        if (f != null && f.exists()) return f.readBytes()
        throw ApiException(0, "attachments", "local staged copy missing")
    }

    // ------------------------------------------------------------------ entries

    fun deleteEntry(entryId: String) {
        holderScope.launch {
            try {
                api.deleteEntry(terminalId, entryId)
            } catch (e: ApiException) {
                _notice.value = when (e.status) {
                    409 -> "This entry can't be deleted right now (HTTP 409). ${e.message}"
                    else -> "HTTP ${e.status}: ${e.message}"
                }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    /** DELETE …/chat/attachments/{id} — removes the file from EVERY entry holding it. */
    fun deleteAttachmentEverywhere(attachmentId: String) {
        holderScope.launch {
            try {
                api.deleteChatAttachment(terminalId, attachmentId)
            } catch (e: ApiException) {
                _notice.value = when (e.status) {
                    409 -> "This attachment can't be removed right now (HTTP 409). ${e.message}"
                    else -> "HTTP ${e.status}: ${e.message}"
                }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    /** GET …/chat/attachments/{id} bytes; 404 → empty (gone). */
    suspend fun attachmentImageBytes(attachmentId: String): ByteArray = withContext(Dispatchers.IO) {
        runCatching { api.attachmentBytes(terminalId, attachmentId) }.getOrDefault(ByteArray(0))
    }

    suspend fun clearHistory() {
        try {
            api.clearHistory(terminalId)
            // the tail replacement arrives through the socket on its own
            runCatching { api.markRead(terminalId) }
        } catch (e: ApiException) {
            _notice.value = when (e.status) {
                409 -> "Chat history can't be cleared while a turn or import is running. ${e.message}"
                else -> "HTTP ${e.status}: ${e.message}"
            }
        } catch (e: Exception) {
            _notice.value = e.message
        }
    }

    private fun queryDisplayName(cr: android.content.ContentResolver, uri: Uri): String? = runCatching {
        if (uri.scheme != "content") return@runCatching null
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}

// ============================================================ shared helpers

/** First image mime (the image/… wildcard) on the primary clip, else null (Android paste parity is partial). */
internal fun clipboardImageMime(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return@runCatching null
    val clip = cm.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) return@runCatching null
    val d = clip.description
    (0 until d.mimeTypeCount).firstOrNull { d.getMimeType(it).startsWith("image/") }?.let { d.getMimeType(it) }
}.getOrNull()

private fun mimeFromName(name: String): String? {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") -> "text/plain"
        lower.endsWith(".zip") -> "application/zip"
        else -> null
    }
}

/** Paste payload: image bytes (uri-backed clip, modern Androids) or plain text. */
internal fun readClipboardPayload(context: Context): Triple<ByteArray, String, String>? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val mime = clipboardImageMime(context)
    if (mime != null) {
        val uri = clip.getItemAt(0).uri
            ?: throw IllegalStateException("image clip without content uri")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("clipboard image unreadable")
        Triple(bytes, mime, if (mime.contains("png")) "pasted-image.png" else "pasted-image.jpg")
    } else {
        val text = clip.getItemAt(0).text?.toString() ?: return null
        if (text.isBlank()) return null
        Triple(text.toByteArray(Charsets.UTF_8), "text/plain", "pasted")
    }
}.getOrNull()

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> (bytes / 1024).toString() + " KB"
    else -> bytes.toString() + " B"
}

// ============================================================ top bar

@Composable
private fun ChatTopBar(
    terminalName: String,
    snapshot: ChatSnapshot?,
    socketState: SocketState,
    verb: String,
    shellStripOn: Boolean,
    onBack: () -> Unit,
    onToggleShellStrip: () -> Unit,
    onExitChat: () -> Unit,
    onOpenThreads: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // v2.1 phone-compact (frame 05, todo #47): below 480dp the nav tightens toward the
    // artifact's 52px bar — status line joins the identity chip row; above, byte-identical.
    val compact = LocalConfiguration.current.screenWidthDp < 480
    val statusLine = when (snapshot?.status) {
        "working" -> verb.ifBlank { S.waitingForAReply }
        "needsAttention" -> S.waitingForAReply
        "idle" -> "idle"
        "notRunning" -> S.thisTerminalIsntRunning
        "unloaded" -> S.thisTerminalIsUnloaded
        else -> socketState.name.lowercase()
    }
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = 12.dp,
            vertical = if (compact) 2.dp else 6.dp,
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) { BackLabel("Back") }
            Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                // design-v2 05 lnav: serif 24·500 root title (title-only serif)
                Text(
                    "Chat", // CANDIDATE: literal title, not from the parity corpus
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                when (snapshot?.status) {
                                    "working" -> LocalMaestriColors.current.warning
                                    "needsAttention" -> MaterialTheme.colorScheme.error
                                    else -> Color(0xFF3A3D45)
                                },
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        terminalName,
                        fontSize = 10.5.sp, // artifact lnav chip: mono 10.5
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // vendor dot — only when the talking-to agent names a real vendor
                    snapshot?.talkingToAgentName?.let { agent ->
                        vendorColor(agent)?.let { vc ->
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.size(8.dp).background(vc, CircleShape))
                        }
                    }
                    if (compact) {
                        // frame 05 52px bar: the status rides beside the identity chip
                        Spacer(Modifier.width(4.dp))
                        Text(
                            statusLine,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!compact) {
                    Text(
                        statusLine,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // threads switcher trigger — the artifacts ⧉ glyph
            if (snapshot?.threads != null) {
                TextButton(
                    onClick = onOpenThreads,
                    modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                ) { Text("⧉", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
            TextButton(
                onClick = { menuOpen = !menuOpen },
                modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
            ) { IconOnly(Icons.Filled.MoreVert, size = 18.dp) }
        }
        // overflow keeps every former inline action reachable (never lose an action)
        // v2.1 revisita (reno): below 480dp the three-action Row crushes into
        // one-word-per-line columns — restack as a right-aligned vertical list.
        // Tablet keeps the Row, content-identical (re-indented only).
        if (menuOpen) {
            if (compact) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { menuOpen = false; onToggleShellStrip() },
                        modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                    ) {
                        Text(if (shellStripOn) S.hideTerminalPreview else S.showTerminalPreview, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { menuOpen = false; onExitChat() },
                        modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                    ) {
                        Text(if (snapshot?.enabled == true) S.exitChatMode else S.enterChatMode, fontSize = 12.sp)
                    }
                    if (snapshot?.canClear == true) {
                        TextButton(
                            onClick = { menuOpen = false; onClearHistory() },
                            modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                        ) {
                            Text(S.clearChatHistory, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { menuOpen = false; onToggleShellStrip() },
                        modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                    ) {
                        Text(if (shellStripOn) S.hideTerminalPreview else S.showTerminalPreview, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { menuOpen = false; onExitChat() },
                        modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                    ) {
                        Text(if (snapshot?.enabled == true) S.exitChatMode else S.enterChatMode, fontSize = 12.sp)
                    }
                    if (snapshot?.canClear == true) {
                        TextButton(
                            onClick = { menuOpen = false; onClearHistory() },
                            modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                        ) {
                            Text(S.clearChatHistory, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadChipsRow(
    holder: ChatFaceState,
    threads: ChatThreads?,
    onOpenThreads: () -> Unit,
) {
    if (threads == null) return
    val chrome = LocalMaestriColors.current
    val previews by holder.threadPreviews.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        threads.items.forEach { slot ->
            val active = slot.id == threads.activeSlot
            val preview = previews.firstOrNull { it.id == slot.id }?.entries?.lastOrNull()?.text
            val previewLabel = preview?.replace("\n", " ")?.trim()?.take(24).orEmpty()
            val label = slot.id.toString() + " · " +
                (previewLabel.ifEmpty { if (slot.hasMessages) "has messages" else "empty" })
            Row( // .tchip — mono 11.5, 8×14, pill, warm ring; .on = accent-hi + accent 55% ring
                modifier = Modifier
                    .heightIn(min = Rhythm.TouchTarget.dp)
                    .clip(pillShape())
                    .background(chrome.canvasSurface, pillShape())
                    .let {
                        if (active) {
                            it.border(1.dp, Palette.TerracottaLight.copy(alpha = 0.55f), pillShape())
                        } else {
                            it.border(1.dp, chrome.hairline, pillShape())
                        }
                    }
                    .clickable(enabled = !active) {
                        holder.selectThread(slot.id)
                        holder.refreshThreadPreviews()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) Palette.TerracottaLight else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .heightIn(min = Rhythm.TouchTarget.dp)
                .clip(pillShape())
                .background(chrome.canvasSurface, pillShape())
                .border(1.dp, chrome.hairline, pillShape())
                .clickable { onOpenThreads() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                threads.items.size.toString() + " threads",
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** Host palette, settings order: blue purple pink red orange yellow green | graphite. */
fun threadColor(name: String): Color = when (name.lowercase()) {
    "blue" -> Color(0xFF0A84FF)
    "purple" -> Color(0xFFBF5AF2)
    "pink" -> Color(0xFFFF375F)
    "red" -> Color(0xFFFF453A)
    "orange" -> Color(0xFFFF9F0A)
    "yellow" -> Color(0xFFFFD60A)
    "green" -> Color(0xFF32D74B)
    else -> Color(0xFF8E8E93) // graphite
}

// ============================================================ entries (reverse-anchored)

/**
 * Entries render bottom-anchored (reverseLayout): item 0 is the newest entry, so the
 * LazyColumn naturally sits on the newest message — the ChatScrollPinner becomes "stay
 * on item 0". Load-earlier + boundary card render at the list's visual top.
 */
@Composable
private fun EntryList(
    holder: ChatFaceState,
    snapshot: ChatSnapshot,
    listState: LazyListState,
    openEntryMenu: (EntryMenuTarget) -> Unit,
    openAttachmentPreview: (AttachmentPreviewTarget) -> Unit,
    onOpenThreads: () -> Unit,
    onClearHistory: () -> Unit,
    terminalName: String,
) {
    // v2.1 phone-compact (frame 05): below 480dp the settings card + tool tiles render
    // under the thread chips; the tablet arrangement is byte-identical (compact == false).
    val compact = LocalConfiguration.current.screenWidthDp < 480
    val entries = snapshot.entries
    val paging by holder.paging.collectAsState()
    val showBoundary = snapshot.hasOlderOnRecord == false ||
        (snapshot.hasOlderOnRecord == null && holder.fullHistoryLoaded)
    val showLoadEarlier = snapshot.hasOlderOnRecord == true ||
        (snapshot.hasOlderOnRecord == null && !holder.fullHistoryLoaded && entries.isNotEmpty())

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showBoundary) {
            item(key = "boundary") { BoundaryCard() }
        }
        val freshIds = holder.freshEntryIds
        itemsIndexed(entries.asReversed(), key = { _, e -> e.id }) { revIdx, entry ->
            val isLast = revIdx == 0
            EntryBubble(
                freshIds = freshIds,
                entry = entry,
                compact = compact,
                showNoReply = isLast && entry.isUser && snapshot.status == "idle",
                talkingTo = snapshot.talkingToAgentName,
                answering = snapshot.answeringAgentName,
                attachmentLoader = { id -> holder.attachmentImageBytes(id) },
                onRemoveAttachment = { id -> holder.deleteAttachmentEverywhere(id) },
                onLongPress = { openEntryMenu(EntryMenuTarget(entry, entry.text)) },
                onAttachmentTap = { seg ->
                    openAttachmentPreview(AttachmentPreviewTarget(entry.id, seg.id, seg.filename))
                },
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        S.sendAMessageAndTheAgentWillAnswerHere,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        // design-v2 05: chain tail = visual top (reverseLayout column) — bottom-anchored
        // transcript sits under the thread chips; "load earlier" heads the older pages.
        // GATE = the actionable tri-state only: hasOlderOnRecord true, or ABSENT (old-host
        // GET-merge pending). false = the boundary card above is the whole truth — the
        // pre-fix unconditional render put a button here that tapped into the silent
        // false-branch, which is exactly the observed device no-op. Never render a
        // button whose only outcome is silence.
        if (entries.isNotEmpty() && showLoadEarlier) {
            item(key = "load-earlier") {
                if (paging) {
                    LoadingPill(S.loadEarlierMessages, {})
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { holder.loadEarlier() },
                            modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                        ) {
                            Text(S.loadEarlierMessages, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (compact) {
            item(key = "chat-settings") {
                ChatSettingsCard(
                    holder = holder,
                    terminalName = terminalName,
                    snapshot = snapshot,
                    onOpenThreads = onOpenThreads,
                    onClearHistory = onClearHistory,
                )
            }
        }
        if (snapshot.threads != null) {
            item(key = "tchips") {
                ThreadChipsRow(
                    holder = holder,
                    threads = snapshot.threads,
                    onOpenThreads = onOpenThreads,
                )
            }
        }
    }
}

/** "Earlier conversation happened in the terminal" — ChatHistoryBoundaryMarker parity. */
@Composable
private fun BoundaryCard() {
    Surface(
        color = LocalMaestriColors.current.canvasSurface,
        shape = controlShape(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            S.earlierConversationHappenedInTheTerminal,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/**
 * design-v2.1 frame 05 chat-settings card (phone-compact): r-18 card on the banked
 * card token — identity row (36 ic tile, names, status dot) + Switch pill + 76px tool
 * tiles. REAL data only: names/status/threads-count live on the wire; the canned-run
 * tiles render honest-disabled ("(awaiting schema)" — no host route for canned
 * prompts); the alerts well has no chat-level alert contract and Aa ± needs
 * MarkdownLite font plumbing — both deferred (flagged in the L5 report), never faked.
 */
@Composable
private fun ChatSettingsCard(
    holder: ChatFaceState,
    terminalName: String,
    snapshot: ChatSnapshot,
    onOpenThreads: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val chrome = LocalMaestriColors.current
    val verb by holder.workingVerb.collectAsState()
    val statusLine = when (snapshot.status) {
        "working" -> verb.ifBlank { S.waitingForAReply }
        "needsAttention" -> S.waitingForAReply
        else -> snapshot.status
    }
    val talking = snapshot.talkingToAgentName ?: snapshot.answeringAgentName
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(chrome.card, RoundedCornerShape(MaestriRadii.Card))
            .border(1.dp, chrome.hairline, RoundedCornerShape(MaestriRadii.Card))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(MaestriRadii.Control))
                    .background(Palette.Terracotta.copy(alpha = 0.16f))
                    .border(1.dp, Palette.Terracotta.copy(alpha = 0.22f), RoundedCornerShape(MaestriRadii.Control)),
                contentAlignment = Alignment.Center,
            ) {
                IconOnly(Icons.Filled.Star, size = 18.dp, tint = Palette.TerracottaLight)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    listOfNotNull(terminalName, talking).joinToString(" · "),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // status dot — the top bar's wire mapping, verbatim
                    Box(
                        Modifier.size(6.dp).background(
                            when (snapshot.status) {
                                "working" -> chrome.warning
                                "needsAttention" -> MaterialTheme.colorScheme.error
                                else -> Color(0xFF3A3D45)
                            },
                            CircleShape,
                        )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        statusLine,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(pillShape())
                    .clickable(onClick = onOpenThreads)
                    .border(1.dp, Palette.Terracotta.copy(alpha = 0.55f), pillShape())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Switch", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.TerracottaLight)
                Spacer(Modifier.width(4.dp))
                IconOnly(Icons.Filled.KeyboardArrowDown, size = 12.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Canned-run tiles: the contract has no route for canned prompts — honest
            // disabled with the awaiting-schema marker, never a fake count.
            ToolTile(
                modifier = Modifier.weight(1f),
                label = "Fix lint",
                icon = Icons.Filled.Edit,
                tint = Palette.TerracottaLight,
                tileTop = Palette.Terracotta.copy(alpha = 0.14f).compositeOver(Palette.ScreenLift),
                ring = Palette.TerracottaLight.copy(alpha = 0.45f),
                sub = "(awaiting schema)",
                enabled = false,
            )
            ToolTile(
                modifier = Modifier.weight(1f),
                label = "Run tests",
                icon = Icons.Filled.PlayArrow,
                tint = Palette.Success,
                tileTop = Palette.Success.copy(alpha = 0.14f).compositeOver(Palette.ScreenLift),
                ring = Palette.Success.copy(alpha = 0.40f),
                sub = "(awaiting schema)",
                enabled = false,
            )
            ToolTile(
                modifier = Modifier.weight(1f),
                label = "Clear",
                icon = Icons.Filled.Delete,
                tint = MaterialTheme.colorScheme.onSurface,
                tileTop = Palette.ScreenLift,
                ring = chrome.hairline,
                sub = snapshot.threads?.items?.size?.let { "$it threads kept" }
                    ?: "clears transcript + attachments",
                enabled = snapshot.canClear,
                onClick = onClearHistory,
            )
        }
    }
}

/**
 * Frame 05 tool tile: 76px, r-14, gradient top over the sunken surface, 28 ic r-9,
 * 12/600 label + mono 10 sub. Disabled tiles sit at 40% alpha — visible, not fake.
 */
@Composable
private fun ToolTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    tileTop: Color,
    ring: Color,
    sub: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(tileTop, Palette.ScreenLift)))
            .border(1.dp, ring, RoundedCornerShape(14.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = { onClick?.invoke() })
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Palette.ScreenLift),
            contentAlignment = Alignment.Center,
        ) {
            IconOnly(icon, size = 15.dp, tint = tint)
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.4.sp)
        Text(
            sub,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryBubble(
    freshIds: StateFlow<Set<String>>,
    entry: ChatEntry,
    showNoReply: Boolean,
    talkingTo: String?,
    answering: String?,
    attachmentLoader: suspend (String) -> ByteArray,
    onRemoveAttachment: (String) -> Unit,
    onLongPress: () -> Unit,
    onAttachmentTap: (ChatSegment.Attachment) -> Unit,
    compact: Boolean = false,
) {
    val isUser = entry.isUser
    if (entry.kind == "divider") {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                entry.text.ifBlank { "· · ·" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && entry.kind == "message") {
            if (compact) {
                // frame 05 agent meta: 16px vendor dot + mono 10.5/600 "name · HH:mm".
                // Times are wire truth (ChatEntry.ts, epoch millis from the host) — null
                // renders name-only, nothing invented. The talking-to context lives in
                // the settings card above; the tablet keeps both text rows byte-identical.
                val who = entry.authorName ?: entry.agentType ?: ""
                if (who.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    ) {
                        val dot = vendorColor(entry.agentType ?: entry.authorName)
                            ?: Palette.StoneGray
                        Box(Modifier.size(16.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            who + (entry.ts?.let { ts ->
                                " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(ts))
                            } ?: ""),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                listOfNotNull(entry.authorName, entry.agentType).firstOrNull()?.let {
                    Text(
                        it,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                    )
                }
                (talkingTo ?: answering)?.let {
                    Text(
                        "↳ " + it,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                    )
                }
            }
        }
        when (entry.kind) {
            "progress" -> Text(
                entry.text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
            )
            else -> {
                // message + final: canonical entry; user rides their composer segments.
                // design-v2: .press scale .97 (Motion.Fast) + .incoming rise/fade (Motion.Slow).
                val freshSet by freshIds.collectAsState()
                val incoming = entry.id in freshSet
                val inAnim = remember(entry.id) { Animatable(if (incoming) 0f else 1f) }
                LaunchedEffect(entry.id, incoming) {
                    if (incoming && !inAnim.isRunning) {
                        inAnim.animateTo(1f, tween(Motion.Slow, easing = Motion.Ease))
                    }
                }
                val bubbleSource = remember { MutableInteractionSource() }
                val bubblePressed by bubbleSource.collectIsPressedAsState()
                val press by animateFloatAsState(
                    targetValue = if (bubblePressed) Motion.PressScale else 1f,
                    animationSpec = tween(Motion.Fast, easing = Motion.Ease),
                    label = "bubble-press",
                )
                // design-v2 05 .bub: 82% max, 11×14, radius 20 + 7 tail, 13/1.5;
                // me = 135° gradient #d97b54→#c96442 + white 8% inner ring + glow (.22, 8/20);
                // ag = warm card + ring. Incoming rise matches the artifact (8px).
                val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.82f
                val bubbleShape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 7.dp,
                    bottomEnd = if (isUser) 7.dp else 20.dp,
                )
                val bubbleFill = if (isUser) {
                    Brush.linearGradient(
                        0f to Palette.TerracottaLight,
                        1f to Palette.Terracotta,
                        start = Offset(0f, 0f),
                        end = Offset(1f, 1f),
                    )
                } else {
                    Brush.linearGradient(0f to LocalMaestriColors.current.bubbleAgent, 1f to LocalMaestriColors.current.bubbleAgent)
                }
                val glow = if (isUser) Palette.Terracotta.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.18f)
                Surface(
                    color = Color.Transparent,
                    shape = bubbleShape,
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .shadow(elevation = 10.dp, shape = bubbleShape, ambientColor = glow, spotColor = glow)
                        .graphicsLayer {
                            scaleX = press; scaleY = press
                            alpha = inAnim.value
                            translationY = (1f - inAnim.value) * 8.dp.toPx()
                        }
                        .background(bubbleFill, bubbleShape)
                        .border(
                            1.dp,
                            if (isUser) Color.White.copy(alpha = 0.08f) else LocalMaestriColors.current.hairline,
                            bubbleShape,
                        )
                        .combinedClickable(
                            interactionSource = bubbleSource,
                            onClick = {},
                            onLongClick = onLongPress,
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        when {
                            entry.segments.isNotEmpty() -> entry.segments.forEach { seg ->
                                when (seg) {
                                    is ChatSegment.Text -> MarkdownLite(seg.text, compact)
                                    is ChatSegment.Attachment -> AttachmentPill(
                                        seg = seg,
                                        bytesLoader = attachmentLoader,
                                        onTap = { onAttachmentTap(seg) },
                                        onRemoveAttachment = if (seg.id.isNotBlank()) {
                                            { onRemoveAttachment(seg.id) }
                                        } else null,
                                    )
                                }
                            }
                            entry.text.isNotBlank() -> MarkdownLite(entry.text, compact)
                        }
                        if (compact && isUser) {
                            // frame 05 .bub.me footer: right-aligned mono 10 time + the ONE
                            // tick the wire truth supports — a user entry renders from the
                            // host snapshot, so its presence IS "recorded by host". The
                            // mock's dim second tick is a read receipt; the wire carries no
                            // per-entry read state — omitted, never invented.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                entry.ts?.let { ts ->
                                    Text(
                                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(ts)),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                IconOnly(Icons.Filled.Check, size = 14.dp)
                            }
                        }
                        if (showNoReply && isUser) {
                            Text(
                                S.noReplyArrived,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A retained attachment inside an entry: bytes from GET …/chat/attachments/{id}; 404 → gone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentPill(
    seg: ChatSegment.Attachment,
    bytesLoader: suspend (String) -> ByteArray,
    onTap: () -> Unit,
    onRemoveAttachment: (() -> Unit)?,
) {
    if (seg.id.isBlank()) return
    var bmp by remember(seg.id) { mutableStateOf<Bitmap?>(null) }
    var gone by remember(seg.id) { mutableStateOf(false) }
    LaunchedEffect(seg.id, seg.isImage) {
        if (seg.isImage) {
            val bytes = bytesLoader(seg.id)
            if (bytes.isEmpty()) {
                gone = true // 404: the attachment is gone; render nothing
            } else {
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }
    if (gone) return
    if (seg.isImage) {
        val b = bmp
        if (b != null) {
            ChatImageThumb(
                bmp = b,
                filename = seg.filename,
                onTap = onTap,
                onLongPress = onRemoveAttachment,
            )
        } else {
            LoadingPill("…", onTap)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = Rhythm.TouchTarget.dp)
                .background(LocalMaestriColors.current.canvasSurface, controlShape())
                .clickable { onTap() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconOnly(Icons.Filled.ContentCopy, size = 13.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                S.attachmentNamed(seg.filename),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingPill(label: String, onTap: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(LocalMaestriColors.current.canvasSurface, controlShape())
            .clickable { onTap() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================== markdown-lite

/**
 * MarkdownField rendering (parity for what agents actually write): paragraphs, headings,
 * bullets, quotes, bold/italic/code spans, and fenced code blocks with "Copy Code".
 */
@Composable
fun MarkdownLite(text: String, compact: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val fences = Regex("""```(\w*)(?:\n|\r\n)((?:.|\n)*?)(?:```|$)""")
    if (!text.contains("```")) {
        MarkdownText(text, compact)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var cursor = 0
        for (m in fences.findAll(text)) {
            val head = text.substring(cursor, m.range.first)
            if (head.isNotBlank()) MarkdownText(head, compact)
            CodeBlockCard(
                code = m.groupValues[2].removeSuffix("\n"),
                lang = m.groupValues[1],
                clipboard = clipboard,
            )
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            if (tail.isNotBlank()) MarkdownText(tail, compact)
        }
    }
}

@Composable
private fun MarkdownText(text: String, compact: Boolean = false) {
    val imageRe = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        text.split("\n").forEach { line ->
            when {
                imageRe.matches(line) -> { // Mac ChatMarkdownImage parity (design-v2 05)
                    val m = imageRe.matchEntire(line)!!
                    ChatMarkdownImage(
                        url = m.groupValues[2],
                        alt = m.groupValues[1],
                    )
                }
                line.startsWith("### ") -> Text(
                    inlineSpan(line.drop(4)),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("## ") -> Text(
                    inlineSpan(line.drop(3)),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("# ") -> Text(
                    inlineSpan(line.drop(2)),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("- ") || line.startsWith("• ") -> Row {
                    Text("•  ", fontSize = 13.sp)
                    Text(inlineSpan(line.drop(2)), fontSize = 13.sp)
                }
                line.startsWith("> ") -> Text(
                    inlineSpan(line.drop(2)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(LocalMaestriColors.current.canvasSurface, controlShape())
                        .padding(6.dp)
                        .fillMaxWidth(),
                )
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(
                    inlineSpan(line),
                    fontSize = 13.sp,
                    lineHeight = if (compact) 22.sp else 20.sp, // revisita: narrow measure breathes; size keeps artifact 13
                )
            }
        }
    }
}

/** `code`, **bold**, *italic* spans — next-match state machine, leftmost wins. */
private fun inlineSpan(line: String): AnnotatedString {
    val codeRe = Regex("""`([^`]+)`""")
    val boldRe = Regex("""\*\*([^*]+)\*\*""")
    val italRe = Regex("""\*([^*]+)\*""")
    return buildAnnotatedString {
        var rest = line
        while (rest.isNotEmpty()) {
            val pick = listOfNotNull(
                codeRe.find(rest)?.let { 0 to it },
                boldRe.find(rest)?.let { 1 to it },
                italRe.find(rest)?.let { 2 to it },
            ).minByOrNull { (_, m) -> m.range.first }
            if (pick == null) {
                append(rest)
                break
            }
            val (kind, m) = pick
            append(rest.substring(0, m.range.first))
            val body = m.groupValues[1]
            when (kind) {
                0 -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF23262C))) {
                    append(body)
                }
                1 -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(body) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) }
            }
            rest = rest.substring(m.range.last + 1)
        }
    }
}

@Composable
private fun CodeBlockCard(
    code: String,
    lang: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    Surface(
        color = Color(0xFF151518), // artifact .code bg (#151518 on Tokyo Night #tn-bg)
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, Color(0xFF23233A), RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lang.ifBlank { "code" },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.heightIn(min = Rhythm.TouchTarget.dp),
                ) {
                    Text(S.copyCode, fontSize = 11.sp)
                }
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 18.sp, // artifact: 11/1.6
                )
            }
        }
    }
}

// ============================================================== composer pill

@Composable
private fun ComposerPill(
    holder: ChatFaceState,
    terminalName: String,
    text: String,
    staged: List<StagedItem>,
    quote: String?,
    mentionPanel: ChatFaceState.MentionPanelState?,
    flight: SendFlight?,
    showAttachMenu: Boolean,
    onDismissMenu: () -> Unit,
    onOpenAttachMenu: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onRemoveStaged: (StagedItem) -> Unit,
    onRemoveQuote: () -> Unit,
    cameraDest: File?,
    setCameraDest: (File?) -> Unit,
) {
    val context = LocalContext.current

    val host = context as? HostRequests
    val filePicker = {
        host?.pickDocuments { uris ->
            if (uris.isNotEmpty()) holder.stageLocalFiles(uris)
            onDismissMenu()
        }
    }

    val cameraLauncher = { intent: android.content.Intent ->
        host?.launchForResult(intent) { resultCode, _ ->
            val f = cameraDest
            setCameraDest(null)
            if (resultCode == android.app.Activity.RESULT_OK && f != null && f.exists()) {
                holder.stageLocalFiles(listOf(Uri.fromFile(f)), mapOf(Uri.fromFile(f) to f.name))
            }
        }
    }

    fun launchCamera() {
        // Prep (dir + FileProvider URI + intent) throws outside the launcher call
        // (misconfigured provider, no camera handler) — guard it too, and say so on
        // the notice line instead of vanishing. Draft text is untouched throughout.
        val dest = runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "chat-camera-" + System.currentTimeMillis() + ".jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", f,
            )
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            f to intent
        }.getOrNull()
        if (dest == null) {
            setCameraDest(null)
            holder.note(S.attachmentCantBePreparedDraftPreserved)
            onDismissMenu()
            return
        }
        val (f, intent) = dest
        setCameraDest(f)
        val launched = runCatching { cameraLauncher(intent) }.isSuccess
        if (!launched) {
            setCameraDest(null)
            holder.note(S.attachmentCantBePreparedDraftPreserved)
        }
        onDismissMenu()
    }

    val chrome = LocalMaestriColors.current
    Column(Modifier.fillMaxWidth()) {
        mentionPanel?.let { panel -> MentionDropdown(holder = holder, panel = panel) }
        flight?.let { f -> FlightBanner(flight = f, onKeepDraft = { holder.dismissFlight() }) }

        // attach menu lives ABOVE the composer pill — below it the menu lays
        // out off-screen (the pill is bottom-anchored; with the keyboard open
        // there is no room below), which read as a dead + button (todo #54).
        // Both widths share this path, so both are fixed by the move.
        if (showAttachMenu) {
            Surface(
                color = LocalMaestriColors.current.canvasChromeElevated,
                shape = cardShape(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Column(Modifier.padding(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { filePicker() },
                            modifier = Modifier.weight(1f),
                        ) { Text(S.attach, fontSize = 12.sp, maxLines = 1) }
                        TextButton(onClick = { launchCamera() }, modifier = Modifier.weight(1f)) {
                            Text(S.camera, fontSize = 12.sp, maxLines = 1)
                        }
                        TextButton(
                            onClick = { holder.stagePaste(); onDismissMenu() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (holder.canPasteImage()) S.pasteImage else "Paste",
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = onDismissMenu, modifier = Modifier.weight(1f)) {
                            Text("Close", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                if (staged.isNotEmpty()) {
                    StagedSlots(staged = staged, onRemove = onRemoveStaged)
                    Spacer(Modifier.height(6.dp))
                }
                quote?.let { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ("> " + q.take(80) + if (q.length > 80) "…" else ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .background(LocalMaestriColors.current.canvasSurface, controlShape())
                                .padding(6.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = onRemoveQuote,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                        ) { IconOnly(Icons.Filled.Close, size = 16.dp) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val addSrc = remember { MutableInteractionSource() }
                    val addPressed by addSrc.collectIsPressedAsState()
                    val addPress by animateFloatAsState(
                        targetValue = if (addPressed) Motion.PressScale else 1f,
                        animationSpec = tween(Motion.Fast, easing = Motion.Ease),
                        label = "add-press",
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer { scaleX = addPress; scaleY = addPress }
                            .clip(CircleShape)
                            .background(chrome.canvasSurface)
                            .border(1.dp, chrome.hairline, CircleShape)
                            .clickable(onClick = onOpenAttachMenu),
                        contentAlignment = Alignment.Center,
                    ) { IconOnly(Icons.Filled.Add, contentDescription = S.attach, size = 18.dp) }
                    // ＠ pill: a literal "@" typed into the input opens the mention selector
                    val atSrc = remember { MutableInteractionSource() }
                    val atPressed by atSrc.collectIsPressedAsState()
                    val atPress by animateFloatAsState(
                        targetValue = if (atPressed) Motion.PressScale else 1f,
                        animationSpec = tween(Motion.Fast, easing = Motion.Ease),
                        label = "at-press",
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer { scaleX = atPress; scaleY = atPress }
                            .clip(CircleShape)
                            .background(chrome.canvasSurface)
                            .border(1.dp, chrome.hairline, CircleShape)
                            .clickable(onClick = { onTextChange(text + "@") }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("@", fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    }
                    // v2.1 revisita (reno): below 480dp the outlined field's box reads
                    // as a second inner surface inside the glass pill — BasicTextField
                    // draws text/cursor/placeholder ONLY, so the pill glass is the one
                    // surface. Tablet keeps the outlined field, untouched.
                    // (All field containers/borders are already Transparent since 78f209c;
                    // this branch removes the box-drawing path entirely on compact.)
                    val compact = LocalConfiguration.current.screenWidthDp < 480
                    if (compact) {
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .border(1.dp, chrome.hairline, RoundedCornerShape(24.dp))
                                .padding(horizontal = 12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 4,
                            decorationBox = { inner ->
                                // revisita: short single-line hint — the full
                                // "Message %s. @ to mention" wraps 3 lines at 393dp.
                                Box(Modifier.padding(vertical = 10.dp)) {
                                    if (text.isEmpty()) {
                                        Text(
                                            "Message $terminalName\u2026",
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    } else {
                        OutlinedTextField(
                            value = text,
                            onValueChange = onTextChange,
                            placeholder = {
                                Text(
                                    S.messageToMentionFmt.format(terminalName),
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            shape = pillShape(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedBorderColor = chrome.hairline,
                                unfocusedBorderColor = chrome.hairline,
                                disabledBorderColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp), // artifact .in min-height 40
                            maxLines = 4,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                    val sendEnabled = flight == null && (text.isNotBlank() || staged.isNotEmpty())
                    Button(
                        onClick = onSend,
                        enabled = sendEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = chrome.accent,
                            contentColor = chrome.onAccent,
                            disabledContainerColor = chrome.cardSunken,
                            disabledContentColor = chrome.secondaryLabel,
                        ),
                    ) {
                        Text(S.send, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

    }
}

@Composable
private fun StagedSlots(staged: List<StagedItem>, onRemove: (StagedItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        staged.forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(min = 88.dp)
                    .background(LocalMaestriColors.current.canvasSurface, controlShape())
                    .padding(6.dp),
            ) {
                Text(
                    item.fileName,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatBytes(item.sizeBytes) + (if (item.upload != null) " · staged" else ""),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onRemove(item) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) { IconOnly(Icons.Filled.Close, size = 12.dp, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Send-flight banner (§3.8 wording); Keep Draft dismisses — never a retry. */
@Composable
private fun FlightBanner(flight: SendFlight, onKeepDraft: () -> Unit) {
    Surface(
        color = when (flight.stage) {
            SendFlight.Stage.UNKNOWN -> Color(0xFF4A3A20)
            SendFlight.Stage.FAILED -> Color(0xFF4A2020)
            else -> LocalMaestriColors.current.canvasChromeElevated
        },
        shape = controlShape(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val busy = flight.stage == SendFlight.Stage.UPLOADING ||
                    flight.stage == SendFlight.Stage.SENDING ||
                    flight.stage == SendFlight.Stage.CORRELATING
                Text(
                    if (busy) "↑ " else "! ",
                    color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
                Text(flight.title(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            flight.body()?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (flight.stage == SendFlight.Stage.UNKNOWN || flight.stage == SendFlight.Stage.FAILED) {
                TextButton(
                    onClick = onKeepDraft,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(S.keepDraft, fontSize = 11.sp) }
            }
        }
    }
}

// ============================================================ mentions dropdown

@Composable
private fun MentionDropdown(holder: ChatFaceState, panel: ChatFaceState.MentionPanelState) {
    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(LocalMaestriColors.current.canvasChromeElevated, cardShape())
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    panel.mode == ChatFaceState.MentionPanelState.Mode.FILES && panel.loading -> S.loadingFiles
                    panel.mode == ChatFaceState.MentionPanelState.Mode.FILES -> S.fileSuggestions
                    else -> S.mentionSuggestions
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (panel.mode == ChatFaceState.MentionPanelState.Mode.MENTIONS && panel.mentions.isNotEmpty()) {
                Text(
                    holder.countMentionSuggestions(panel.mentions.size),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        panel.errorWording?.let { Text(it, fontSize = 11.sp, color = LocalMaestriColors.current.warning) }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (panel.mode == ChatFaceState.MentionPanelState.Mode.MENTIONS) {
                items(panel.mentions) { m ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { holder.insertMention(m) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "@" + m.name,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOfNotNull(
                            m.floorName,
                            m.kind.takeIf { it.isNotBlank() && it != "agent" },
                        )
                        if (sub.isNotEmpty()) {
                            Text(
                                sub.joinToString(" · "),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                items(panel.files) { f ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (f.isDirectory) holder.mentionPanelScope(f) else holder.insertFile(f)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            (if (f.isDirectory) "▾ " else "") + f.name,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            f.relativePath,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (panel.isTruncated) {
                    item {
                        Text(
                            "more results on the host…",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row {
            TextButton(
                onClick = { holder.toggleMentionMode() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
            ) {
                Text(
                    if (panel.mode == ChatFaceState.MentionPanelState.Mode.MENTIONS)
                        S.fileSuggestions
                    else
                        S.filesBackToMentions,
                    fontSize = 11.sp,
                )
            }
            if (panel.mode == ChatFaceState.MentionPanelState.Mode.FILES && panel.filesRoot.isNotBlank()) {
                Text(
                    panel.filesRoot + (panel.filesScope.takeIf { it.isNotBlank() }?.let { "/" + it } ?: ""),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(end = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================ thread switcher

/**
 * 7 fixed slots 0…6 (absent items = empty). Previews are the host's read-only
 * GET …/chat/threads data (no selection, no read-marking; works while locked).
 */
@Composable
private fun ThreadSwitcherSheet(
    holder: ChatFaceState,
    threads: ChatThreads,
    onDismiss: () -> Unit,
) {
    val previews by holder.threadPreviews.collectAsState()
    LaunchedEffect(Unit) { holder.refreshThreadPreviews() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Threads", fontSize = 16.sp) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!threads.canSelect) {
                    Text("Thread switching is locked right now.", fontSize = 11.sp, color = LocalMaestriColors.current.warning)
                    Spacer(Modifier.height(6.dp))
                }
                (0..6).forEach { slot ->
                    val item = threads.items.firstOrNull { it.id == slot }
                    val preview = previews.firstOrNull { it.id == slot }
                    val isActive = slot == threads.activeSlot
                    Surface(
                        color = if (isActive) Color(0xFF24324A) else LocalMaestriColors.current.canvasSurface,
                        shape = controlShape(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(threadColor(item?.color ?: "graphite"), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Slot " + slot + (if (isActive) " · active" else ""),
                                    fontSize = 13.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                val peek = preview?.entries?.lastOrNull()?.text
                                if (!peek.isNullOrBlank()) {
                                    Text(
                                        peek.take(80),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (!isActive && (item?.hasMessages == true || preview != null)) {
                                TextButton(
                                    onClick = { holder.clearSlot(slot) },
                                    enabled = threads.canSelect,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                                ) { Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                            }
                            TextButton(
                                onClick = { holder.selectThread(slot); onDismiss() },
                                enabled = threads.canSelect && !isActive,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            ) { Text("Open", fontSize = 11.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ============================================================ gate + shell + menus

@Composable
private fun FaceGate(onEnterChat: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 24.dp),
        ) {
            Text(
                S.sendAMessageAndTheAgentWillAnswerHere,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onEnterChat) { Text(S.enterChatMode) }
        }
    }
}

@Composable
private fun ShellStrip(preview: List<String>) {
    Surface(
        color = Color(0xFF0C0D10),
        shape = controlShape(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(92.dp),
    ) {
        Column(Modifier.padding(6.dp).verticalScroll(rememberScrollState())) {
            preview.takeLast(6).forEach { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF9FB3A1),
                )
            }
        }
    }
}

@Composable
private fun EntryMenuDialog(
    target: EntryMenuTarget,
    onDismiss: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entry", fontSize = 15.sp) },
        text = { Text(target.copyText().take(160), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextButton(onClick = onQuote) { Text(S.quote, fontSize = 12.sp) } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) } },
    )
}

/**
 * "Attachment preview" — retained bytes rendered large; images decode, other types show
 * the "Attachment: %@" metadata card; Delete removes it from EVERY entry (409 while a
 * cleanup runs → surfaced inline).
 */
@Composable
private fun AttachmentPreviewDialog(
    holder: ChatFaceState,
    target: AttachmentPreviewTarget,
    onDismiss: () -> Unit,
) {
    var bmp by remember(target.attachmentId) { mutableStateOf<Bitmap?>(null) }
    var byteCount by remember(target.attachmentId) { mutableStateOf(-1) }
    LaunchedEffect(target.attachmentId) {
        val bytes = holder.attachmentImageBytes(target.attachmentId)
        if (bytes.isNotEmpty()) {
            byteCount = bytes.size
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.attachmentPreview, fontSize = 14.sp) },
        text = {
            Column {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = target.fileName,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(S.attachmentNamed(target.fileName), fontSize = 13.sp)
                    if (byteCount >= 0) {
                        Text(
                            formatBytes(byteCount.toLong()),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        onDismiss()
                        holder.deleteAttachmentEverywhere(target.attachmentId)
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

