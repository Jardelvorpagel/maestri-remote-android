package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.maestri.ui.iconGlyph
import app.maestri.wire.FeedItem
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.NoteCard
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch

/**
 * Live feed for one workspace: `WS /api/feed/stream?ws=…&token=…`.
 *
 * Items render as cards (terminal / pendingPrompt / note / unknown). Floor chips pick
 * the mirrored floor; socket reconnects with backoff and the snapshot stays authoritative.
 */
@Composable
fun FeedScreen(
    session: WireSession,
    wsId: String,
    wsName: String,
    onBack: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenTerminal: (terminalId: String, terminalName: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snapshot by session.feed.snapshot.collectAsState()
    val socketState by session.feed.state.collectAsState()
    val socketError by session.feed.lastError.collectAsState()
    var floor by remember { mutableStateOf<String?>(null) } // null = mirror the host's active floor

    var initialError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        val p = session.pairing.value ?: return@LaunchedEffect
        if (session.feed.snapshot.value == null || session.feed.currentUrl.isNullOrBlank()) {
            try {
                // Seed via HTTP so content shows before the socket's first snapshot.
                session.feed.seed(session.feedOnce(p, wsId, floor))
            } catch (e: Exception) {
                initialError = e.message
            }
        }
        session.openFeed(wsId, floor)
    }

    LaunchedEffect(floor) {
        if (session.feed.snapshot.value?.workspaceId == wsId) {
            try {
                val p = session.pairing.value ?: return@LaunchedEffect
                session.feed.reset()
                session.feed.seed(session.feedOnce(p, wsId, floor))
            } catch (e: Exception) {
                initialError = e.message
            }
            session.feed.retarget(session.feedUrlFor(wsId, floor))
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Column(Modifier.weight(1f)) {
                Text(wsName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val s = snapshot
                if (s != null) {
                    Text(
                        buildString {
                            append("epoch ${s.epoch.take(8)}")
                            s.workspace?.let {
                                append(" · ${it.runningTerminalCount}/${it.terminalCount} running")
                                if (it.attentionCount > 0) append(" · ⚠${it.attentionCount}")
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenCanvas,
            ) { Text("Canvas") }
            ConnChip(socketState, socketError)
        }

        // Floor chips
        val floors = snapshot?.floors ?: emptyList()
        if (floors.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = floor == null,
                    onClick = { floor = null },
                    label = { Text("Host view") },
                )
                floors.forEach { f ->
                    FilterChip(
                        selected = floor == (f.id ?: "ground"),
                        onClick = { floor = f.id ?: "ground" },
                        label = {
                            Text(
                                (if (f.statusText != null) "${f.name} · ${truncate(f.statusText, 26)}" else f.name),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }

        initialError?.let {
            Text(it, color = Red, fontSize = 12.sp)
        }

        val snap = snapshot
        if (snap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no snapshot yet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in snap.items) {
                    FeedItemRow(
                        item = item,
                        pendingIds = snap.pendingPromptIds,
                        onOpen = onOpenTerminal,
                        session = session,
                        scope = scope,
                    )
                }
            }
        }
    }
}

@Composable
private fun snapshotItemKey(item: FeedItem): String = when (item) {
    is FeedItem.TerminalFeedItem -> "t-${item.terminal.id}"
    is FeedItem.PendingPromptFeedItem -> "p-${item.terminal.id}"
    is FeedItem.NoteFeedItem -> "n-${item.note.nodeId}"
    is FeedItem.UnknownFeedItem -> "u-${item.kind}"
}

private fun truncate(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

@Composable
fun ConnChip(state: SocketState, err: String?) {
    val (color, label) = when (state) {
        SocketState.OPEN -> Green to "live"
        SocketState.CONNECTING -> Amber to "connecting"
        SocketState.CLOSED -> Red to "down"
        SocketState.IDLE -> Color.Gray to "off"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = color)
        if (state == SocketState.CLOSED && err != null) {
            Text(" · $err", fontSize = 10.sp, color = Red, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeedItemRow(
    item: FeedItem,
    pendingIds: Set<String>,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
    session: WireSession,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (item) {
        is FeedItem.TerminalFeedItem ->
            TerminalCardView(item.terminal, pendingIds.contains(item.terminal.id), null, onOpen)
        is FeedItem.PendingPromptFeedItem ->
            PendingPromptCard(item.terminal, item.prompt, onOpen, session, scope)
        is FeedItem.NoteFeedItem -> NoteItem(item.note)
        is FeedItem.UnknownFeedItem -> {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "unsupported item (${item.kind.ifBlank { "unknown" }})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalCardView(
    t: TerminalCard,
    pending: Boolean,
    promptText: String?,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pending || t.needsAttention) Surface2 else Surface1
        ),
        onClick = { onOpen(t.id, t.name) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(iconGlyph(t.icon), fontSize = 16.sp, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.name + (t.accountName?.let { " · $it" } ?: ""),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (t.isManager) Text("  (maestro)", fontSize = 10.sp, color = Amber)
                    }
                    Text(
                        buildString {
                            append("${t.floorName ?: "floor"} · ${if (t.isRunning) "running" else "stopped"}")
                            if (t.isActive == true) append(" · working")
                            else if (t.needsAttention) append(" · attention")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(10.dp).background(attentionColor(t, pending), CircleShape))
            }
            if (promptText != null && pending) {
                Spacer(Modifier.height(6.dp))
                PromptQuestion(promptText)
            }
            Spacer(Modifier.height(8.dp))
            // Preview lines: plain text, last few lines — the no-emulator view of a terminal
            val lines = t.preview.takeLast(3)
            if (lines.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B0C0E), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    lines.forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF9FB3A1),
                        )
                    }
                }
            }
            t.maestroRequestMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "maestro: ${truncate(it, 120)}",
                    color = Amber,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun attentionColor(t: TerminalCard, pending: Boolean): Color = when {
    pending || t.needsAttention -> Red
    t.isActive == true -> Green
    t.isLive -> Amber
    else -> Color.Gray
}

@Composable
private fun PromptQuestion(prompt: String) {
    Text(
        prompt,
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PendingPromptCard(   // public: also used by TerminalScreen
    t: TerminalCard,
    prompt: String,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
    session: WireSession,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var busy by remember { mutableStateOf(false) }
    var fail by remember { mutableStateOf<String?>(null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1518)),
        onClick = { onOpen(t.id, t.name) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("S/n · ", color = Amber, fontWeight = FontWeight.Bold)
                Text(
                    t.name + (t.accountName?.let { " · $it" } ?: ""),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            PromptQuestion(prompt)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        android.util.Log.d("QAFeed", "pending card tap: Reject term=${t.id}")
                        busy = true
                        scope.launch {
                            try {
                                session.reject(t.id)
                                fail = null
                                android.util.Log.d("QAFeed", "pending card Reject returned ok term=${t.id}")
                            } catch (e: Exception) {
                                fail = (e as? app.maestri.wire.ApiException)?.let { "HTTP ${it.status}: ${it.message}" }
                                    ?: "failed: ${e.message}"
                                android.util.Log.e("QAFeed", "pending card Reject threw term=${t.id}: $e")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Reject") }
                Button(
                    enabled = !busy,
                    onClick = {
                        android.util.Log.d("QAFeed", "pending card tap: Approve term=${t.id}")
                        busy = true
                        scope.launch {
                            try {
                                session.approve(t.id)
                                fail = null
                                android.util.Log.d("QAFeed", "pending card Approve returned ok term=${t.id}")
                            } catch (e: Exception) {
                                fail = (e as? app.maestri.wire.ApiException)?.let { "HTTP ${it.status}: ${it.message}" }
                                    ?: "failed: ${e.message}"
                                android.util.Log.e("QAFeed", "pending card Approve threw term=${t.id}: $e")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Approve") }
            }
            fail?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Amber, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoteItem(n: NoteCard) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                n.displayName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            n.preview.firstOrNull()?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}