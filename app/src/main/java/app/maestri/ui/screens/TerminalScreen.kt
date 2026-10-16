package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Amber
import app.maestri.ui.Green
import app.maestri.ui.Red
import app.maestri.ui.Surface1
import app.maestri.ui.Surface2
import app.maestri.wire.FeedItem
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch

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
) {
    val scope = rememberCoroutineScope()
    val snapshot by session.feed.snapshot.collectAsState()
    val feedState by session.feed.state.collectAsState()
    val feedError by session.feed.lastError.collectAsState()

    val streamState by session.terminalStream.state.collectAsState()
    val streamError by session.terminalStream.error.collectAsState()
    val streamText by session.terminalStream.screenText.collectAsState()
    val streamGeometry by session.terminalStream.geometry.collectAsState()
    val streamCursor by session.terminalStream.cursor.collectAsState()

    var live by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var promptText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var seededFallback by remember { mutableStateOf(false) }

    val caps = session.serverInfo
    val canStream = caps?.has("terminalStreaming") == true
    val canResize = caps?.has("terminalResize") == true
    val isOwner = caps?.role == "owner"

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

    // ---------------------------------------------------------------- layout

    val card: TerminalCard? = snapshot?.terminalCards?.firstOrNull { it.id == terminalId }
    val pending: Boolean = snapshot?.pendingPromptIds?.contains(terminalId) == true
    val pendingPrompt: String? =
        snapshot?.items
            ?.filterIsInstance<FeedItem.PendingPromptFeedItem>()
            ?.firstOrNull { it.terminal.id == terminalId }?.prompt

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
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
            ConnChip(feedState, feedError)
        }

        if (pendingPrompt != null) {
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
            Text(it, color = Amber, fontSize = 12.sp)
        }

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

        if (live && canStream) {
            LiveTerminalView(
                state = streamState,
                error = streamError,
                text = streamText,
                cursor = streamCursor,
                geometry = streamGeometry,
                canResize = canResize,
                isOwner = isOwner,
                onInput = { s -> session.terminalStream.sendInput(s) },
                onResizeRequest = { showResize = true },
            )
            if (showResize) {
                ResizeDialog(
                    cols = streamGeometry?.cols ?: 80,
                    rows = streamGeometry?.rows ?: 24,
                    onDismiss = { showResize = false },
                    onApply = { c, r ->
                        showResize = false
                        session.terminalStream.sendResize(c, r)
                    },
                )
            }
        } else {
            PreviewPanel(card)
        }

        Spacer(Modifier.height(12.dp))
        PromptComposer(
            value = promptText,
            onChange = { promptText = it },
            onSend = ::sendPrompt,
            sending = sending,
        )
    }
}

// --------------------------------------------------------------------------- views

private fun attentionDot(t: TerminalCard?, pending: Boolean): Color = when {
    t == null -> Color.Gray
    pending || t.needsAttention -> Red
    t.isActive == true -> Green
    t.isLive -> Amber
    else -> Color.Gray
}

@Composable
private fun PreviewPanel(card: TerminalCard?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(8.dp))
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
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF9FB3A1),
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
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Prompt to the agent…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        Button(onClick = onSend, enabled = !sending && value.isNotBlank()) {
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