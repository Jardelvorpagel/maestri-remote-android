package app.maestri.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.warmRing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private sealed interface DisplayRow {
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
            RelayChip(state = relayState)
        }
        LiveFeedContent(vm)
    }
}

/**
 * Relay feed body (settings, event list, decision banner, composer) with no
 * scaffold of its own: hosted by LiveScreen or by a terminal's Chat mode.
 * ColumnScope for the weight-bearing list box; the rendering lives here once,
 * never duplicated per host.
 */
@Composable
fun ColumnScope.LiveFeedContent(vm: LiveViewModel) {
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
    val decided by vm.decisions.collectAsState()
    val expiredSet by vm.expiredApprovals.collectAsState()
    val approvalsMap by vm.approvals.collectAsState()
    val composeState by vm.composeState.collectAsState()
    val decisionPrompt by vm.decisionPrompt.collectAsState()

    // Auto-connect on entering Chat when a relay URL is already stored: one shot
    // per composition (leaving Chat disposes it, so re-entry retries; Stop sets a
    // disconnected state with autoTried true, so it never loops against the user).
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
    LaunchedEffect(allRows.size) {
        if (allRows.isEmpty()) return@LaunchedEffect
        if (followingNow) listState.scrollToItem(allRows.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        RelaySettingsCard(
            url = url,
            token = token,
            state = relayState,
            onUrl = { vm.setUrl(it) },
            onToken = { vm.setToken(it) },
            onConnect = { vm.connect() },
            onDisconnect = { vm.disconnect() },
        )

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
                    body = "Set the relay URL above — nothing connects until then. Token is optional, only for relays that require one.",
                )
                allRows.isEmpty() && relayState is RelayState.Open -> LiveEmpty(
                    title = "Waiting for events…",
                    body = "The stream is open. New Pi activity lands here.",
                )
                allRows.isEmpty() -> LiveEmpty(
                    title = "Not connected",
                    body = "Tap Connect to open the event stream.",
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(allRows, key = { rowKey(it) }) { row ->
                        when (row) {
                            is DisplayRow.Message -> MessageRow(row.event)
                            is DisplayRow.Tool -> ToolRow(row.call, row.result)
                            is DisplayRow.StatusLine -> StatusLineRow(row.event)
                            is DisplayRow.Err -> ErrorRow(row.event)
                            is DisplayRow.Unknown -> UnknownRow(row.event)
                            is DisplayRow.Pending -> LivePendingRow(row.send) { vm.retry(row.send.clientMsgId) }
                            is DisplayRow.Approval -> LiveApprovalCard(
                                view = row.view,
                                commandsReady = composeState is LiveComposeState.Ready,
                                commandsReason = (composeState as? LiveComposeState.Disabled)?.reason,
                                onApprove = { vm.decide(row.view.callId, approved = true, rememberSession = false) },
                                onDeny = { vm.decide(row.view.callId, approved = false, rememberSession = false) },
                                onAlways = { vm.decide(row.view.callId, approved = true, rememberSession = true) },
                                onExpire = { vm.markApprovalExpired(row.view.callId) },
                            )
                        }
                    }
                }
            }
            if (allRows.isNotEmpty() && !following) {
                TextButton(
                    onClick = {
                        vm.resumeTail()
                        scope.launch { listState.animateScrollToItem(allRows.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .heightIn(min = Rhythm.TouchTarget.dp)
                        .background(colors.canvasChromeElevated, pillShape())
                        .border(1.dp, colors.hairline, pillShape()),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    val label = if (newWhileAway > 0) "$newWhileAway new · resume" else "Resume tail"
                    Text(label, style = monoHud(11, color = MaterialTheme.colorScheme.onSurface))
                }
            }
        }

        decisionPrompt?.takeIf { it.isOpen }?.let { LiveDecisionBanner(prompt = it) }
        LiveComposerBar(vm)
    }
}

@Composable
private fun LiveDecisionBanner(prompt: DecisionPrompt) {
    val colors = LocalMaestriColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .background(colors.warning.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, colors.warning.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                "Responda no teclado do Pi — o app só espelha.",
                fontSize = 11.sp,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val colors = LocalMaestriColors.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(body, fontSize = 13.sp, color = colors.secondaryLabel)
    }
}

@Composable
private fun RelayChip(state: RelayState) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .warmRing(colors.hairline, radius = MaestriRadii.Card)
            .background(colors.canvasSurface, cardShape())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
private fun MessageRow(event: PiEvent) {
    val colors = LocalMaestriColors.current
    val mine = event.kind == LiveKinds.USER_MESSAGE
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (mine) 0.86f else 0.92f)
                .background(
                    if (mine) colors.bubbleMine else colors.bubbleAgent,
                    RoundedCornerShape(14.dp),
                )
                .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mine) "you" else (event.role?.takeIf { it.isNotBlank() }) ?: "assistant",
                    style = monoHud(10, color = if (mine) colors.bubbleMineText.copy(alpha = 0.75f) else colors.secondaryLabel),
                    modifier = Modifier.weight(1f),
                )
                val ts = formatTs(event.ts)
                if (ts.isNotEmpty()) {
                    Text(
                        ts,
                        style = monoHud(10, color = if (mine) colors.bubbleMineText.copy(alpha = 0.6f) else colors.secondaryLabel),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                event.text.orEmpty().ifBlank { "—" },
                fontSize = 13.5.sp,
                color = if (mine) colors.bubbleMineText else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ToolRow(call: PiEvent, result: PiEvent?) {
    val colors = LocalMaestriColors.current
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .warmRing(colors.hairline, radius = MaestriRadii.Card)
            .background(colors.canvasSurface, cardShape())
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = colors.secondaryLabel)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            summary,
            fontSize = 12.5.sp,
            color = colors.secondaryLabel,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
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
