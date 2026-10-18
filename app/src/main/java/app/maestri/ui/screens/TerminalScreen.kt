package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.wire.FeedItem
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.pillShape
import app.maestri.ui.settings.TerminalFontStore
import app.maestri.wire.ChatApi
import app.maestri.ui.Style.TerminalThemes
import app.maestri.ui.Style.BackLabel

/** Tokyo Night 16 ANSI slots for the termlib renderer (palette accepted in #61, verbatim). */
private val TERMLIB_TOKYO_NIGHT = intArrayOf(
    0xFF1A1B26.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
    0xFF565F89.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
)

/**
 * Terminal detail: preview (live snapshot) OR the live stream, prompt composer,
 * approve/reject for pendingPrompt items.
 *
 * Contract wiring:
 *  - POST …/seen on open
 *  - feed-socket presence of the open node (capability "presence")
 *  - live stream resets its emulator on every geometry frame
 *  - resize requests are clamped to 20..250 cols × 5..120 rows
 *  - prompt/approve/reject clear attention on the host; 409s surface as inline notices
 */
@Composable
fun TerminalScreen(
    session: WireSession,
    terminalId: String,
    terminalName: String,
    wsId: String,
    onBack: () -> Unit,
    onEnterChat: ((String, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Aa stepper (design v2 §5.4): persisted 10–20 terminal type scale.
    val ctx = LocalContext.current
    val fontStore = remember(ctx) { TerminalFontStore(ctx.applicationContext) }
    val fontSp by fontStore.sizeSp.collectAsState()
    var showTypeScale by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val snapshot by session.feed.snapshot.collectAsState()
    val feedState by session.feed.state.collectAsState()
    val feedError by session.feed.lastError.collectAsState()

    val streamState by session.terminalStream.state.collectAsState()
    val streamError by session.terminalStream.error.collectAsState()
    val streamGeometry by session.terminalStream.geometry.collectAsState()
    val streamCursor by session.terminalStream.cursor.collectAsState()

    var live by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var promptText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var seededFallback by remember { mutableStateOf(false) }
    // L3 compact refit (defect 2): phone-fit cols reported by the live viewport feed
    // the resize-dialog prefill and the forcedSize auto-grid below.
    var fitCols by remember(terminalId) { mutableStateOf<Int?>(null) }
    // Capabilities up here: the termlib binding below reads canResize at creation.
    val caps = session.serverInfo
    val canStream = caps?.has("terminalStreaming") == true
    val canResize = caps?.has("terminalResize") == true
    val isOwner = caps?.role == "owner"
    // todo #62: one termlib emulator per terminal (libvterm grid, recreated on switch).
    // Host bytes reach it through the socket (TerminalStreamSocket.termlib, attached
    // below); the component sizes its grid from forcedSize and reports back through
    // onResize, which refits the host PTY. lastSentGrid dedupes the host's own echo
    // (dialog applies stamp it too) so the resize converges instead of ping-ponging.
    var lastSentGrid by remember(terminalId) { mutableStateOf<Pair<Int, Int>?>(null) }
    val canResizeNow by rememberUpdatedState(canResize)
    val streamNow = session.terminalStream
    val termlibEmulator: TerminalEmulator = remember(terminalId) {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = TerminalThemes.TokyoNight.foreground,
            defaultBackground = TerminalThemes.TokyoNight.background,
            // The component runs keyboardEnabled=false, so this only fires for stray
            // hardware keys; route them down the UTF-8 input path like the composer.
            onKeyboardInput = { bytes -> streamNow.sendInput(String(bytes, Charsets.UTF_8)) },
            onResize = { dims ->
                if (!canResizeNow) return@create
                val grid = dims.columns to dims.rows
                if (lastSentGrid == grid) return@create
                lastSentGrid = grid
                streamNow.sendResize(dims.columns, dims.rows)
            },
        ).also { em ->
            em.applyColorScheme(
                TERMLIB_TOKYO_NIGHT,
                TerminalThemes.TokyoNight.foreground.toArgb(),
                TerminalThemes.TokyoNight.background.toArgb(),
            )
        }
    }
    LaunchedEffect(terminalId, termlibEmulator) {
        session.terminalStream.termlib = termlibEmulator
    }

    // seen + presence on open
    LaunchedEffect(terminalId) {
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

    LaunchedEffect(live, terminalId, canStream) {
        if (live && canStream) session.openTerminalStream(terminalId)
        else if (!live) session.terminalStream.close()
    }

    // Defect 2 (termlib era): the refit trigger moved into the emulator — the
    // component resizes its grid from forcedSize and reports through onResize above,
    // which sends the matching host resize. screenCompact still reads the full window
    // width so it can never disagree with the header's BoxWithConstraints.
    val screenCompact = LocalConfiguration.current.screenWidthDp < 480

    DisposableEffect(Unit) {
        onDispose {
            if (session.serverInfo?.has("presence") == true) session.feed.sendPresence(null)
            session.terminalStream.close()
        }
    }

    // ---------------------------------------------------------------- actions

    fun sendPrompt() {
        val text = promptText.trim()
        if (text.isBlank()) return
        sending = true
        scope.launch {
            try {
                session.prompt(terminalId, text)
                promptText = ""
            } catch (e: app.maestri.wire.ApiException) {
                notice = "prompt refused (HTTP ${e.status}${e.code?.let { " · $it" } ?: ""}): ${e.message}"
            } catch (e: Exception) {
                notice = "prompt failed: ${e.message}"
            } finally {
                sending = false
            }
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
        val enter = onEnterChat ?: return
        scope.launch {
            try {
                ChatApi(session).chatSetFace(terminalId, true, null)
                enter(terminalId, terminalName)
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

    // todo #57: edge-to-edge + adjustResize delivers the IME as an inset — the window does
    // NOT shrink (reno_kb2 proves it: the viewport extends under Gboard) — so the screen
    // consumes it here. One shared fix lifting the Live input + rail AND the agent prompt
    // composer (both faces) above the keyboard. Closed keyboard → zero inset → identical.
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).imePadding()) {
        // Header: responsive below a 480dp breakpoint (todo #42, Reno 411dp case).
        // The tablet path is the ARRANGEMENT-IDENTICAL row it has always been; the
        // compact path re-flows chips + controls onto a second, right-aligned row and
        // gives the Enter-Chat pill a measured minimum width so it renders on one line.
        val backBtn: @Composable () -> Unit = { TextButton(onClick = onBack) { BackLabel("Back") } }
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
        val connChip: @Composable () -> Unit = { ConnChip(feedState, feedError) }
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
        // Aa stepper chip: current persisted size, tap = the stepper row.
        val aaStepper: @Composable () -> Unit = {
            TextButton(onClick = { showTypeScale = !showTypeScale }) {
                Text("Aa $fontSp", fontFamily = MaestriType.Mono, fontSize = 12.sp)
            }
        }
        val fullscreenBtn: @Composable () -> Unit = {
            if (live && canStream) {
                TextButton(onClick = { fullscreen = !fullscreen }) {
                    Text(if (fullscreen) "\u2921" else "\u2922", fontSize = 18.sp)
                }
            }
        }
        // Compact-only: breathing room around the expand glyph on the re-flowed row.
        val fullscreenPadded: @Composable () -> Unit = {
            Box(Modifier.padding(horizontal = 3.dp)) { fullscreenBtn() }
        }
        // Enter-Chat pill. Tablet: the plain call, UNCHANGED. Compact: a min-width
        // floor keeps the label on ONE line (12sp "Enter Chat Mode" measures ~117dp
        // incl. its 12dp side paddings; at larger font scales the loose upper bound
        // lets it grow instead of wrapping).
        val chatPill: @Composable () -> Unit = {
            if (session.serverInfo?.has("terminalChat") == true && onEnterChat != null) {
                app.maestri.ui.screens.EnterChatModeButton(
                    session = session,
                    terminalId = terminalId,
                    onChatReady = { onEnterChat?.invoke(terminalId, terminalName) },
                )
            }
        }
        val chatPillCapped: @Composable () -> Unit = {
            if (session.serverInfo?.has("terminalChat") == true && onEnterChat != null) {
                Box(Modifier.defaultMinSize(minWidth = 128.dp)) {
                    app.maestri.ui.screens.EnterChatModeButton(
                        session = session,
                        terminalId = terminalId,
                        onChatReady = { onEnterChat?.invoke(terminalId, terminalName) },
                    )
                }
            }
        }
        BoxWithConstraints {
            val compact = maxWidth < 480.dp
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    backBtn()
                    titleBlock()
                    connChip()
                    floorChip()
                    Spacer(Modifier.width(6.dp))
                    aaStepper()
                    fullscreenBtn()
                    chatPill()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        backBtn()
                        titleBlock()
                        connChip()
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        floorChip()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            aaStepper()
                            fullscreenPadded()
                            chatPillCapped()
                        }
                    }
                }
            }
        }

        if (showTypeScale && !fullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Terminal font · ${MaestriType.MonoName} · $fontSp",
                    fontFamily = MaestriType.Mono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { fontStore.setSize(fontSp - 1) },
                    enabled = fontSp > TerminalFontStore.MIN_SP,
                ) { Text("−", fontFamily = MaestriType.Mono, fontSize = 14.sp) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { fontStore.setSize(fontSp + 1) },
                    enabled = fontSp < TerminalFontStore.MAX_SP,
                ) { Text("+", fontFamily = MaestriType.Mono, fontSize = 14.sp) }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (pendingPrompt != null && !fullscreen) {
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

        if (!fullscreen) {
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !live,
                onClick = { live = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Preview") }
            SegmentedButton(
                selected = live,
                onClick = { if (canStream) live = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                enabled = canStream,
            ) { Text(if (canStream) "Live" else "Live (cap off)") }
        }
        Spacer(Modifier.height(10.dp))
        }

        if (live && canStream) {
            LiveTerminalView(
                state = streamState,
                error = streamError,
                cursor = streamCursor,
                geometry = streamGeometry,
                canResize = canResize,
                isOwner = isOwner,
                fontSizeSp = fontSp,
                fullscreen = fullscreen,
                onChat = if (session.serverInfo?.has("terminalChat") == true && onEnterChat != null) {
                    ({ enterChatMode() })
                } else null,
                onInput = { s -> session.terminalStream.sendInput(s) },
                onResizeRequest = { showResize = true },
                compact = screenCompact,
                onViewportFit = { c, _ -> fitCols = c },
                termlib = termlibEmulator,
                modifier = if (fullscreen) Modifier.weight(1f) else Modifier,
            )
            if (showResize) {
                ResizeDialog(
                    // Compact prefills the measured phone-fit cols (defect 2: OK refits);
                    // tablet keeps the current-geometry prefill byte-identically.
                    cols = if (screenCompact) fitCols ?: (streamGeometry?.cols ?: 80) else streamGeometry?.cols ?: 80,
                    rows = streamGeometry?.rows ?: 24,
                    onDismiss = { showResize = false },
                    onApply = { c, r ->
                        showResize = false
                        lastSentGrid = c to r
                        session.terminalStream.sendResize(c, r)
                    },
                )
            }
        } else {
            PreviewPanel(card)
        }

        // todo #58: the agent prompt composer belongs to the Preview face — Live already
        // has the terminal composer + rail, and stacking both stranded "Prompt to the
        // agent…" under the rail with a focus ring (reno_termbox). Pre-#42 rendered it on
        // both faces (a922c8a:356 gated fullscreen-only, 361cbef:282 unconditional) — that
        // both-faces state IS the reported duplicate, so history keeps no other owner.
        if (!live && !fullscreen) {
            Spacer(Modifier.height(12.dp))
            PromptComposer(
                value = promptText,
                onChange = { promptText = it },
                onSend = ::sendPrompt,
                sending = sending,
            )
        }
    }
}

// --------------------------------------------------------------------------- views

@Composable
private fun attentionDot(t: TerminalCard?, pending: Boolean): Color = when {
    t == null -> Color.Gray
    pending || t.needsAttention -> MaterialTheme.colorScheme.error
    t.isActive == true -> MaterialTheme.colorScheme.primary
    t.isLive -> LocalMaestriColors.current.warning
    else -> Color.Gray
}

@Composable
private fun PreviewPanel(card: TerminalCard?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalMaestriColors.current.canvasSurface, controlShape())
            .padding(10.dp)
    ) {
        Text("Preview", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (card == null) {
            Text(
                "no snapshot yet — waiting for the live feed…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                card.preview.takeLast(12).forEachIndexed { idx, line ->
                    Text(
                        line,
                        fontFamily = MaestriType.Mono,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = TerminalThemes.previewForeground(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptComposer(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // todo #42 round 2: the composer is the prompt line — single-line with the IME
        // Send action driving the SAME guarded path as the Send button (hardware Enter
        // must submit; multi-line entry no longer reachable by keyboard, by design).
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Prompt to the agent…") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { if (!sending && value.isNotBlank()) onSend() },
            ),
        )
        Button(onClick = { if (!sending && value.isNotBlank()) onSend() }, enabled = !sending && value.isNotBlank()) {
            Text(if (sending) "…" else "Send")
        }
    }
}

@Composable
private fun ResizeDialog(
    cols: Int,
    rows: Int,
    onDismiss: () -> Unit,
    onApply: (cols: Int, rows: Int) -> Unit,
) {
    var c by remember { mutableStateOf(cols.toString()) }
    var r by remember { mutableStateOf(rows.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize PTY") },
        text = {
            Column {
                Text("Cols 20–250 · Rows 5–120", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = c,
                    onValueChange = { c = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("cols") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = r,
                    onValueChange = { r = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("rows") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply((c.toIntOrNull() ?: 80), (r.toIntOrNull() ?: 24)) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
