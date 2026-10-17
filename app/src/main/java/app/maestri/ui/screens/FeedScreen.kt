package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.maestri.wire.FeedItem
import app.maestri.wire.FloorMeta
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.NoteCard
import app.maestri.wire.PendingFloorMeta
import app.maestri.wire.SocketState
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.RootTitle
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.BackLabel
import app.maestri.ui.Style.pillShape

/**
 * Live feed for one workspace: `WS /api/feed/stream?ws=…&token=…`.
 *
 * design-v2 modern parity (spec §4-02): a filter-chip row (all / needsAttention / floors /
 * pr — glass pills, active = terracotta ring + terracotta mono label, the mock's
 * .tchip/.tchip.on) over claude list rows: top-only hairline separators, 8 dp semantic
 * dot, sans 13 title, mono 11.5 sub-meta, mono 11 right time. Rows are a UI
 * re-presentation of the SAME snapshot items and floor metadata — no wire/API change;
 * the floor mirror chips (switching the mirrored floor) and the Approve/Reject
 * affordances keep their shipped semantics. Per-item times do not exist in the snapshot
 * (the contract omits optional fields; only maestroRequest carries createdAt), so the
 * right time is the client-observed arrival stamp of the item/event — real data,
 * never invented.
 */
@Composable
fun FeedScreen(
    session: WireSession,
    wsId: String,
    wsName: String,
    onBack: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenTerminal: (terminalId: String, terminalName: String) -> Unit,
    onOpenFloors: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snapshot by session.feed.snapshot.collectAsState()
    val socketState by session.feed.state.collectAsState()
    val socketError by session.feed.lastError.collectAsState()
    var floor by remember { mutableStateOf<String?>(null) } // null = mirror the host's active floor
    var filter by remember { mutableStateOf(FeedFilter.All) }

    // Client-observed arrival stamps (id → first-seen millis). Feed rows show mono 11
    // right times; the wire snapshot carries no per-item timestamps, so the times the
    // user sees are ours: the real first-arrival time of each item/event on this device.
    // First arrival survives epoch resyncs; the map is keyed to the workspace.
    var arrivals by remember(wsId) { mutableStateOf(emptyMap<String, Long>()) }
    var arrivalTick by remember { mutableStateOf(0) } // 30 s re-render cadence for the labels

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

    val snapNow = snapshot
    val rows = remember(snapNow, filter) {
        if (snapNow == null) emptyList() else buildRows(snapNow, filter)
    }

    // Arrival-stamp bookkeeping + the 30 s ticker that relabels "now" → "2m" → "12:44".
    LaunchedEffect(rows) {
        if (rows.isEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        arrivals = arrivals + rows.map { it.rowKey() }
            .filter { it !in arrivals }
            .map { it to now }
            .toMap()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            arrivalTick++
        }
    }
    val now = remember(arrivalTick) { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Column(Modifier.weight(1f)) {
                RootTitle(wsName)
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
                        fontFamily = MaestriType.Mono,
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

        // Filter chips — spec §4-02: all / needsAttention / floors / pr. Glass pill +
        // hairline (the no-blur glass treatment); active = terracotta ring + terracotta
        // mono label, exactly the mock's `.tchip.on`.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FeedFilter.entries.forEach { f ->
                FeedFilterChip(
                    label = f.label,
                    selected = filter == f,
                    onClick = { filter = f },
                )
            }
        }

        // Floor chips (mirror lane, function kept): head of the row opens the floors + git
        // screens; the mirror chips keep their floor-switching semantics, now with the
        // FloorMeta state glyph (⚠ attention/blocked — clone-missing and copy errors
        // travel only on the floors screens' FloorRow, not in models).
        val floors = snapNow?.floors ?: emptyList()
        if (floors.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onOpenFloors, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("${app.maestri.ui.FloorsStrings.floors} ›", fontSize = 13.sp)
                }
                FilterChip(
                    selected = floor == null,
                    onClick = { floor = null },
                    label = { Text("Host view") },
                )
                floors.forEach { f ->
                    val glyph = floorStateGlyph(f)
                    FilterChip(
                        selected = floor == (f.id ?: "ground"),
                        onClick = { floor = f.id ?: "ground" },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (glyph != null) {
                                    Text(glyph, fontSize = 12.sp, modifier = Modifier.padding(end = 3.dp))
                                }
                                Text(
                                    (if (f.statusText != null) "${f.name} · ${truncate(f.statusText, 26)}" else f.name),
                                    maxLines = 1,
                                )
                            }
                        },
                    )
                }
            }
        }

        initialError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        if (snapNow == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no snapshot yet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // Event rows — claude list treatment: top-only hairline separators
            // (`.rli` — first row has none), min 56 pt touch target. The bottom padding
            // clears the spine (MaestriSpine tab 1 stays the Feed's chrome).
            val showBanner = filter == FeedFilter.All || filter == FeedFilter.NeedsAttention
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = (Rhythm.SpineHeight + 12).dp),
            ) {
                // MaestroRequest: an actionable-looking surface. The contract documents no
                // acknowledge route, so the button stays visibly disabled (honest, not dead).
                if (showBanner) {
                    snapNow.terminalCards
                        .filter { it.maestroRequestMessage != null }
                        .forEach { t -> MaestroRequestBanner(t, onOpenTerminal) }
                }

                rows.forEachIndexed { i, row ->
                    FeedEventRow(
                        row = row,
                        time = arrivals[row.rowKey()]?.let { arrivalLabel(it, now) } ?: "",
                        onOpen = onOpenTerminal,
                        onOpenFloors = onOpenFloors,
                        first = i == 0 && !showBanner,
                        session = session,
                        scope = scope,
                    )
                }

                if (rows.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Palette.StoneGray, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                when (filter) {
                                    FeedFilter.NeedsAttention -> "nothing needs attention"
                                    FeedFilter.Floors -> "no floor state in this snapshot"
                                    FeedFilter.PullRequests -> "no PRs and no floors building"
                                    FeedFilter.All -> "no items in this floor's feed"
                                },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "try another filter, or switch floors below",
                                fontFamily = MaestriType.Mono,
                                fontSize = 11.5.sp,
                                color = monoMetaColor(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // The floors/git screens stay one tap from the floors/PR filters.
                if (filter == FeedFilter.Floors || filter == FeedFilter.PullRequests) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onOpenFloors),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "${app.maestri.ui.FloorsStrings.floors} ›",
                            fontFamily = MaestriType.Mono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = LocalMaestriColors.current.accent,
                        )
                    }
                }
            }
        }
    }
}

private enum class FeedFilter(val label: String) {
    All("all"),
    NeedsAttention("needsAttention"),
    Floors("floors"),
    PullRequests("pr"),
}

private sealed interface FeedRow {
    /** The arrival-clock key for this row (also its stable identity across snapshots). */
    fun rowKey(): String

    data class Item(val item: FeedItem) : FeedRow {
        override fun rowKey(): String = when (item) {
            is FeedItem.TerminalFeedItem -> "t-${item.terminal.id}"
            is FeedItem.PendingPromptFeedItem -> "p-${item.terminal.id}"
            is FeedItem.NoteFeedItem -> "n-${item.note.nodeId}"
            is FeedItem.UnknownFeedItem -> "u-${item.kind}"
        }
    }

    data class FloorEvent(val floor: FloorMeta, val event: FloorEventKind) : FeedRow {
        override fun rowKey(): String = "f-${floor.id ?: "ground"}-${event.name}"
    }

    data class PendingBuild(val build: PendingFloorMeta) : FeedRow {
        override fun rowKey(): String = "pb-${build.id}"
    }
}

private enum class FloorEventKind {
    /** pullState merged — the mock's "Floor landed" row. */
    Landed,

    /** pullState open — the mock's "PR opened" row. */
    PullRequest,

    /** attentionCount > 0 or statusState blocked — actionable floor trouble. */
    NeedsAttention,

    /** other floor state carrying real status — surfaced by the floors filter only. */
    Status,
}

private fun buildRows(snap: FeedSnapshot, filter: FeedFilter): List<FeedRow> {
    val items = snap.items
    val pendingIds = snap.pendingPromptIds
    val classified = snap.floors.mapNotNull { f ->
        classifyFloor(f)?.let { FeedRow.FloorEvent(f, it) }
    }
    val builds = snap.pendingFloors.map { FeedRow.PendingBuild(it) }

    return when (filter) {
        FeedFilter.All -> {
            val prompts = items.filterIsInstance<FeedItem.PendingPromptFeedItem>().map { FeedRow.Item(it) }
            val terminals = items.filterIsInstance<FeedItem.TerminalFeedItem>()
            val needsAttention = terminals
                .filter { it.terminal.needsAttention || pendingIds.contains(it.terminal.id) }
                .map { FeedRow.Item(it) }
            val rest = (terminals.filterNot { it.terminal.needsAttention || pendingIds.contains(it.terminal.id) }
                + items.filterIsInstance<FeedItem.NoteFeedItem>()
                + items.filterIsInstance<FeedItem.UnknownFeedItem>())
                .map { FeedRow.Item(it) }
            prompts + needsAttention + rest +
                classified.filter {
                    it.event == FloorEventKind.Landed || it.event == FloorEventKind.PullRequest || it.event == FloorEventKind.NeedsAttention
                } + builds
        }
        FeedFilter.NeedsAttention ->
            items
                .filter {
                    it is FeedItem.PendingPromptFeedItem ||
                        (it is FeedItem.TerminalFeedItem && (it.terminal.needsAttention || pendingIds.contains(it.terminal.id)))
                }
                .map { FeedRow.Item(it) } + classified.filter { it.event == FloorEventKind.NeedsAttention }
        FeedFilter.Floors -> classified + builds
        FeedFilter.PullRequests ->
            classified.filter { it.event == FloorEventKind.PullRequest || it.event == FloorEventKind.Landed } + builds
    }
}

/** Real feed-stream event classification from live FloorMeta — nothing invented. */
private fun classifyFloor(f: FloorMeta): FloorEventKind? = when {
    f.pullState == "merged" -> FloorEventKind.Landed
    f.pullState == "open" -> FloorEventKind.PullRequest
    (f.attentionCount ?: 0) > 0 || f.statusState == "blocked" -> FloorEventKind.NeedsAttention
    f.pullState == "closed" -> FloorEventKind.Status
    f.statusText != null || f.statusState != null -> FloorEventKind.Status
    else -> null
}

/** Mock's right-slot time grammar: `now` → `Xm` → `HH:mm`, all from one arrival stamp. */
private fun arrivalLabel(millis: Long, now: Long): String {
    val d = (now - millis).coerceAtLeast(0)
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        else -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

/** Sub-meta colour: claude Stone Gray on dark; the light scheme's warm secondary. */
@Composable
private fun monoMetaColor(): Color =
    if (LocalMaestriColors.current.isLight) Color(0xFF6B6A62) else Palette.StoneGray

@Composable
private fun FeedFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalMaestriColors.current
    // `.tchip` / `.tchip.on`: glass pill + hairline ring; active = terracotta 55 % ring
    // + terracotta mono label. 32 dp pill per spec §6 (around 44 pt list rows that carry
    // the heavy touch traffic).
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.55f) else c.hairline, pillShape())
            .background(c.glass, pillShape())
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = MaestriType.Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) c.accent else monoMetaColor(),
        )
    }
}

/**
 * One claude list row: 8 dp semantic dot · text block · mono 11 right time. Top-only
 * hairline separator ([first] rows have none — `.rli:first-child` parity).
 */
@Composable
private fun FeedEventRow(
    row: FeedRow,
    time: String,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
    onOpenFloors: () -> Unit,
    first: Boolean,
    session: WireSession,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (!first) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalMaestriColors.current.hairline))
    }
    when (row) {
        is FeedRow.Item -> when (val item = row.item) {
            is FeedItem.TerminalFeedItem ->
                TerminalEventRow(item.terminal, time, onOpen)
            is FeedItem.PendingPromptFeedItem ->
                PendingPromptEventRow(item.terminal, item.prompt, arrival = time, onOpen = onOpen, session = session, scope = scope)
            is FeedItem.NoteFeedItem ->
                NoteEventRow(item.note, time)
            is FeedItem.UnknownFeedItem ->
                UnknownEventRow(item, time)
        }
        is FeedRow.FloorEvent -> FloorEventRow(row.floor, row.event, time, onOpenFloors)
        is FeedRow.PendingBuild -> PendingBuildRow(row.build, time, onOpenFloors)
    }
}

/** Row frame: 8 dp dot · weighted text block · mono 11 right time, min 56 pt. */
@Composable
private fun RowScaffold(
    dot: Color,
    title: @Composable () -> Unit,
    time: String,
    meta: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickMod = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(clickMod),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(6.dp))
            title()
            meta?.invoke()
            body?.invoke()
            Spacer(Modifier.height(6.dp))
        }
        Text(
            time,
            fontFamily = MaestriType.Mono,
            fontSize = 11.sp,
            color = if (time.isBlank()) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Terminal event — digest parity: one mono meta line (state · last preview line). */
@Composable
private fun TerminalEventRow(
    t: TerminalCard,
    time: String,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
) {
    val c = LocalMaestriColors.current
    RowScaffold(
        dot = rowDotColor(t),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name + (t.accountName?.let { " · $it" } ?: ""),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (t.isManager) {
                    Spacer(Modifier.width(4.dp))
                    Text(" (maestro)", fontSize = 10.sp, color = c.warning)
                }
            }
        },
        time = time,
        meta = {
            Text(
                metaLine(t),
                fontFamily = MaestriType.Mono,
                fontSize = 11.5.sp,
                color = monoMetaColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = { onOpen(t.id, t.name) },
    )
}

/** Row dot = REAL state, claude status slots only (terracotta is chrome, never data). */
@Composable
private fun rowDotColor(t: TerminalCard): Color {
    val c = LocalMaestriColors.current
    return when {
        t.needsAttention -> c.danger
        t.isActive == true -> c.positive
        t.isRunning -> c.positive
        t.isLive -> c.warning
        else -> Palette.StoneGray
    }
}

/** "floor · state · last-preview-line" — wire truth only, no invented copy. */
private fun metaLine(t: TerminalCard): String = buildString {
    append(t.floorName ?: "floor")
    append(" · ")
    append(if (t.needsAttention) "needsAttention" else if (t.isActive == true) "working" else if (t.isRunning) "running" else "stopped")
    t.preview.lastOrNull()?.takeIf { it.isNotBlank() }?.let {
        append(" · ")
        append(it.trim())
    }
}

/** Pending prompt — the actionable row keeps Approve/Reject with the shipped semantics. */
@Composable
private fun PendingPromptEventRow(
    t: TerminalCard,
    prompt: String,
    arrival: String,
    onOpen: (terminalId: String, terminalName: String) -> Unit,
    session: WireSession,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var busy by remember(t.id) { mutableStateOf(false) }
    var fail by remember(t.id) { mutableStateOf<String?>(null) }
    val c = LocalMaestriColors.current
    RowScaffold(
        dot = c.danger,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("S/n · ", color = c.warning, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    t.name + (t.accountName?.let { " · $it" } ?: ""),
                    fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        time = arrival,
        meta = {
            Text(
                metaLine(t),
                fontFamily = MaestriType.Mono,
                fontSize = 11.5.sp,
                color = monoMetaColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        body = {
            Text(
                prompt,
                color = c.warning,
                fontFamily = MaestriType.Mono,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        android.util.Log.d("QAFeed", "pending row tap: Reject term=${t.id}")
                        busy = true
                        scope.launch {
                            try {
                                session.reject(t.id)
                                fail = null
                                android.util.Log.d("QAFeed", "pending row Reject returned ok term=${t.id}")
                            } catch (e: Exception) {
                                fail = (e as? app.maestri.wire.ApiException)?.let { "HTTP ${it.status}: ${it.message}" }
                                    ?: "failed: ${e.message}"
                                android.util.Log.e("QAFeed", "pending row Reject threw term=${t.id}: $e")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Reject") }
                Button(
                    enabled = !busy,
                    onClick = {
                        android.util.Log.d("QAFeed", "pending row tap: Approve term=${t.id}")
                        busy = true
                        scope.launch {
                            try {
                                session.approve(t.id)
                                fail = null
                                android.util.Log.d("QAFeed", "pending row Approve returned ok term=${t.id}")
                            } catch (e: Exception) {
                                fail = (e as? app.maestri.wire.ApiException)?.let { "HTTP ${it.status}: ${it.message}" }
                                    ?: "failed: ${e.message}"
                                android.util.Log.e("QAFeed", "pending row Approve threw term=${t.id}: $e")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Approve") }
                fail?.let {
                    Text(
                        it,
                        color = c.warning,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        },
        onClick = { onOpen(t.id, t.name) },
    )
}

@Composable
private fun NoteEventRow(n: NoteCard, time: String) {
    val noteMeta: String = listOf(n.floorName, n.preview.firstOrNull()?.trim()?.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" · ")
    RowScaffold(
        dot = Palette.StoneGray,
        title = {
            Text(n.displayName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        time = time,
        meta = if (noteMeta.isNotBlank()) {
            {
                Text(
                    noteMeta,
                    fontFamily = MaestriType.Mono,
                    fontSize = 11.5.sp,
                    color = monoMetaColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun UnknownEventRow(item: FeedItem.UnknownFeedItem, time: String) {
    val subtitle: String? = item.title
    RowScaffold(
        dot = Palette.StoneGray,
        title = {
            Text(
                "unsupported item (${item.kind.ifBlank { "unknown" }})",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        time = time,
        meta = if (subtitle.isNullOrBlank()) {
            null
        } else {
            {
                Text(
                    subtitle,
                    fontFamily = MaestriType.Mono,
                    fontSize = 11.5.sp,
                    color = monoMetaColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** Floor event rows from live FloorMeta (dots: merged=ok, PR=warn/attention, trouble=attention). */
@Composable
private fun FloorEventRow(
    f: FloorMeta,
    event: FloorEventKind,
    time: String,
    onOpenFloors: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val dot: Color
    val title: @Composable () -> Unit
    val metaText: String
    when (event) {
        FloorEventKind.Landed -> {
            dot = c.positive
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Floor landed: ", fontSize = 13.sp)
                    Text(f.branch ?: f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            metaText = listOfNotNull(pullCaption(f), f.statusText).joinToString(" · ")
        }
        FloorEventKind.PullRequest -> {
            dot = if (pullAttentionBad(f)) c.danger else c.warning
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PR opened: ", fontSize = 13.sp)
                    Text(f.pullTitle ?: f.branch ?: f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            metaText = listOfNotNull(pullCaption(f), f.pullAttention, f.statusText).joinToString(" · ")
        }
        FloorEventKind.NeedsAttention -> {
            dot = c.danger
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Floor needs attention: ", fontSize = 13.sp)
                    Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            metaText = f.statusText ?: ""
        }
        FloorEventKind.Status -> {
            dot = Palette.StoneGray
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val pct = statusProgressPct(f.statusProgress)
                    if (pct != null) {
                        Text(" · $pct%", fontFamily = MaestriType.Mono, fontSize = 11.sp, color = monoMetaColor())
                    }
                }
            }
            metaText = f.statusText ?: ""
        }
    }
    RowScaffold(
        dot = dot,
        title = title,
        time = time,
        meta = if (metaText.isNotBlank()) {
            {
                Text(
                    metaText,
                    fontFamily = MaestriType.Mono,
                    fontSize = 11.5.sp,
                    color = monoMetaColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        onClick = onOpenFloors,
    )
}

private fun pullAttentionBad(f: FloorMeta): Boolean =
    f.pullAttention in setOf("conflicts", "changesRequested", "checksFailing")

/** statusProgress → integer percent, only when the value is a plausible 0–100 number. */
private fun statusProgressPct(p: Double?): Int? = when {
    p == null || p.isNaN() || p < 0.0 || p > 100.0 -> null
    p <= 1.0 -> (p * 100.0).roundToInt()
    else -> p.roundToInt()
}

/** "PR #n · <branch>" caption for pull-bearing rows (wire truth; null when no PR data). */
private fun pullCaption(f: FloorMeta): String? {
    if (f.pullState == null && f.pullNumber == null) return null
    val parts = buildList {
        if (f.pullNumber != null) add("PR #${f.pullNumber}")
        if (!f.branch.isNullOrBlank()) add(f.branch!!)
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** P4-A pending floor build — real progress from pendingFloors, surfaced in floors/PR filters. */
@Composable
private fun PendingBuildRow(p: PendingFloorMeta, time: String, onOpenFloors: () -> Unit) {
    val c = LocalMaestriColors.current
    RowScaffold(
        dot = if (p.error != null) c.danger else c.warning,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Floor building: ", fontSize = 13.sp)
                Text(
                    listOfNotNull(p.branch, p.name).firstOrNull() ?: "floor",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        time = time,
        meta = {
            Text(
                listOfNotNull(
                    "${p.completedItems}/${p.totalItems} items",
                    p.error,
                ).joinToString(" · "),
                fontFamily = MaestriType.Mono,
                fontSize = 11.5.sp,
                color = monoMetaColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onOpenFloors,
    )
}

/** The Maestro asking the person for something — kept above the feed items; disabled Acknowledge is honest. */
@Composable
private fun MaestroRequestBanner(t: TerminalCard, onOpen: (terminalId: String, terminalName: String) -> Unit) {
    val c = LocalMaestriColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = c.warning.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚑ Maestro request", color = c.warning, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(" · ${t.name}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(t.maestroRequestMessage ?: "", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {}, enabled = false) { Text("Acknowledge") }
                Text(
                    "(awaiting schema)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpen(t.id, t.name) }) { Text("Open terminal") }
            }
        }
    }
}

private fun truncate(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

/** State glyph from FloorMeta (chips lane): needs-attention ⚠ when the row carries
 *  attention or a blocked status; normal floors get no glyph (hasActivity/activity dot
 *  already surfaces elsewhere). clone-missing/copy ERROR are not on FloorMeta — the
 *  floors list screen (FloorRow.isCloneMissing) owns those; honest limitation. */
private fun floorStateGlyph(f: FloorMeta): String? = when {
    (f.attentionCount ?: 0) > 0 -> "⚠"
    f.statusState == "blocked" -> "⚠"
    else -> null
}

@Composable
fun ConnChip(state: SocketState, err: String?) {
    val (color, label) = when (state) {
        SocketState.OPEN -> MaterialTheme.colorScheme.primary to "live"
        SocketState.CONNECTING -> LocalMaestriColors.current.warning to "connecting"
        SocketState.CLOSED -> MaterialTheme.colorScheme.error to "down"
        SocketState.IDLE -> Color.Gray to "off"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = color)
        if (state == SocketState.CLOSED && err != null) {
            Text(" · $err", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PromptQuestion(prompt: String) {
    Text(
        prompt,
        color = LocalMaestriColors.current.warning,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PendingPromptCard(   // public: also used by TerminalScreen and ChatScreens
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
                Text("S/n · ", color = LocalMaestriColors.current.warning, fontWeight = FontWeight.Bold)
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
                Text(it, color = LocalMaestriColors.current.warning, fontSize = 12.sp)
            }
        }
    }
}