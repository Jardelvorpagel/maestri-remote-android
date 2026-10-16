package app.maestri.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.iconGlyph
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import app.maestri.wire.WorkspaceMeta
import app.maestri.wire.session.WireSession
import app.maestri.ui.Style.LocalMaestriColors

/**
 * Workspace list: `GET /api/workspaces` polled every 15 s (the docs' attention-watch
 * recipe) + pull-to-refresh. Attention badge = red dot with the count when > 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    session: WireSession,
    onOpenWorkspace: (wsId: String, wsName: String) -> Unit,
    onOpenFloors: (wsId: String, wsName: String) -> Unit = { _, _ -> },
) {
    var list by remember { mutableStateOf<List<WorkspaceMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
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
                ) {
                    items(list, key = { it.id }) { ws ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            onClick = {
                                if (ws.isLocked) {
                                    scope.launch { snack.showSnackbar("Workspace locked on the host plan — feed unavailable") }
                                } else {
                                    onOpenWorkspace(ws.id, ws.name)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        iconGlyph(ws.icon),
                                        fontSize = 18.sp,
                                        modifier = Modifier.width(28.dp),
                                    )
                                    Text(
                                        ws.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // attention badge: red dot w/ count
                                    if (ws.attentionCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f), CircleShape)
                                                .padding(6.dp),
                                        ) {
                                            Text(
                                                "${ws.attentionCount}",
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (ws.hasActivity) {
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        buildString {
                                            append("${ws.runningTerminalCount}/${ws.terminalCount} running")
                                            if (ws.isLoaded) append(" · loaded") else append(" · not loaded")
                                            ws.groupName?.let { append(" · $it") }
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // Floor-chips lane: every row opens the workspace's floors
                                    // + git screens (route via FloorsNav.link — MainActivity).
                                    TextButton(
                                        enabled = !ws.isLocked,
                                        onClick = { onOpenFloors(ws.id, ws.name) },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text("${app.maestri.ui.FloorsStrings.floors} ›", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}