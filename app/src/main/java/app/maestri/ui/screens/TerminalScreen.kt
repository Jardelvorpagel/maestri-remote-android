package app.maestri.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import android.net.Uri
import app.maestri.wire.FeedItem
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.TerminalThemes
import app.maestri.ui.Style.pillShape
import app.maestri.ui.settings.ChatDensityStore
import app.maestri.ui.settings.ChatTextSizeStore
import app.maestri.ui.settings.LocalAppLocked
import androidx.lifecycle.viewmodel.compose.viewModel
import app.maestri.live.DataStorePiRelayStore
import app.maestri.live.LiveComposeState
import app.maestri.live.LiveFeedContent
import app.maestri.live.LiveSendStatus
import app.maestri.live.LiveStagedFile
import app.maestri.live.LiveViewModel
import app.maestri.live.LiveViewModelFactory
import app.maestri.live.rememberLiveAttachHandler
import app.maestri.live.MAX_ATTACH_BYTES
import app.maestri.live.MAX_STAGED_FILES
import app.maestri.live.PiRelayUpload
import app.maestri.live.formatAttachmentSize
import app.maestri.live.stagePickedUris
import app.maestri.ui.settings.TerminalFontStore
import app.maestri.ui.settings.TerminalScrollSpeedStore
import app.maestri.ui.settings.TerminalViewModeStore
import app.maestri.wire.ChatApi
import app.maestri.ui.Style.BackLabel

/**
 * Terminal detail: the terminal face IS the xterm live stream (key rail + wide input
 * line), with the Pi relay chat as a sibling view mode (Terminal | Chat), and
 * approve/reject for pendingPrompt items.
 *
 * Contract wiring:
 *  - POST …/seen on open
 *  - feed-socket presence of the open node (capability "presence")
 *  - live stream resets its emulator on every geometry frame
 *  - resize requests are clamped to 20..250 cols × 5..120 rows
 *  - approve/reject clear attention on the host; 409s surface as inline notices
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    session: WireSession,
    terminalId: String,
    terminalName: String,
    wsId: String,
    onBack: () -> Unit,
    onEnterChat: ((String, String) -> Unit)? = null,
    startView: String? = null,
) {
    val scope = rememberCoroutineScope()
    // Aa stepper (design v2 §5.4): persisted 10–20 terminal type scale.
    val ctx = LocalContext.current
    val fontStore = remember(ctx) { TerminalFontStore(ctx.applicationContext) }
    // View mode (Terminal | Chat) persisted per terminal, same DataStore idiom as
    // the font store: survives rotation (StateFlow) and re-entry (DataStore).
    val viewModeStore = remember(ctx) { TerminalViewModeStore(ctx.applicationContext) }
    val viewMode by viewModeStore.mode(terminalId).collectAsState()
    val inChat = viewMode == TerminalViewModeStore.CHAT
    // Deep-link seed (Settings Live row): consumed once per entry so a rotation
    // after switching back to Terminal does not yank the user into Chat again.
    var startConsumed by rememberSaveable(terminalId) { mutableStateOf(false) }
    LaunchedEffect(terminalId, startView) {
        if (!startConsumed && startView == TerminalViewModeStore.CHAT) {
            startConsumed = true
            viewModeStore.setMode(terminalId, TerminalViewModeStore.CHAT)
        }
    }
    // Relay feed VM hoisted above the mode switch: the socket stays open while
    // flipping Terminal <-> Chat, and the entry-scoped key keeps one VM per terminal.
    val relayStore = remember(ctx) { DataStorePiRelayStore(ctx.applicationContext) }
    val liveVm: LiveViewModel = viewModel(
        key = "live-$terminalId",
        factory = LiveViewModelFactory(relayStore),
    )
    val fontSp by fontStore.sizeSp.collectAsState()
    // Chat-face display settings: own text steps (12/13/15) and density, same
    // DataStore idiom as the terminal settings above.
    val chatTextSizeStore = remember(ctx) { ChatTextSizeStore(ctx.applicationContext) }
    val chatDensityStore = remember(ctx) { ChatDensityStore(ctx.applicationContext) }
    val chatTextSize by chatTextSizeStore.sizeSp.collectAsState()
    val chatCompact by chatDensityStore.compact.collectAsState()
    var showDisplayConfig by remember { mutableStateOf(false) }
    // Scroll speed: how many rows the content moves per row of finger travel. A phone
    // flick carries further than the finger, so 1x (the terminal-native feel) reads as
    // sluggish on a long transcript.
    val speedStore = remember(ctx) { TerminalScrollSpeedStore(ctx.applicationContext) }
    val scrollSpeed by speedStore.speedX.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    val snapshot by session.feed.snapshot.collectAsState()
    val feedState by session.feed.state.collectAsState()
    val feedError by session.feed.lastError.collectAsState()

    val streamGeometry by session.terminalStream.geometry.collectAsState()

    var notice by remember { mutableStateOf<String?>(null) }
    // The wide input line under the pty: same raw byte path as the key rail.
    var termInput by remember { mutableStateOf("") }
    // Files staged for the next send: they travel to the host first, so the terminal line
    // can hand the agent an id the host really knows.
    var stagedFiles by remember { mutableStateOf<List<LiveStagedFile>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var seededFallback by remember { mutableStateOf(false) }
    // Capabilities up here: the renderer and the header both read them.
    val caps = session.serverInfo
    val canStream = caps?.has("terminalStreaming") == true
    val canResize = caps?.has("terminalResize") == true
    val isOwner = caps?.role == "owner"

    val locked = LocalAppLocked.current

    // seen + presence on open (held while the lock gate covers the screen — no
    // host-visible markers before unlock)
    LaunchedEffect(terminalId, locked) {
        if (locked) return@LaunchedEffect
        session.seen(terminalId)
        val node = snapshot?.items
            ?.filterIsInstance<FeedItem.TerminalFeedItem>()
            ?.firstOrNull { it.terminal.id == terminalId }?.terminal?.nodeId
        if (session.serverInfo?.has("presence") == true && session.feedState.value == SocketState.OPEN) {
            node?.let { session.feed.sendPresence(it) }
        }
    }

    // If the socket has no snapshot for this workspace (e.g. deep entry), fetch one.
    LaunchedEffect(snapshot, wsId) {
        if (snapshot?.workspaceId != wsId && !seededFallback) {
            seededFallback = true
            runCatching {
                val p = session.pairing.value ?: return@LaunchedEffect
                session.feed.reset()
                session.feed.seed(session.feedOnce(p, wsId, null))
            }
        }
    }

    // The xterm face owns opening the stream (its byte tap is attached before open);
    // this effect only tears the socket down when the face is not on screen: the stream
    // stays open while the Terminal face is visible (and the host supports it) and unlocked.
    LaunchedEffect(terminalId, canStream, locked, inChat) {
        if (!(canStream && !locked && !inChat)) session.terminalStream.close(terminalId)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (session.serverInfo?.has("presence") == true) session.feed.sendPresence(null)
            session.terminalStream.close(terminalId)
        }
    }

    // ---------------------------------------------------------------- actions

    fun markStaged(key: String, transform: (LiveStagedFile) -> LiveStagedFile) {
        stagedFiles = stagedFiles.map { if (it.key == key) transform(it) else it }
    }

    /**
     * Uploads the staged files to the host's relay and returns the path it stored them
     * under. The relay answers `{id,name,bytes,path}` — a real path on the machine that
     * runs the agent — so the terminal line hands over something the agent can open.
     *
     * Nothing is sent when an upload fails: the files stay staged so the user can retry.
     */
    suspend fun uploadStaged(files: List<LiveStagedFile>): List<StagedUpload>? {
        val relayUrl = relayStore.url.value
        if (relayUrl.isBlank()) {
            notice = "Not paired to a relay — nothing sent."
            return null
        }
        val relayToken = relayStore.token.value
        val uploader = PiRelayUpload()
        val out = mutableListOf<StagedUpload>()
        for (f in files) {
            val existing = f.uploadedPath
            if (existing != null) {
                out += StagedUpload(ptySafe(f.name), f.sizeBytes, existing)
                continue
            }
            markStaged(f.key) { it.copy(uploading = true) }
            val outcome = runCatching {
                uploader.upload(relayUrl, relayToken, ptySafe(f.name), f.bytes)
            }
            val path = outcome.getOrNull()?.path?.takeIf { it.isSafeHostPath() }
            markStaged(f.key) { it.copy(uploading = false, uploadedPath = path) }
            if (outcome.isFailure || path == null) {
                val cause = outcome.exceptionOrNull()
                notice = "Upload failed for ${f.name}: " +
                    (cause?.message ?: "no usable path on the host") +
                    if (cause is java.net.SocketTimeoutException) {
                        " — the host relay is not reachable from this device; the phone " +
                            "needs tailnet access to the relay port."
                    } else {
                        ""
                    } +
                    " Files kept, nothing sent."
                return null
            }
            out += StagedUpload(ptySafe(f.name), f.sizeBytes, path)
        }
        return out
    }

    /** Stages the picked files for the next send, with the chat face's caps and messages. */
    fun stageAttachments(uris: List<Uri>) {
        val (picked, unreadable) = stagePickedUris(ctx.applicationContext, uris)
        val over = picked.filter { it.sizeBytes > MAX_ATTACH_BYTES }
        val fitting = picked.filter { it.sizeBytes <= MAX_ATTACH_BYTES }
        val room = (MAX_STAGED_FILES - stagedFiles.size).coerceAtLeast(0)
        stagedFiles = stagedFiles + fitting.take(room)
        val bits = mutableListOf<String>()
        if (unreadable.isNotEmpty()) {
            bits += "couldn't read: " + unreadable.take(3).joinToString(", ")
        }
        if (over.isNotEmpty()) {
            bits += "over 8 MB: " + over.map { it.name }.take(3).joinToString(", ")
        }
        if (fitting.size > room) bits += "room for 8 max"
        if (bits.isNotEmpty()) notice = "Not attached — " + bits.joinToString("; ") + "."
    }

    /** Empties the wide input line into the pty. Send SUBMITS: the text plus the Enter byte
     *  ("\r") the rail's Enter key sends, so one tap on the phone runs the command. */
    fun sendTyped() {
        if (uploading) return
        // trimEnd, not trim: leading spaces are real shell indentation.
        val text = termInput.trimEnd()
        val files = stagedFiles
        if (text.isBlank() && files.isEmpty()) return
        if (files.isEmpty()) {
            session.terminalStream.sendInput(text + "\r")
            termInput = ""
            return
        }
        // Attachments go to the host FIRST: the pty can only be handed an id the host
        // already knows, so the upload has to finish before the line is typed.
        scope.launch {
            uploading = true
            val uploaded = try {
                if (session.terminalStream.state.value != SocketState.OPEN) {
                    notice = "Terminal stream is not live — nothing sent, files kept."
                    return@launch
                }
                uploadStaged(files)
            } finally {
                uploading = false
            }
            if (uploaded == null) return@launch
            val line = buildString {
                if (text.isNotBlank()) {
                    append(text)
                    append(' ')
                }
                uploaded.forEach {
                    append("[attachment: ${it.name} (${formatAttachmentSize(it.sizeBytes)}) path=${it.path}] ")
                }
            }.trimEnd()
            session.terminalStream.sendInput(line + "\r")
            // Only the text that was actually sent leaves the field: whatever the user
            // typed while the upload was running stays.
            if (termInput.trimEnd() == text) termInput = ""
            stagedFiles = emptyList()
        }
    }

    fun approveReject(yes: Boolean) {
        android.util.Log.d("QAFeed", "terminal screen tap: ${if (yes) "Approve" else "Reject"} term=$terminalId")
        scope.launch {
            try {
                if (yes) session.approve(terminalId) else session.reject(terminalId)
                android.util.Log.d("QAFeed", "terminal screen ${if (yes) "Approve" else "Reject"} returned ok")
            } catch (e: app.maestri.wire.ApiException) {
                notice = "HTTP ${e.status}: ${e.message}"
            } catch (e: Exception) {
                android.util.Log.e("QAFeed", "terminal screen approve/reject threw: $e")
                notice = "failed: ${e.message}"
            }
        }
    }

    /** Chat key on the quickkey rail: the same enable-chat handshake the nav button runs. */
    fun enterChatMode() {
        scope.launch {
            try {
                ChatApi(session).chatSetFace(terminalId, true, null)
                viewModeStore.setMode(terminalId, TerminalViewModeStore.CHAT)
            } catch (e: Exception) {
                notice = "chat mode failed: ${e.message}"
            }
        }
    }

    // ---------------------------------------------------------------- layout

    val card: TerminalCard? = snapshot?.terminalCards?.firstOrNull { it.id == terminalId }
    val pending: Boolean = snapshot?.pendingPromptIds?.contains(terminalId) == true
    val pendingPrompt: String? =
        snapshot?.items
            ?.filterIsInstance<FeedItem.PendingPromptFeedItem>()
            ?.firstOrNull { it.terminal.id == terminalId }?.prompt

    val imeUp = WindowInsets.isImeVisible
    val streamState by session.terminalStream.state.collectAsState()
    var chipOpen by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(terminalId, inChat, imeUp, streamState, configuration.orientation, configuration.screenWidthDp, locked) {
        chipOpen = false
        showDisplayConfig = false
    }
    BackHandler(enabled = chipOpen) {
        chipOpen = false
    }
    // Typing needs the grid, not the chrome: the IME leaves ~140dp here, so the header
    // steps aside while the keyboard is up (system back closes the IME first).
    val chromeTight = imeUp && !inChat && !fullscreen
    // todo #57: edge-to-edge + adjustResize delivers the IME as an inset — the window does
    // NOT shrink (reno_kb2 proves it: the viewport extends under Gboard) — so the screen
    // consumes it here. One shared fix lifting the terminal input line + rail AND the agent
    // prompt composer (Chat face) above the keyboard. Closed keyboard → zero inset → identical.
    Column(
        Modifier.fillMaxSize().background(TerminalThemes.TokyoNight.background)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    ) {
        // Header: responsive below a 480dp breakpoint (todo #42, Reno 411dp case).
        // The tablet path is the ARRANGEMENT-IDENTICAL row it has always been; the
        // compact path re-flows chips + controls onto a second, right-aligned row and
        // gives the Enter-Chat pill a measured minimum width so it renders on one line.
        val backBtn: @Composable () -> Unit = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        val titleBlock: @Composable RowScope.() -> Unit = {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(attentionDot(card, pending), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        terminalName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    buildString {
                        append("${if (isOwner) "owner" else "guest · reads only"} · ${card?.status ?: "?"}")
                        card?.accountName?.let { append(" · $it") }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val connChip: @Composable () -> Unit = {
            val pulse = rememberInfiniteTransition(label = "terminalLive")
            val opacity by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "terminalLiveOpacity",
            )
            val live = streamState == SocketState.OPEN
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).alpha(if (live) opacity else 1f)
                    .background(if (live) TerminalThemes.TokyoNight.green else TerminalThemes.TokyoNight.amber, CircleShape))
                Text(if (live) "live" else "offline", fontSize = 11.sp, color = TerminalThemes.TokyoNight.foreground)
            }
        }
        // Mono workspace chip (design v2 §4-03): floor name when the card carries one,
        // else the short workspace id — never a styled/serif value.
        val floorChip: @Composable () -> Unit = {
            Text(
                (card?.floorName?.takeIf { it.isNotBlank() } ?: wsId.take(8)),
                fontFamily = MaestriType.Mono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .background(LocalMaestriColors.current.canvasSurface, pillShape())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val fullscreenBtn: @Composable () -> Unit = {
            if (canStream) {
                IconButton(onClick = { fullscreen = !fullscreen }) {
                    Text(
                        if (fullscreen) "\u2921" else "\u2922",
                        fontSize = 18.sp,
                        modifier = Modifier.semantics {
                            contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen"
                        },
                    )
                }
            }
        }
        // Scroll speed is its own row with its own label on BOTH faces: shared
        // store, never merged into density, never dropped.
        val scrollSpeedRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scroll speed · ${scrollSpeed}×", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = scrollSpeed > TerminalScrollSpeedStore.MIN_X,
                    onClick = { speedStore.setSpeed(scrollSpeed - 1) },
                ) { Text("−") }
                TextButton(
                    enabled = scrollSpeed < TerminalScrollSpeedStore.MAX_X,
                    onClick = { speedStore.setSpeed(scrollSpeed + 1) },
                ) { Text("+") }
            }
        }
        if (!chromeTight && !fullscreen) {
            Row(
                Modifier.fillMaxWidth().height(50.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                backBtn()
                titleBlock()
                connChip()
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                floorChip()
                Spacer(Modifier.weight(1f))
                fullscreenBtn()
                Box {
                    IconButton(onClick = { showDisplayConfig = !showDisplayConfig }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Display settings")
                    }
                    DropdownMenu(
                        expanded = showDisplayConfig,
                        onDismissRequest = { showDisplayConfig = false },
                        modifier = Modifier.width(248.dp),
                        containerColor = TerminalThemes.TokyoNight.panel,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (inChat) {
                                Text("Text size · $chatTextSize", fontSize = 12.sp)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    ChatTextSizeStore.STEPS.forEachIndexed { index, size ->
                                        SegmentedButton(
                                            selected = chatTextSize == size,
                                            onClick = {
                                                chatTextSizeStore.setSize(size)
                                                showDisplayConfig = false
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index, ChatTextSizeStore.STEPS.size),
                                        ) { Text("$size") }
                                    }
                                }
                                Text("Density", fontSize = 12.sp)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    val densities = listOf("Roomy" to false, "Compact" to true)
                                    densities.forEachIndexed { index, (label, compact) ->
                                        SegmentedButton(
                                            selected = chatCompact == compact,
                                            onClick = {
                                                chatDensityStore.setCompact(compact)
                                                showDisplayConfig = false
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index, densities.size),
                                        ) { Text(label) }
                                    }
                                }
                                scrollSpeedRow()
                            } else {
                                Text("Font size · $fontSp", fontSize = 12.sp)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    val sizes = listOf(TerminalFontStore.MIN_SP, 13, TerminalFontStore.MAX_SP).distinct()
                                    sizes.forEachIndexed { index, size ->
                                        SegmentedButton(
                                            selected = fontSp == size,
                                            onClick = {
                                                fontStore.setSize(size)
                                                showDisplayConfig = false
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index, sizes.size),
                                        ) { Text("$size") }
                                    }
                                }
                                scrollSpeedRow()
                            }
                        }
                    }
                }
            }
        }
        if (fullscreen) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                fullscreenBtn()
            }
        }

        if (inChat) {
            val liveDraft by liveVm.draft.collectAsState()
            val liveStaged by liveVm.staged.collectAsState()
            val liveComposeState by liveVm.composeState.collectAsState()
            val livePending by liveVm.pending.collectAsState()
            val liveReady = liveComposeState is LiveComposeState.Ready
            val liveDisabledReason = (liveComposeState as? LiveComposeState.Disabled)?.reason
            val liveSending = livePending.any { it.status == LiveSendStatus.SENDING }
            val liveCanSend = liveReady && (liveDraft.isNotBlank() || liveStaged.isNotEmpty())
            val liveMeta = liveDisabledReason
                ?: if (liveStaged.isNotEmpty())
                    "${liveStaged.size} file${if (liveStaged.size == 1) "" else "s"}"
                else null
            val liveOnAttach = rememberLiveAttachHandler(liveVm)
            LiveFeedContent(
                liveVm,
                showComposer = false,
                onOpenTerminal = { viewModeStore.setMode(terminalId, TerminalViewModeStore.TERMINAL) },
            )
            if (!fullscreen) {
                TerminalComposer(
                    placeholder = "Message Pi…",
                    value = liveDraft,
                    onChange = { liveVm.updateDraft(it) },
                    onSend = { if (liveCanSend) liveVm.send() },
                    onAttach = liveOnAttach,
                    canSend = liveCanSend,
                    busy = liveSending,
                    meta = liveMeta,
                    chatSelected = true,
                    onChat = ::enterChatMode,
                    onRun = { viewModeStore.setMode(terminalId, TerminalViewModeStore.TERMINAL) },
                    staged = liveStaged,
                    onRemoveStaged = { liveVm.removeStaged(it) },
                    focusRequester = inputFocusRequester,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    attachEnabled = liveReady,
                )
            }
        } else {
        if (pendingPrompt != null && !fullscreen && !imeUp) {
            Spacer(Modifier.height(10.dp))
            PendingPromptCard(card ?: TerminalCard(
                id = terminalId, nodeId = "", name = terminalName, agentType = "", icon = null,
                color = null, status = "", floorId = null, floorName = null, isRunning = true,
                cols = 80, rows = 24, preview = emptyList(), isManager = false,
                needsAttention = true, isLive = false, isActive = null, accountName = null,
                maestroRequestMessage = null,
            ), pendingPrompt, onOpen = { _, _ -> }, session, scope)
        }

        notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = LocalMaestriColors.current.warning, fontSize = 12.sp)
        }

        if (!canStream) {
            Text(
                "this host has no terminal streaming capability — the pty face stays blank",
                color = LocalMaestriColors.current.warning,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
        }

        if (!canResize) {
            streamGeometry?.let { g ->
                Text(
                    "pty ${g.cols} cols — terminal resize is unavailable on this host, so " +
                        "line tails past the screen edge are unreachable",
                    color = LocalMaestriColors.current.warning,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        val chipCols = card?.cols ?: streamGeometry?.cols ?: 80
        val chipRows = card?.rows ?: streamGeometry?.rows ?: 24
        val reconnecting = streamState == SocketState.CONNECTING ||
            (streamState == SocketState.CLOSED && card?.isRunning == true)
        val chipStatusLabel = when {
            reconnecting -> "Reconnecting"
            streamState == SocketState.CLOSED -> "closed"
            else -> "${card?.status?.ifBlank { "—" } ?: "—"} · ${chipCols}×${chipRows}"
        }
        val chipDotColor = when {
            reconnecting -> TerminalThemes.TokyoNight.amber
            streamState != SocketState.OPEN -> TerminalThemes.TokyoNight.dim
            pending || card?.needsAttention == true -> TerminalThemes.TokyoNight.red
            else -> TerminalThemes.TokyoNight.green
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TerminalWebView(
                session = session,
                terminalId = terminalId,
                fontSizeSp = fontSp,
                canResize = canResize,
                scrollSpeed = scrollSpeed,
                // canStream also gates the dial: the face stays composed (and explains itself
                // above) but never opens a stream this host cannot serve.
                enabled = canStream && !locked && !inChat,
                modifier = Modifier.fillMaxSize(),
            )
            if (!fullscreen) {
                TerminalChip(
                    status = TerminalChipStatus(label = chipStatusLabel, dotColor = chipDotColor),
                    expanded = chipOpen,
                    imeVisible = imeUp,
                    enabled = streamState == SocketState.OPEN && !locked,
                    onToggle = { chipOpen = !chipOpen },
                    onKey = { key ->
                        chipOpen = false
                        session.terminalStream.sendInput(key.bytes.toString(Charsets.UTF_8))
                    },
                    onChat = {
                        chipOpen = false
                        enterChatMode()
                    },
                    onShowKeyboard = {
                        chipOpen = false
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .padding(12.dp).zIndex(6f),
                )
            }
        }
        if (!fullscreen) {
            val composerMeta = when {
                uploading -> "uploading…"
                streamState != SocketState.OPEN -> "offline"
                stagedFiles.isNotEmpty() ->
                    "${stagedFiles.size} file${if (stagedFiles.size == 1) "" else "s"}"
                else -> null
            }
            TerminalComposer(
                placeholder = "Ask $terminalName to fix it…",
                value = termInput,
                onChange = { termInput = it },
                onSend = ::sendTyped,
                onAttach = ::stageAttachments,
                canSend = streamState == SocketState.OPEN && !locked &&
                    (termInput.isNotBlank() || stagedFiles.isNotEmpty()) && !uploading,
                busy = uploading,
                meta = composerMeta,
                chatSelected = inChat,
                onChat = ::enterChatMode,
                onRun = { viewModeStore.setMode(terminalId, TerminalViewModeStore.TERMINAL) },
                staged = stagedFiles,
                onRemoveStaged = { key -> stagedFiles = stagedFiles.filter { it.key != key } },
                focusRequester = inputFocusRequester,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }

        // Chat and terminal faces share one composer: TerminalScreen hosts TerminalComposer
        // on both faces (terminal PTY send vs. LiveViewModel relay send), with the Chat/Run
        // segment pair as the explicit two-way face switch. The standalone LiveScreen route
        // keeps LiveComposerBar (LiveComposer.kt:35); the embedded chat mode here hides it
        // via LiveFeedContent(showComposer = false) so there is exactly one composer band
        // and no path can enter chat without a Run way back.
        }
    }
}

// --------------------------------------------------------------------------- views

/** A file that reached the host: the path the host stored it under. */
private data class StagedUpload(
    val name: String,
    val sizeBytes: Long,
    val path: String,
)

private fun String.isSafeHostPath(): Boolean =
    isNotBlank() && startsWith("/") && length < 512 && all { c ->
        c.isLetterOrDigit() || c in "./_-"
    }

/**
 * Keeps a value safe to type into the pty: the line the user sends goes straight to the
 * host's shell, so nothing in it may carry control characters. Letters of any alphabet are
 * kept; everything else becomes an underscore.
 */
private fun ptySafe(value: String): String {
    val cleaned = value.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_' }
        .joinToString("")
        .take(64)
    return cleaned.ifBlank { "file" }
}



@Composable
private fun attentionDot(t: TerminalCard?, pending: Boolean): Color = when {
    t == null -> Color.Gray
    pending || t.needsAttention -> MaterialTheme.colorScheme.error
    t.isActive == true -> MaterialTheme.colorScheme.primary
    t.isLive -> LocalMaestriColors.current.warning
    else -> Color.Gray
}

/**
 * The terminal's own attachment chips: the chat face's version draws an inline image
 * preview, and a couple of those push the input line off the screen on a 411dp phone, so
 * this one lists the files and nothing else.
 */
@Composable
internal fun TerminalStagedChips(files: List<LiveStagedFile>, onRemove: (String) -> Unit) {
    val colors = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 132.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        files.forEach { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.cardSunken, pillShape())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    file.name,
                    fontFamily = MaestriType.Mono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatAttachmentSize(file.sizeBytes),
                    fontFamily = MaestriType.Mono,
                    fontSize = 10.sp,
                    color = colors.secondaryLabel,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        file.uploading -> "…"
                        file.uploadedPath != null -> "✓"
                        else -> "×"
                    },
                    fontFamily = MaestriType.Mono,
                    fontSize = 12.sp,
                    color = colors.secondaryLabel,
                    modifier = Modifier.clickable { onRemove(file.key) },
                )
            }
        }
    }
}

