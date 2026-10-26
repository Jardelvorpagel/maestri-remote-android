package app.maestri.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.maestri.ui.Style.Gemini
import app.maestri.ui.Style.GeminiAiCaption
import app.maestri.ui.Style.GeminiHero
import app.maestri.ui.Style.GeminiLaunchOverlay
import app.maestri.ui.Style.GeminiModelSelector
import app.maestri.ui.Style.GeminiStar
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Motion
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.warmRing
import app.maestri.ui.settings.ChatDensityStore
import app.maestri.ui.settings.ChatTextSizeStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface DisplayRow {
    data class Message(val event: PiEvent) : DisplayRow
    data class Tool(val call: PiEvent, val result: PiEvent?) : DisplayRow
    data class StatusLine(val event: PiEvent) : DisplayRow
    data class Err(val event: PiEvent) : DisplayRow
    data class Unknown(val event: PiEvent) : DisplayRow
    data class Pending(val send: LivePendingSend) : DisplayRow
    data class Approval(val view: LiveApprovalView) : DisplayRow
}

private fun buildRows(events: List<PiEvent>, approvals: Map<String, LiveApprovalView> = emptyMap()): List<DisplayRow> {
    val rows = mutableListOf<DisplayRow>()
    val toolIndex = mutableMapOf<String, Int>()
    for (e in events) {
        when (e.kind) {
            LiveKinds.USER_MESSAGE, LiveKinds.ASSISTANT_MESSAGE -> rows.add(DisplayRow.Message(e))
            LiveKinds.TOOL_CALL -> {
                val id = e.tool?.callId?.takeIf { it.isNotBlank() } ?: e.entryId
                toolIndex[id] = rows.size
                rows.add(DisplayRow.Tool(e, null))
            }
            LiveKinds.TOOL_RESULT -> {
                val id = e.tool?.callId?.takeIf { it.isNotBlank() } ?: e.entryId
                val at = toolIndex[id]
                if (at != null && rows[at] is DisplayRow.Tool) {
                    val prev = rows[at] as DisplayRow.Tool
                    rows[at] = prev.copy(result = e)
                } else {
                    rows.add(DisplayRow.Tool(e, e))
                }
            }
            LiveKinds.RUN_START, LiveKinds.RUN_END, LiveKinds.NOTICE ->
                rows.add(DisplayRow.StatusLine(e))
            LiveKinds.ERROR -> rows.add(DisplayRow.Err(e))
            LiveKinds.SEND_ACK, LiveKinds.APPROVAL_RESOLVED -> Unit
            LiveKinds.APPROVAL_REQUEST -> {
                val id = e.tool?.callId?.takeIf { it.isNotBlank() } ?: e.entryId
                val view = approvals[id]
                if (view != null) rows.add(DisplayRow.Approval(view))
                else rows.add(DisplayRow.Unknown(e))
            }
            else -> rows.add(DisplayRow.Unknown(e))
        }
    }
    return rows
}

private val tsFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTs(ts: String): String =
    if (ts.isBlank()) ""
    else runCatching {
        Instant.parse(ts).atZone(ZoneId.systemDefault()).format(tsFmt)
    }.getOrDefault(ts.take(16))

@Composable
fun LiveScreen(onBack: () -> Unit, vm: LiveViewModel) {
    val colors = LocalMaestriColors.current
    val relayState by vm.relayState.collectAsState()
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val gemini by vm.gemini.collectAsState()
    var showRelaySettings by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .background(colors.canvasChrome),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "‹",
                fontSize = 20.sp,
                color = colors.secondaryLabel,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Text(
                "Live",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            GeminiModelSelector(
                preset = gemini,
                onModel = vm::selectModel,
                onThinking = vm::selectThinking,
                modifier = Modifier.padding(end = 10.dp),
            )
            RelayChip(state = relayState, onClick = { showRelaySettings = true })
        }
        LiveFeedContent(vm)
        if (showRelaySettings) {
            RelaySettingsDialog(
                url = url,
                token = token,
                state = relayState,
                onUrl = { vm.setUrl(it) },
                onToken = { vm.setToken(it) },
                onConnect = { vm.connect() },
                onDisconnect = { vm.disconnect() },
                onDismiss = { showRelaySettings = false },
            )
        }
    }
}

/**
 * Relay feed body (settings, event list, decision banner, composer) with no
 * scaffold of its own: hosted by LiveScreen or by a terminal's Chat mode.
 * [modifier] carries the host's sizing, and [endPadding] is the host's measured
 * floating-composer height — the rendering lives here once, never per host.
 */
@Composable
fun LiveFeedContent(
    vm: LiveViewModel,
    modifier: Modifier = Modifier.fillMaxSize(),
    showComposer: Boolean = true,
    /**
     * How much room the floating composer block needs at the bottom: measured by the host and fed
     * back here so the last message clears the pill instead of hiding under it.
     */
    endPadding: Dp = 16.dp,
    /** Tap destination for tool rows: the chat face passes the Terminal face switch.
     * Null keeps the legacy expand-in-place, reachable only from the retired
     * standalone LiveScreen route. */
    onOpenTerminal: (() -> Unit)? = null,
) {
    val colors = LocalMaestriColors.current
    val scope = rememberCoroutineScope()
    val relayState by vm.relayState.collectAsState()
    val events by vm.events.collectAsState()
    val following by vm.following.collectAsState()
    val newWhileAway by vm.newWhileAway.collectAsState()
    val notice by vm.notice.collectAsState()
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val pending by vm.pending.collectAsState()
    val sentAttachments by vm.sentAttachments.collectAsState()
    val decided by vm.decisions.collectAsState()
    val expiredSet by vm.expiredApprovals.collectAsState()
    val approvalsMap by vm.approvals.collectAsState()
    val composeState by vm.composeState.collectAsState()
    val decisionPrompt by vm.decisionPrompt.collectAsState()
    val autoRetry by vm.autoRetry.collectAsState()
    val waitingForSession by vm.waitingForSession.collectAsState()
    val relayError = relayState as? RelayState.Error
    // Chat display settings (same DataStore idiom as the terminal settings): text
    // steps 12/13/15 drive the message body; density drives list spacing and
    // bubble padding. Both faces of LiveFeedContent read the same stores.
    val ctx = LocalContext.current
    val chatTextSizeStore = remember(ctx) { ChatTextSizeStore(ctx.applicationContext) }
    val chatDensityStore = remember(ctx) { ChatDensityStore(ctx.applicationContext) }
    val chatTextSize by chatTextSizeStore.sizeSp.collectAsState()
    val chatCompact by chatDensityStore.compact.collectAsState()
    val listSpacing = if (chatCompact) 8.dp else 12.dp
    val bubbleVertical = if (chatCompact) 8.dp else 12.dp

    // Auto-connect on entering Chat when a relay URL is already stored: one shot
    // per composition (leaving Chat disposes it, so re-entry retries; Stop sets a
    // disconnected state with autoTried true, so it never loops against the user).
    // Duplicate firings collapse in LiveViewModel.connect (single-flight: no-op
    // while Connecting/Open), so the device can never show a double dial again.
    // No stored URL → the config-first empty state below stays, nothing invented.
    var autoTried by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (!autoTried && url.isNotBlank() && vm.relayState.value is RelayState.Disconnected) {
            autoTried = true
            vm.connect()
        }
    }

    val approvalViews = remember(events, approvalsMap, decided, expiredSet) {
        buildApprovalViews(approvalsMap, events, decided, expiredSet)
    }
    val approvalById = remember(approvalViews) { approvalViews.associateBy { it.callId } }
    val rows = remember(events, approvalById) { buildRows(events, approvalById) }
    val eventApprovalIds = remember(events) {
        events.filter { it.kind == LiveKinds.APPROVAL_REQUEST }
            .map { it.tool?.callId?.takeIf { c -> c.isNotBlank() } ?: it.entryId }
            .toSet()
    }
    val orphanApprovals = remember(approvalViews, eventApprovalIds) {
        approvalViews.filter { it.callId !in eventApprovalIds }
    }
    val allRows = remember(rows, orphanApprovals, pending) {
        rows + orphanApprovals.map { DisplayRow.Approval(it) } + pending.map { DisplayRow.Pending(it) }
    }
    // Consecutive tool runs collapse into one folded card (see IntelligentUi.groupFeedRows).
    val renderItems = remember(allRows) { groupFeedRows(allRows) }
    // The reference prints its AI caption under the newest answer only.
    val lastAnswerKey = remember(renderItems) {
        renderItems.lastOrNull { item ->
            item is FeedRenderItem.Single &&
                (item.row as? DisplayRow.Message)?.event?.kind == LiveKinds.ASSISTANT_MESSAGE
        }?.let { renderKey(it) }
    }
    val listState = rememberLazyListState()

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) true
            else {
                val last = info.visibleItemsInfo.lastOrNull()
                last != null && last.index >= total - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 8
            }
        }
    }
    vm.setFollowing(atBottom)
    val followingNow by rememberUpdatedState(following)
    LaunchedEffect(renderItems.size) {
        if (renderItems.isEmpty()) return@LaunchedEffect
        if (followingNow) listState.scrollToItem(renderItems.size - 1)
    }

    // The wash itself is painted by the screen behind this feed (TerminalScreen draws it over the
    // whole chat surface, composer included), so this column stays transparent. The boot animation
    // still plays once per chat-face mount, over the message area.
    var booting by remember { mutableStateOf(true) }
    Column(modifier) {
        // Persistent outage strip: the empty-state error card below only shows with
        // zero rows, but outages usually strike with history on screen — then the
        // only signal was the Connect button. This strip shows iff the relay is in
        // Error (title + reason + retry position) and hides itself on Open, so a
        // stale banner after reconnect is structurally impossible. It carries no
        // buttons, unlike the relay approval cards, and sits above the feed while
        // the Pi decision mirror sits above the composer.
        if (relayError != null && allRows.isNotEmpty()) {
            LiveErrorStrip(message = relayError.message, url = url, autoRetry = autoRetry)
        }

        notice?.let { n ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    n,
                    color = colors.warning,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Dismiss",
                    color = colors.secondaryLabel,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { vm.dismissNotice() }.padding(start = 8.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                url.isBlank() -> LiveEmpty(
                    title = "No relay configured",
                    body = "Tap the relay chip in the header to set the URL — nothing connects until then. Token is optional, only for relays that require one.",
                )
                // Bound-but-empty reads as the same hero as a fresh chat: a bare paragraph over the
                // wash photographed as text on a blue rectangle. The relay wording is the caption.
                allRows.isEmpty() && waitingForSession && relayState is RelayState.Open -> GeminiHero(
                    caption = "This terminal's session is not on the relay yet — it binds as soon as " +
                        "that Pi session starts (or reconnect once it has).",
                )
                // The hero is the empty CONVERSATION state: the relay always opens with a
                // `run started session …` notice, which on 182 pushed the hero off screen.
                !hasConversation(allRows) && relayState is RelayState.Open -> GeminiHero(
                    caption = "The stream is open. New Pi activity lands here.",
                )
                allRows.isEmpty() && relayError != null -> LiveEmpty(
                    title = "Relay error",
                    body = relayErrorBody(url, relayError.message, autoRetry),
                )
                allRows.isEmpty() -> LiveEmpty(
                    title = "Not connected",
                    body = "Tap Connect to open the event stream.",
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = endPadding),
                    verticalArrangement = Arrangement.spacedBy(listSpacing),
                ) {
                    items(renderItems, key = { renderKey(it) }) { item ->
                        when (item) {
                            is FeedRenderItem.Single -> FeedRowView(
                                row = item.row,
                                vm = vm,
                                bodySizeSp = chatTextSize,
                                bubbleVertical = bubbleVertical,
                                onOpenTerminal = onOpenTerminal,
                                composerState = composeState,
                                sentAttachments = sentAttachments,
                                showFooter = renderKey(item) == lastAnswerKey,
                            )
                            is FeedRenderItem.ToolGroup -> ToolGroupCard(group = item) { tool ->
                                ToolRow(tool.call, tool.result, bubbleVertical, onOpenTerminal)
                            }
                        }
                    }
                }
            }
            // Floating tail chip: the old "below the list, never over it" row cost a band of
            // height and pushed the conversation up. It now hovers just above the composer, on its
            // own 40 dp circle with no plate behind it — follow/unfollow and resumeTail unchanged.
            if (allRows.isNotEmpty() && !following) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = endPadding + 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Gemini.SurfaceRaised, CircleShape)
                            .clickable {
                                vm.resumeTail()
                                scope.launch { listState.animateScrollToItem(renderItems.size - 1) }
                            }
                            .semantics { contentDescription = "Scroll to the newest message" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\u2193", fontSize = 18.sp, color = Gemini.TextStrong)
                        if (newWhileAway > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .background(Gemini.Accent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$newWhileAway", fontSize = 9.sp, color = Gemini.TextStrong)
                            }
                        }
                    }
                }
            }
            GeminiLaunchOverlay(visible = booting) { booting = false }
        }

        decisionPrompt?.takeIf { it.isOpen }?.let {
            LiveDecisionBanner(
                prompt = it,
                sending = pending.any { send -> send.status == LiveSendStatus.SENDING },
                onOption = { label -> vm.sendOption(label) },
            )
        }
        if (showComposer) {
            // Voice memo: the take becomes a staged file, so the chat path uploads it exactly
            // like a picked attachment (vm.stageFiles owns the cap and its notice).
            val voice = rememberVoiceNote(
                onClip = { name, bytes ->
                    val memo = voiceNoteStagedFile(name, bytes)
                    vm.stageFiles(listOf(memo))
                    // Transcribe after staging: a slow or missing recognizer never delays the
                    // memo, and the transcript lands on the file the user already sees.
                    scope.launch {
                        val transcript = transcribeMemo(ctx.applicationContext, bytes) ?: return@launch
                        vm.setTranscript(memo.key, transcript)
                    }
                },
                onNotice = { vm.note(it) },
            )
            LiveComposerBar(
                vm = vm,
                recording = voice.recording,
                recordElapsedMs = voice.elapsedMs,
                onToggleRecord = voice::toggle,
            )
        }
    }
}

@Composable
private fun FeedRowView(
    row: DisplayRow,
    vm: LiveViewModel,
    bodySizeSp: Int,
    bubbleVertical: Dp,
    onOpenTerminal: (() -> Unit)?,
    composerState: LiveComposeState,
    sentAttachments: Map<String, List<LivePendingAttachment>>,
    showFooter: Boolean = false,
) {
    val composerReady = composerState is LiveComposeState.Ready
    when (row) {
        is DisplayRow.Message -> MessageRow(
            row.event,
            bodySizeSp = bodySizeSp,
            bubbleVertical = bubbleVertical,
            attachments = sentAttachments[row.event.text.orEmpty()].orEmpty(),
            composerReady = composerReady,
            onSendPrompt = vm::sendOption,
            showFooter = showFooter,
        )
        is DisplayRow.Tool -> ToolRow(
            row.call,
            row.result,
            bubbleVertical = bubbleVertical,
            onOpenTerminal = onOpenTerminal,
        )
        is DisplayRow.StatusLine -> StatusLineRow(row.event)
        is DisplayRow.Err -> ErrorRow(row.event)
        is DisplayRow.Unknown -> UnknownRow(row.event)
        is DisplayRow.Pending -> LivePendingRow(row.send) { vm.retry(row.send.clientMsgId) }
        is DisplayRow.Approval -> LiveApprovalCard(
            view = row.view,
            commandsReady = composerReady,
            commandsReason = (composerState as? LiveComposeState.Disabled)?.reason,
            onApprove = { vm.decide(row.view.callId, approved = true, rememberSession = false) },
            onDeny = { vm.decide(row.view.callId, approved = false, rememberSession = false) },
            onAlways = { vm.decide(row.view.callId, approved = true, rememberSession = true) },
            onExpire = { vm.markApprovalExpired(row.view.callId) },
        )
    }
}

private fun renderKey(item: FeedRenderItem): String = when (item) {
    is FeedRenderItem.Single -> rowKey(item.row)
    is FeedRenderItem.ToolGroup ->
        "g:${item.rows.firstOrNull()?.call?.seq}:${item.rows.lastOrNull()?.call?.seq}:${item.count}"
}

@Composable
private fun LiveErrorStrip(message: String, url: String, autoRetry: Boolean) {
    val colors = LocalMaestriColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp)
            .background(colors.warning.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, colors.warning.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Relay error",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.warning,
        )
        Text(
            relayErrorBody(url, message, autoRetry),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LiveDecisionBanner(prompt: DecisionPrompt, sending: Boolean, onOption: (String) -> Unit) {
    val colors = LocalMaestriColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .background(colors.warning.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, colors.warning.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(colors.warning, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Pi aguardando decisão: " + prompt.label(),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (prompt.options.isEmpty()) {
                        "Responda no teclado do Pi — o app só espelha."
                    } else {
                        "Toque para enviar como mensagem, ou responda no Pi."
                    },
                    fontSize = 11.sp,
                    color = colors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (prompt.options.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                prompt.options.forEach { option ->
                    TextButton(
                        onClick = { onOption(option) },
                        enabled = !sending,
                        modifier = Modifier
                            .border(1.dp, colors.warning.copy(alpha = 0.45f), pillShape())
                            .background(
                                colors.warning.copy(alpha = if (sending) 0.04f else 0.12f),
                                pillShape(),
                            ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text(
                            option,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (sending) colors.secondaryLabel else colors.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun rowKey(row: DisplayRow): String = when (row) {
    is DisplayRow.Message -> "m:${row.event.seq}:${row.event.entryId}"
    is DisplayRow.Tool -> "t:${row.call.seq}:${row.call.tool?.callId ?: row.call.entryId}"
    is DisplayRow.StatusLine -> "s:${row.event.seq}:${row.event.entryId}"
    is DisplayRow.Err -> "e:${row.event.seq}:${row.event.entryId}"
    is DisplayRow.Unknown -> "u:${row.event.seq}:${row.event.entryId}"
    is DisplayRow.Pending -> "p:${row.send.clientMsgId}"
    is DisplayRow.Approval -> "a:${row.view.callId}"
}

@Composable
private fun LiveEmpty(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Gemini.Text)
        Text(body, fontSize = 13.sp, color = Gemini.TextMuted)
    }
}

@Composable
private fun RelayChip(state: RelayState, onClick: () -> Unit) {
    val colors = LocalMaestriColors.current
    val label = when (state) {
        RelayState.Disconnected -> "Disconnected"
        RelayState.Connecting -> "Connecting"
        RelayState.Open -> "Open"
        is RelayState.Error -> "Error"
    }
    val dot = when (state) {
        RelayState.Disconnected -> colors.secondaryLabel
        RelayState.Connecting -> colors.warning
        RelayState.Open -> colors.positive
        is RelayState.Error -> colors.errorText
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onClick() }
            .background(colors.canvasSurface, pillShape())
            .border(1.dp, colors.hairline, pillShape())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = monoHud(11, color = MaterialTheme.colorScheme.onSurface))
    }
    if (state is RelayState.Error) {
        Text(
            state.message.take(120),
            color = colors.errorText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Relay connection settings on demand. The Chat face keeps a status chip in the header
 * instead of a permanent card; this dialog is where URL, token, Connect and Stop live now,
 * so the affordance survives while the feed gets its vertical space back.
 */
@Composable
fun RelaySettingsDialog(
    url: String,
    token: String,
    state: RelayState,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalMaestriColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.canvasChrome, cardShape())
                .border(1.dp, colors.hairline, cardShape())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Relay",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(6.dp),
                )
            }
            RelaySettingsCard(
                url = url,
                token = token,
                state = state,
                onUrl = onUrl,
                onToken = onToken,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun RelaySettingsCard(
    url: String,
    token: String,
    state: RelayState,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = LocalMaestriColors.current
    val open = state is RelayState.Open || state is RelayState.Connecting
    var collapsed by remember(open) { mutableStateOf(open) }
    val dot = when (state) {
        RelayState.Disconnected -> colors.secondaryLabel
        RelayState.Connecting -> colors.warning
        RelayState.Open -> colors.positive
        is RelayState.Error -> colors.errorText
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .warmRing(colors.hairline, radius = MaestriRadii.Card)
            .background(colors.canvasSurface, cardShape())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (collapsed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    url.ifBlank { "Relay" },
                    style = monoHud(11, color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { collapsed = false },
                )
                TextButton(onClick = { if (open) onDisconnect() else onConnect() }) {
                    Text(
                        if (open) "Stop" else "Connect",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (open) colors.errorText else colors.accent,
                    )
                }
                TextButton(onClick = { collapsed = false }) {
                    Text("Show", fontSize = 12.sp, color = colors.secondaryLabel)
                }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                "Relay",
                style = monoHud(11, color = colors.secondaryLabel),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { collapsed = true }) {
                Text("Hide", fontSize = 12.sp, color = colors.secondaryLabel)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrl,
                    label = { Text("Relay URL", fontSize = 11.sp) },
                    textStyle = monoHud(12),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrect = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = onToken,
                    label = { Text("Token (optional)", fontSize = 11.sp) },
                    textStyle = monoHud(12),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { if (open) onDisconnect() else onConnect() }) {
                Text(
                    if (open) "Stop" else "Connect",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (open) colors.errorText else colors.accent,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    event: PiEvent,
    bodySizeSp: Int = ChatTextSizeStore.DEFAULT_SP,
    bubbleVertical: Dp = 8.dp,
    attachments: List<LivePendingAttachment> = emptyList(),
    composerReady: Boolean = false,
    onSendPrompt: (String) -> Unit = {},
    showFooter: Boolean = false,
) {
    val colors = LocalMaestriColors.current
    val mine = event.kind == LiveKinds.USER_MESSAGE
    val body = event.text.orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        // The typed bubble hugs its text (Gemini's "oi" is a small pill) up to a readable cap;
        // the answer column keeps the full measure.
        Column(
            modifier = if (mine) {
                Modifier.widthIn(max = 560.dp)
            } else {
                Modifier.fillMaxWidth(0.92f)
            },
        ) {
            // Reference reading style: an attachment is its own card ABOVE the typed bubble,
            // never clipped inside the bubble it belongs to.
            if (mine && attachments.isNotEmpty()) LiveAttachmentChips(attachments)
            // A turn that carried only an attachment has nothing to say: the card stands alone,
            // with no empty bubble and no stray "you" label above it.
            if (!mine || showsTurnBubble(body, attachments.size)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Gemini reading style: the typed turn is a charcoal pill (24 dp, no border),
                    // the answer sits straight on the black canvas. No label or timestamp row —
                    // the reference has none, and "you" above a bubble was clutter.
                    .then(
                        if (mine) {
                            Modifier
                                .background(Gemini.Surface, RoundedCornerShape(24.dp))
                                .padding(horizontal = 16.dp, vertical = bubbleVertical)
                        } else {
                            Modifier.padding(vertical = bubbleVertical)
                        },
                    ),
            ) {
                if (mine) {
                    if (body.isNotBlank()) {
                        Text(body, fontSize = bodySizeSp.sp, color = Gemini.Text)
                    }
                } else {
                    IntelligentMessageBody(
                        text = body.ifBlank { "—" },
                        bodySizeSp = bodySizeSp,
                        color = Gemini.Text,
                        composerReady = composerReady,
                        onSendPrompt = onSendPrompt,
                    )
                    LiveCopyRow(text = body)
                    if (showFooter) {
                        Text(
                            GeminiAiCaption,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.sp,
                            color = Gemini.TextMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/** The air between the floating composer block and the message above it. */
val ChatComposerGap = 8.dp

/**
 * How far the last message must sit from the bottom of the feed: the distance from that bottom edge
 * to the TOP of the floating block, plus one gap.
 *
 * It is that distance and not the block's own height on purpose. The block is bottom-anchored, so
 * the two are equal while everything inside it is accounted for — and when it is not (189 measured a
 * chip row that grew the block 205 px while the reserve grew 175 px), reading the top edge is the
 * fact that cannot be got wrong: whatever the chips, the pill, the toggle or a future row add, the
 * edge moves with them.
 */
fun chatFeedEndPadding(blockTopOffset: Dp): Dp = blockTopOffset + ChatComposerGap

/** The visible breathing room: what the feed reserves minus what the block actually occupies. */
fun chatClearance(reserve: Dp, blockHeight: Dp): Dp = reserve - blockHeight

/** Whether the feed carries an actual exchange yet: notices, tool rows and status lines do not. */
internal fun hasConversation(rows: List<DisplayRow>): Boolean = rows.any { it is DisplayRow.Message }

/** Whether a turn needs bubble chrome at all: an attachment-only turn is just its card. */
internal fun showsTurnBubble(body: String, attachmentCount: Int): Boolean =
    body.isNotBlank() || attachmentCount == 0

/**
 * Action row under an assistant turn. Copy only: share, thumbs and regenerate each need host
 * or protocol support, and a dead icon in the reading surface is worse than none.
 */
@Composable
private fun LiveCopyRow(text: String) {
    if (text.isBlank()) return
    val clip = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1600)
        copied = false
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(pillShape())
            .clickable {
                clip.setText(AnnotatedString(text))
                copied = true
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        GeminiStar(size = 13.dp)
        Spacer(Modifier.width(8.dp))
        Text(if (copied) "\u2713" else "\u29C9", fontSize = 13.sp, color = Gemini.TextSecondary)
        Spacer(Modifier.width(6.dp))
        Text(if (copied) "Copiado" else "Copiar", fontSize = 12.sp, color = Gemini.TextSecondary)
    }
}

@Composable
private fun ToolRow(
    call: PiEvent,
    result: PiEvent?,
    bubbleVertical: Dp = 8.dp,
    onOpenTerminal: (() -> Unit)?,
) {
    val colors = LocalMaestriColors.current
    // The expand path below survives only for the null callback — the retired
    // standalone LiveScreen route. The chat face always passes a callback, so its
    // rows navigate instead of expanding and the preview stays static.
    var expanded by remember(call.entryId, call.seq) { mutableStateOf(false) }
    val info = call.tool
    val name = info?.name?.takeIf { it.isNotBlank() } ?: "tool"
    val status = (result?.tool?.status ?: info?.status ?: "").lowercase()
    val dot = when (status) {
        "ok" -> colors.positive
        "error" -> colors.errorText
        else -> colors.warning
    }
    val summary = (result?.tool?.summary ?: info?.summary ?: result?.text ?: call.text).orEmpty()
        .take(140).ifBlank { status.ifBlank { "running" } }
    // Press state per the design: scale .97 with a 2px accent ring.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = tween(Motion.Fast, easing = Motion.Ease),
        label = "tool-press",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .warmRing(
                if (pressed) colors.accent else colors.hairline,
                radius = MaestriRadii.Card,
                ringWidth = if (pressed) 2.dp else 1.dp,
            )
            .background(colors.canvasSurface, cardShape())
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = { onOpenTerminal?.invoke() ?: run { expanded = !expanded } },
            )
            .padding(horizontal = 12.dp, vertical = bubbleVertical),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status.ifBlank { "running" }, style = monoHud(10, color = colors.secondaryLabel))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            summary,
            fontSize = 12.5.sp,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded && onOpenTerminal == null) {
            Spacer(Modifier.height(6.dp))
            val args = info?.argsPreview?.take(500).orEmpty()
            val res = (result?.tool?.resultPreview ?: info?.resultPreview ?: result?.text).orEmpty().take(500)
            if (args.isNotBlank()) {
                Text("args", style = monoHud(10, color = colors.secondaryLabel))
                Text(
                    args,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.cardSunken, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            if (res.isNotBlank()) {
                Text("result", style = monoHud(10, color = colors.secondaryLabel))
                Text(
                    res,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.cardSunken, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            if (args.isBlank() && res.isBlank()) {
                Text("no previews", style = monoHud(11, color = colors.secondaryLabel))
            }
        }
    }
}

@Composable
private fun StatusLineRow(event: PiEvent) {
    val colors = LocalMaestriColors.current
    val label = when (event.kind) {
        LiveKinds.RUN_START -> "run started"
        LiveKinds.RUN_END -> "run ended"
        else -> "notice"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = monoHud(10, color = colors.secondaryLabel))
        val body = event.text.orEmpty()
        if (body.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                body.take(120),
                fontSize = 12.sp,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ErrorRow(event: PiEvent) {
    val colors = LocalMaestriColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, colors.warning.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.warning)
        Spacer(Modifier.width(8.dp))
        Text(
            event.text.orEmpty().ifBlank { "error" }.take(300),
            fontSize = 12.5.sp,
            color = colors.warning,
        )
    }
}

@Composable
private fun UnknownRow(event: PiEvent) {
    val colors = LocalMaestriColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvasSurface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.kind.ifBlank { "unknown" },
            style = monoHud(10, color = colors.secondaryLabel),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            event.text.orEmpty().take(200).ifBlank { "—" },
            fontSize = 12.5.sp,
            color = colors.secondaryLabel,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
