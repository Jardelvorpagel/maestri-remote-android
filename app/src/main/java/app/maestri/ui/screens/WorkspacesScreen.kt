package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import app.maestri.ui.Style.Chrome
import app.maestri.ui.Style.GlyphOrIcon
import app.maestri.ui.Style.IconPill
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.softHalo
import app.maestri.ui.Style.warmRing
import app.maestri.wire.WorkspaceMeta
import app.maestri.wire.session.WireSession
import app.maestri.ui.FloorsStrings

/**
 * Workspace list — design-v2 "modern" parity (spec frame 01): ring-depth cards
 * (`Modifier.warmRing` + 12 radius + warm card), glass chips (translucent + hairline +
 * halo; Compose 1.7 has no backdrop blur — documented deviation), 44 pt icon squircle,
 * card meta on HUD mono, locked card dimmed to 45 %, thumb action bar of 4 × 44 pt
 * icon pills bottom-right above the spine, terracotta active states. MaestriSpine
 * stays where it is (MainActivity overlay — untouched).
 *
 * Serif 30 pt root title is deferred (wave 4) per the brief.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    session: WireSession,
    onOpenWorkspace: (wsId: String, wsName: String) -> Unit,
    onOpenFloors: (wsId: String, wsName: String) -> Unit = { _, _ -> },
    onOpenSettings: (() -> Unit)? = null,
    /** ＋ pill: open the pairing flow (the only way to add a machine). */
    onAddMachine: (() -> Unit)? = null,
    /** 🔔 pill: the notifications settings route (P4-A). */
    onOpenNotifications: (() -> Unit)? = null,
    /** 🔔 pill's terracotta active state = system notifications actually armed. */
    notificationsArmed: Boolean = false,
) {
    var list by remember { mutableStateOf<List<WorkspaceMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 🔔 pill's terracotta ACTIVE state = notifications actually armed. The router lives
    // in Root (MainActivity wiring), which passes the read-once system answer in.

    // Entering the workspace list leaves any feed socket from before behind.
    LaunchedEffect(Unit) {
        if (session.feed.currentUrl != null) session.feed.close()
    }

    suspend fun refresh(quiet: Boolean = false) {
        if (!quiet) loading = true
        try {
            list = session.workspaces()
            err = null
        } catch (e: app.maestri.wire.ApiException) {
            if (!quiet) err = "HTTP ${e.status}${e.code?.let { " ($it)" } ?: ""}: ${e.message}"
        } catch (e: Exception) {
            if (!quiet) err = e.message ?: "network error"
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(15_000)
            refresh(quiet = true)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch { refresh() }
            },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(Rhythm.Gutter.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Workspaces",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        val p = session.serverInfo
                        if (p != null) {
                            Text(
                                "${p.name} · ${p.role ?: "?"}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onOpenSettings != null) {
                            Text(
                                "⚙",
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .padding(vertical = 11.dp) // 44 pt touch on the header gear too
                                    .clickable { onOpenSettings?.invoke() },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "live · polled 15 s · tap to open the feed",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (loading && list.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    err?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                        // clear the thumb action bar + spine (Rhythm.SpineHeight + bar + gap)
                        contentPadding = PaddingValues(bottom = 132.dp),
                    ) {
                        items(list, key = { it.id }) { ws ->
                            WorkspaceCard(
                                ws = ws,
                                onOpen = { onOpenWorkspace(ws.id, ws.name) },
                                onLocked = {
                                    scope.launch { snack.showSnackbar("Workspace locked on the host plan — feed unavailable") }
                                },
                                onOpenFloors = { onOpenFloors(ws.id, ws.name) },
                            )
                        }
                    }
                }

                // Thumb action bar (spec frame 01): 4 × 44 pt icon pills, bottom-right,
                // above the spine. Real affordances wired; unavailable ones disabled with
                // semantics — never dead-looking-active.
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Rhythm.Gutter.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconPill(
                        glyph = "＋",
                        label = "Pair another computer",
                        enabled = onAddMachine != null,
                        onClick = { onAddMachine?.invoke() },
                    )
                    IconPill(
                        glyph = "⚙",
                        label = "Settings",
                        enabled = onOpenSettings != null,
                        onClick = { onOpenSettings?.invoke() },
                    )
                    IconPill(
                        glyph = "🔔",
                        label = "Notifications",
                        active = notificationsArmed,
                        enabled = onOpenNotifications != null,
                        onClick = { onOpenNotifications?.invoke() },
                    )
                    // The mock's 4th pill is the host's add-workspace hotkey (⌃⌥↩ at 13 px
                    // in the mock) — no touch equivalent; honest-disabled, mock glyph.
                    HotkeyPill()
                }
            }
        }
    }
}

/** The mock's ⌃⌥↩ pill: 44 pt circle, 12 sp glyph, honest-disabled (host-only shortcut). */
@Composable
private fun HotkeyPill() {
    val c = LocalMaestriColors.current
    Box(
        Modifier
            .size(Rhythm.TouchTarget.dp)
            .background(c.glass, CircleShape)
            .border(1.dp, c.hairline, CircleShape)
            .alpha(0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text("⌃⌥↩", color = c.secondaryLabel, fontSize = 12.sp)  // mock glyph verbatim
    }
}

/**
 * One workspace card, claude ring depth: warm card surface, 12 radius, hairline ring
 * (+ crimson ring while the card carries attention), 44 pt icon squircle, name 15/600,
 * HUD-mono meta line, glass `Floors ›` chip. Locked card: 45 % + a locked chip instead
 * of the floors affordance (the feed behind it is unavailable).
 */
@Composable
private fun WorkspaceCard(
    ws: WorkspaceMeta,
    onOpen: () -> Unit,
    onLocked: () -> Unit,
    onOpenFloors: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val chipRadius = RoundedCornerShape(15.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (ws.isLocked) Modifier.alpha(0.45f) else Modifier)
            .warmRing(
                if (ws.attentionCount > 0) c.danger.copy(alpha = 0.55f) else c.hairline,
                MaestriRadii.Card,
            )
            .background(c.card, cardShape())
            .clickable { if (ws.isLocked) onLocked() else onOpen() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 44 pt icon squircle: accent 18–22 % mix + accent glyph. WorkspaceMeta has
            // NO vendor datum (no dominant-agent field), so the mock's per-vendor mix
            // can't be honest here — terracotta mix for every card (flagged).
            Box(
                Modifier
                    .size(Rhythm.TouchTarget.dp)
                    .background(c.accentSoft, RoundedCornerShape(MaestriRadii.Control)),
                contentAlignment = Alignment.Center,
            ) {
                GlyphOrIcon(ws.icon, fontSize = 19.sp, tint = c.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ws.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("${ws.runningTerminalCount}/${ws.terminalCount} running")
                        if (ws.isLoaded) append(" · loaded") else append(" · not loaded")
                        ws.groupName?.let { append(" · $it") }
                    },
                    style = monoHud(11, color = Palette.StoneGray),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ws.attentionCount > 0) {
                // attention badge: crimson count pill
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(c.danger.copy(alpha = 0.18f), CircleShape)
                        .padding(6.dp),
                ) {
                    Text(
                        "${ws.attentionCount}",
                        color = c.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            if (ws.hasActivity) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(8.dp)
                        .background(c.accent, CircleShape),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (ws.isLocked) {
                // locked chip replaces the floors affordance (mock frame 01)
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 30.dp)
                        .softHalo(radius = 15.dp, elevation = 2.dp)
                        .background(c.glass, chipRadius)
                        .border(1.dp, c.hairline, chipRadius)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Locked", style = monoHud(11, color = Palette.StoneGray))
                }
            } else {
                // Glass chip: translucent + hairline ring + whisper halo. 44 pt touch via
                // heightIn (visual stays the mock's compact chip inside a taller target).
                Box(
                    Modifier
                        .softHalo(radius = 15.dp, elevation = 2.dp)
                        .heightIn(min = Rhythm.TouchTarget.dp)
                        .background(c.glass, chipRadius)
                        .border(1.dp, c.hairline, chipRadius)
                        .clickable(onClick = onOpenFloors),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            FloorsStrings.floors,
                            fontSize = 11.5.sp,
                            color = c.secondaryLabel,
                            maxLines = 1,
                        )
                        Text(" ›", fontSize = 11.5.sp, color = Palette.StoneGray)
                    }
                }
            }
        }
    }
}