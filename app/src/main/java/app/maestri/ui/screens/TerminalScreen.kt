package app.maestri.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import app.maestri.ui.Style.Gemini
import app.maestri.ui.Style.drawAurora
import androidx.compose.ui.draw.drawBehind
import app.maestri.ui.Style.GeminiModelSelector
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
import app.maestri.live.RelaySettingsDialog
import app.maestri.live.RelayState
import app.maestri.live.rememberLiveAttachHandler
import app.maestri.live.MAX_ATTACH_BYTES
import app.maestri.live.MAX_STAGED_FILES
import app.maestri.live.PiRelayUpload
import app.maestri.live.composeMemoText
import app.maestri.live.formatAttachmentSize
import app.maestri.live.stagePickedUris
import app.maestri.live.transcribeMemo
import app.maestri.live.voiceNoteStagedFile
import app.maestri.live.rememberVoiceNote
import app.maestri.live.resolveTranscript
import app.maestri.ui.settings.TerminalFontStore
import app.maestri.ui.settings.TerminalScrollSpeedStore
import app.maestri.ui.settings.TerminalViewModeStore
import app.maestri.wire.ChatApi
import app.maestri.ui.Style.BackLabel

private val BackShape = RoundedCornerShape(12.dp)
private val BackTouchShape = RoundedCornerShape(16.dp)
private val SubBarShape = RoundedCornerShape(12.dp)

/** design v2 Display settings glyph: two rails, two knobs (24x24, stroke 2). */
private fun PathBuilder.knob(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
}

private val SlidersIcon: ImageVector by lazy {
    ImageVector.Builder("sliders", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fillAlpha = 0f,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 7f)
            horizontalLineTo(13f)
            moveTo(17f, 7f)
            horizontalLineTo(20f)
            moveTo(4f, 17f)
            horizontalLineTo(7f)
            moveTo(11f, 17f)
            horizontalLineTo(20f)
            knob(15f, 7f, 2f)
            knob(9f, 17f, 2f)
        }
    }.build()
}

private val CornersExpand: ImageVector by lazy {
    ImageVector.Builder("corners-expand", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fillAlpha = 0f,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(9f, 4f)
            horizontalLineTo(5f)
            arcTo(1f, 1f, 0f, false, false, 4f, 5f)
            verticalLineTo(9f)
            moveTo(15f, 4f)
            horizontalLineTo(19f)
            arcTo(1f, 1f, 0f, false, true, 20f, 5f)
            verticalLineTo(9f)
            moveTo(9f, 20f)
            horizontalLineTo(5f)
            arcTo(1f, 1f, 0f, false, true, 4f, 19f)
            verticalLineTo(15f)
            moveTo(15f, 20f)
            horizontalLineTo(19f)
            arcTo(1f, 1f, 0f, false, false, 20f, 19f)
            verticalLineTo(15f)
        }
    }.build()
}

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
        factory = LiveViewModelFactory(relayStore, terminalId),
    )
    // The model / thinking picker lives in the chat top bar, so its state is read here.
    val geminiPreset by liveVm.gemini.collectAsState()
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
    // Voice memo: the take becomes one more staged attachment, so nothing new touches the
    // upload path. The recorder, tick, cap and permission flow live in rememberVoiceNote.
    val voice = rememberVoiceNote(
        onClip = { name, bytes ->
            val memo = voiceNoteStagedFile(name, bytes)
            if (inChat) {
                liveVm.stageFiles(listOf(memo))
            } else if (stagedFiles.size >= MAX_STAGED_FILES) {
                notice = "Not attached — room for $MAX_STAGED_FILES max."
            } else {
                stagedFiles = stagedFiles + memo
            }
            // Transcribe after staging: the memo is sendable immediately, the transcript
            // lands on it when the on-device recognizer answers (or never, if it can't).
            scope.launch {
                val transcript = transcribeMemo(ctx.applicationContext, bytes) ?: return@launch
                if (inChat) {
                    liveVm.setTranscript(memo.key, transcript)
                } else {
                    stagedFiles = stagedFiles.map {
                        if (it.key == memo.key) it.copy(transcript = transcript) else it
                    }
                }
            }
        },
        onNotice = { notice = it },
    )
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
                out += StagedUpload(ptySafe(f.name), f.sizeBytes, existing, resolveTranscript(f.transcript, null))
                continue
            }
            markStaged(f.key) { it.copy(uploading = true) }
            val outcome = runCatching {
                uploader.upload(relayUrl, relayToken, ptySafe(f.name), f.bytes, terminalId)
            }
            val attachment = outcome.getOrNull()
            val path = attachment?.path?.takeIf { it.isSafeHostPath() }
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
            out += StagedUpload(
                ptySafe(f.name),
                f.sizeBytes,
                path,
                resolveTranscript(f.transcript, attachment?.transcript),
            )
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
        // Set before the dispatch, not inside it: a second tap in the same frame used to slip
        // past the guard while the coroutine was still queued.
        uploading = true
        scope.launch {
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
            // The memo's transcript rides the same line, in the same marker vocabulary;
            // the pty takes one line, so the separator is a space rather than a blank line.
            val memoText = composeMemoText(text, uploaded.firstNotNullOfOrNull { it.transcript }, " ")
            val line = buildString {
                if (memoText.isNotBlank()) {
                    append(memoText)
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
    var chipManualClose by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(terminalId, inChat, configuration.orientation, configuration.screenWidthDp, locked) {
        chipOpen = false
        chipManualClose = false
        showDisplayConfig = false
    }
    BackHandler(enabled = chipOpen) {
        chipOpen = false
        chipManualClose = true
    }
    // System back takes the same door as the topbar Voltar — including its fallback
    // when this route was restored as the stack root (a bare pop would be a no-op).
    BackHandler(enabled = !chipOpen && !locked) { onBack() }
    LaunchedEffect(imeUp, inChat, locked) {
        if (imeUp) {
            chipOpen = false
            chipManualClose = false
        } else if (!inChat && !locked && !chipManualClose) {
            chipOpen = true
        }
    }
    // Typing needs the grid, not the chrome: the IME leaves ~140dp here, so the header
    // steps aside while the keyboard is up (system back closes the IME first).
    val chromeTight = imeUp && !inChat && !fullscreen
    // todo #57: edge-to-edge + adjustResize delivers the IME as an inset — the window does
    // NOT shrink (reno_kb2 proves it: the viewport extends under Gboard) — so the screen
    // consumes it here. One shared fix lifting the terminal input line + rail AND the agent
    // prompt composer (Chat face) above the keyboard. Closed keyboard → zero inset → identical.
    Column(
        Modifier.fillMaxSize().background(if (inChat) Gemini.Canvas else TerminalThemes.TokyoNight.background)
            // The chat face's wash covers the WHOLE surface — header, feed, composer and mode
            // toggle — so no edge of it can show. Drawn behind the column's children, gated to the
            // chat face so the terminal keeps its own background untouched.
            .drawBehind { if (inChat) drawAurora(progress = 1f) }
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    ) {
        // Header: responsive below a 480dp breakpoint (todo #42, Reno 411dp case).
        // The tablet path is the ARRANGEMENT-IDENTICAL row it has always been; the
        // compact path re-flows chips + controls onto a second, right-aligned row and
        // gives the Enter-Chat pill a measured minimum width so it renders on one line.
        val backBtn: @Composable () -> Unit = {
            // Same shape as the composer's AttachButton on purpose: clip -> clickable ->
            // semantics{desc, role} is the one form that uiautomator merges into a single
            // android.widget.Button carrying ACTION_CLICK. The outer clip is inert
            // (r=16dp on a 48dp box) and must never tighten to the 44dp box's own
            // 12dp corners: a CircleShape here sliced all four of them into a blob.
            Box(
                Modifier.size(48.dp)
                    .clip(BackTouchShape)
                    .clickable(enabled = true) { onBack() }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Voltar"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(44.dp).clip(BackShape)
                        .background(Color(0xFF232220), BackShape)
                        .border(1.dp, Color(0x24B0AEA5), BackShape)
                        // nothing of the paint box may reach the a11y tree: any child
                        // semantics here split the node into View + orphan role Button.
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    // Drawn, not an Icon: a semantics-emitting child splits the a11y
                    // node (clickable View + orphan role Button) instead of merging.
                    Canvas(Modifier.size(18.dp)) {
                        val p = Path().apply {
                            moveTo(size.width * 0.625f, size.height * 0.25f)
                            lineTo(size.width * 0.375f, size.height * 0.5f)
                            lineTo(size.width * 0.625f, size.height * 0.75f)
                        }
                        drawPath(
                            p,
                            Color(0xFFD97B54),
                            style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
        val titleBlock: @Composable RowScope.() -> Unit = {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(attentionDot(card, pending), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        terminalName,
                        fontSize = 21.sp,
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
        // Chat face only: the relay state lives here (a dot in the header) instead of a
        // permanent card in the feed. Tap opens the settings the card used to show.
        val relayChip: @Composable () -> Unit = {
            val relay by liveVm.relayState.collectAsState()
            val relayUrl by liveVm.url.collectAsState()
            val relayToken by liveVm.token.collectAsState()
            var relaySettingsOpen by remember { mutableStateOf(false) }
            val palette = LocalMaestriColors.current
            val relayDot = when (relay) {
                RelayState.Open -> palette.positive
                RelayState.Connecting -> palette.warning
                is RelayState.Error -> palette.errorText
                RelayState.Disconnected -> palette.secondaryLabel
            }
            val relayLabel = when (relay) {
                RelayState.Open -> "relay live"
                RelayState.Connecting -> "relay …"
                is RelayState.Error -> "relay error"
                RelayState.Disconnected -> "relay off"
            }
            Row(
                Modifier.padding(horizontal = 12.dp)
                    .clickable(enabled = true) { relaySettingsOpen = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Relay settings"
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(relayDot, CircleShape))
                Text(relayLabel, fontSize = 11.sp, color = TerminalThemes.TokyoNight.foreground)
            }
            if (relaySettingsOpen) {
                RelaySettingsDialog(
                    url = relayUrl,
                    token = relayToken,
                    state = relay,
                    onUrl = { liveVm.setUrl(it) },
                    onToken = { liveVm.setToken(it) },
                    onConnect = { liveVm.connect() },
                    onDisconnect = { liveVm.disconnect() },
                    onDismiss = { relaySettingsOpen = false },
                )
            }
        }
        // Mono workspace chip (design v2 §4-03): floor name when the card carries one,
        // else the short workspace id — never a styled/serif value.
        val floorChip: @Composable () -> Unit = {
            Text(
                (card?.floorName?.takeIf { it.isNotBlank() } ?: wsId.take(8)),
                fontFamily = MaestriType.Mono,
                fontSize = 12.5.sp,
                color = Color(0xFFFAF9F5),
                maxLines = 1,
                modifier = Modifier
                    .background(Color(0xFF2B2A26), pillShape())
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            )
        }
        // design v2 .tchip-collapse: 44dp, r-sm (12dp), --dim glyph, no box until pressed.
        val fullscreenBtn: @Composable () -> Unit = {
            if (canStream) {
                Box(
                    Modifier.size(44.dp)
                        .clip(SubBarShape)
                        .clickable(enabled = true) { fullscreen = !fullscreen }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        CornersExpand,
                        contentDescription = null,
                        tint = Color(0xFFB0AEA5),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        // design v2 .tconf-btn: 44dp box on --card-2 with the --ring inset, --accent-hi
        // sliders glyph — never the Material "Tune" glyph (white bars, no box).
        val displaySettingsBtn: @Composable () -> Unit = {
            Box(
                Modifier.size(44.dp)
                    .clip(SubBarShape)
                    .background(Color(0xFF232220), SubBarShape)
                    .border(1.dp, Color(0x24B0AEA5), SubBarShape)
                    .clickable(enabled = true) { showDisplayConfig = !showDisplayConfig }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Display settings"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SlidersIcon,
                    contentDescription = null,
                    tint = Color(0xFFD97B54),
                    modifier = Modifier.size(18.dp),
                )
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
        // The display-settings button and its menu, shared by the terminal chips row and the
        // single Gemini top bar the chat face wears.
        val settingsBox: @Composable () -> Unit = {
            Box {
                displaySettingsBtn()
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
        if (!chromeTight && !fullscreen) {
            if (inChat) {
                // Screens 1-6: one clean black bar in chat mode — back, the model/thinking
                // picker, then the relay state and the display settings. No TokyoNight chips.
                Row(
                    Modifier.fillMaxWidth().height(52.dp).background(Gemini.Canvas),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    backBtn()
                    GeminiModelSelector(
                        preset = geminiPreset,
                        onModel = liveVm::selectModel,
                        onThinking = liveVm::selectThinking,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    relayChip()
                    settingsBox()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    backBtn()
                    titleBlock()
                    connChip()
                }
                // design v2 .tchips: padding 4px 14px 12px 14px, gap 14px, and only
                // .tconf gets margin-left:auto — [Térreo · Collapse] left, settings right.
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    floorChip()
                    fullscreenBtn()
                    Spacer(Modifier.weight(1f))
                    settingsBox()
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
            val liveCanSend = liveReady && (liveDraft.isNotBlank() || liveStaged.isNotEmpty()) && !voice.recording
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
                    placeholder = "Ask Maestri...",
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
                    recording = voice.recording,
                    recordElapsedMs = voice.elapsedMs,
                    onToggleRecord = voice::toggle,
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
                    onToggle = {
                        if (chipOpen) chipManualClose = true
                        chipOpen = !chipOpen
                    },
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
                    (termInput.isNotBlank() || stagedFiles.isNotEmpty()) && !uploading && !voice.recording,
                busy = uploading,
                recording = voice.recording,
                recordElapsedMs = voice.elapsedMs,
                onToggleRecord = voice::toggle,
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
    val transcript: String?,
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

