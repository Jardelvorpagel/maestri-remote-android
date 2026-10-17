@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.maestri.ui.screens

/**
 * P3 floors UI: floors list → floor overview → (create sheet / land / PR / unify) and the
 * git file tree (browse → file → changes → diff → git actions).
 *
 * iOS model (module map): FloorOverviewSheet / FloorPullRequestForm / FloorSheets /
 * FileTreeScreen / FileTreeFileScreen / FileTreeChangesScreen / FileTreeGit / DirectoryBrowserSheet.
 * All wording from ui/FloorsStrings.kt (iOS-verbatim). Host-provided error text (provider-less
 * PR problem, land refusals, git refusals) is surfaced VERBATIM — it is client-visible contract.
 *
 * behavior-CANDIDATE marks (not evidenced in the docs/strings): see the lane report.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.wire.ApiException
import app.maestri.wire.CreateFloorResult
import app.maestri.wire.FloorsApi
import app.maestri.wire.FloorsSummary
import app.maestri.wire.FloorRow
import app.maestri.wire.FileTreeChange
import app.maestri.wire.FileTreeChanges
import app.maestri.wire.FileTreeDiff
import app.maestri.wire.FileTreeEntry
import app.maestri.wire.FileTreeGitOverview
import app.maestri.wire.FileTreeListing
import app.maestri.wire.PendingFloorRow
import app.maestri.wire.PrContext
import app.maestri.wire.session.WireSession
import app.maestri.ui.FloorsStrings as S
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.warmRing
import app.maestri.ui.Style.softHalo
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Rhythm
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.MaestriType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import app.maestri.ui.Style.BackLabel
import app.maestri.ui.Style.IconLabel

// ------------------------------------------------------------------ shared affordances

class FloorException(val api: ApiException, val display: String) : Exception(display)

fun errText(e: Exception): String = when (e) {
    is ApiException -> e.message.ifBlank { "HTTP ${e.status}" }
    else -> e.message ?: "network error"
}

/** Small mono console box for git output — git's own words, verbatim. */
@Composable
private fun OutputCard(output: String) {
    Surface(color = Color(0xFF0B0C0E), shape = controlShape(), modifier = Modifier.fillMaxWidth()) {
        Text(
            output.ifBlank { "(no output)" },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            color = Color(0xFF9FB3A1),
            modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()).heightIn(max = 220.dp),
        )
    }
}

@Composable
private fun ErrorLine(text: String?) {
    if (text != null) {
        Spacer(Modifier.height(6.dp))
        Text(text, color = LocalMaestriColors.current.warning, fontSize = 12.5.sp)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun statusColor(state: String?): Color = when (state) {
    "working" -> MaterialTheme.colorScheme.primary
    "blocked" -> MaterialTheme.colorScheme.error
    "review" -> LocalMaestriColors.current.warning
    "done" -> Color(0xFF63B3ED)
    "dormant" -> Color.Gray
    else -> Color.Gray
}

fun stateLabel(state: String?): String = when (state) {
    "working" -> S.statusWorking
    "blocked" -> S.statusBlocked
    "review" -> S.statusReview
    "done" -> S.statusDone
    else -> state ?: ""
}

// =========================================================================== floors list

/**
 * Floors list. Data: GET …/{ws}/feed (floors + pendingFloors + ground status), polled 5 s.
 * List order per contract: pending newest→oldest, real floors newest→oldest, ground last.
 */
@Composable
fun FloorsScreen(
    api: FloorsApi,
    wsId: String,
    wsName: String,
    onBack: () -> Unit,
    onOpenOverview: (floorId: String, floorName: String) -> Unit,  // "ground" allowed
) {
    var summary by remember { mutableStateOf<FloorsSummary?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var quickBranch by remember { mutableStateOf("") }
    var quickBusy by remember { mutableStateOf(false) }
    var quickErr by remember { mutableStateOf<String?>(null) }
    var actionErr by remember { mutableStateOf<String?>(null) }
    var showLand by remember { mutableStateOf<FloorRow?>(null) }
    var showDelete by remember { mutableStateOf<FloorRow?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            summary = api.floorsSummary(wsId)
            err = null
        } catch (e: Exception) {
            err = errText(e)
        }
    }

    suspend fun submitQuick() {
        val b = quickBranch.trim()
        if (b.isBlank() || quickBusy) return
        quickBusy = true
        try {
            val existing = api.branches(wsId).branches.any { it.equals(b, ignoreCase = true) }
            api.createFloor(wsId, name = b, gitIsolation = true, branch = b, existingBranch = existing)
            quickBranch = ""
            quickErr = null
        } catch (e: Exception) {
            quickErr = errText(e)   // host's own words (409 branch clash etc.) — verbatim
        } finally {
            quickBusy = false
            refresh()
        }
    }
    LaunchedEffect(wsId) {
        refresh()
        while (true) {
            kotlinx.coroutines.delay(5000)
            refresh()
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // frame-07 pushed lnav (48 px band): serif 24·500 title + ws meta + 44pt ＋ opens
        // the full create sheet; the bottom quick-create covers the one-tap path.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { BackLabel("Back") }
            Spacer(Modifier.width(12.dp))
            Text(
                S.floors,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                wsName,
                fontSize = 11.5.sp,
                color = Palette.StoneGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "＋",
                fontSize = 22.sp,
                color = LocalMaestriColors.current.accent,
                modifier = Modifier
                    .padding(horizontal = 11.dp, vertical = 11.dp)   // 44pt touch
                    .clickable { showCreate = true },
            )
        }
        ErrorLine(err)
        ErrorLine(actionErr)
        ErrorLine(quickErr)
        val s = summary
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // pending newest→oldest; real floors newest→oldest; ground last (contract).
            val pending = s.pendingFloors.reversed()
            val floors = s.floors.filter { !it.isGround }.reversed()
            val ground = s.floors.filter { it.isGround }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                pending.forEach { p ->
                    PendingFloorCard(p, busyId = busyId, onDismiss = {
                        busyId = p.id
                        scope.launch {
                            try {
                                api.dismissPending(wsId, p.id)
                            } catch (_: Exception) {
                                // 409 while copying, 404 after landing — both benign; the next poll reconciles.
                            }
                            busyId = null
                            refresh()
                        }
                    })
                }

                floors.forEach { f ->
                    FloorListRow(
                        f,
                        onOpen = { if (!f.isRemoving) onOpenOverview(f.id ?: "ground", f.name) },
                        onLand = { if (!f.isRemoving) showLand = f },
                        onDismissAction = { if (!f.isRemoving) showDelete = f },
                        onCopyAgain = {
                            busyId = f.id
                            actionErr = null
                            scope.launch {
                                try {
                                    api.createFloor(
                                        wsId,
                                        name = f.name,
                                        gitIsolation = true,
                                        branch = f.branch,
                                        existingBranch = true,
                                    )
                                } catch (e: Exception) {
                                    actionErr = errText(e)
                                } finally {
                                    busyId = null
                                    refresh()
                                }
                            }
                        },
                    )
                }

                ground.forEach { g ->
                    FloorListRow(
                        g,
                        onOpen = { onOpenOverview("ground", g.name) },
                        onLand = {}, onDismissAction = {}, onCopyAgain = {},
                    )
                }
            }
        }
    }

            // frame-07 bottom: active ＋ pill + mono input pill (44pt) — quick-create a
            // worktree floor from a typed branch (existingBranch when the name is taken).
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(LocalMaestriColors.current.canvasChrome)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "＋",
                    fontSize = 18.sp,
                    color = if (quickBranch.isNotBlank() && !quickBusy) LocalMaestriColors.current.accent
                            else LocalMaestriColors.current.secondaryLabel,
                    modifier = Modifier
                        .padding(horizontal = 13.dp, vertical = 13.dp)
                        .clickable(enabled = !quickBusy && quickBranch.isNotBlank()) { scope.launch { submitQuick() } },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .background(LocalMaestriColors.current.card, RoundedCornerShape(22.dp))
                        .border(1.dp, LocalMaestriColors.current.hairline, RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = quickBranch,
                        onValueChange = { quickBranch = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!quickBusy && quickBranch.isNotBlank()) scope.launch { submitQuick() } }),
                        textStyle = TextStyle(
                            fontFamily = MaestriType.Mono,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(LocalMaestriColors.current.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (quickBranch.isBlank()) {
                        Text(
                            S.newFloorFromBranch,
                            style = TextStyle(fontFamily = MaestriType.Mono, fontSize = 11.5.sp, color = Palette.StoneGray),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

    if (showCreate) {
        ModalBottomSheet(onDismissRequest = { showCreate = false }) {
            CreateFloorSheet(
                api = api,
                wsId = wsId,
                onDismissed = { showCreate = false },
            )
        }
    }

    // frame-07 card actions hoist the sheets the overview used to own
    showLand?.let { f ->
        ModalBottomSheet(onDismissRequest = { showLand = null }) {
            LandFloorSheet(api = api, wsId = wsId, floor = f, onDone = { showLand = null })
        }
    }
    showDelete?.let { f ->
        ModalBottomSheet(onDismissRequest = { showDelete = null }) {
            DeleteFloorSheet(api = api, wsId = wsId, row = f, onDone = { showDelete = null })
        }
    }
    }
}


@Composable
private fun PendingFloorCard(p: PendingFloorRow, busyId: String?, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .warmRing(LocalMaestriColors.current.hairline, MaestriRadii.Card)
            .background(LocalMaestriColors.current.card, cardShape()),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { S.preparingTheBranch }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        buildString {
                            if (p.totalItems > 0) append("${p.completedItems}/${p.totalItems} · ")
                            p.branch?.let { append(it) }
                        }.ifBlank { S.preparingTheBranch },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { if (busyId == null) onDismiss() }, enabled = busyId == null) {
                    Text(S.dismiss)
                }
            }
            if (p.error != null) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ ${p.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}


/** Frame-07 status chip: mono-less pill, ring = status colour at ~50 % (mock chips). */
@Composable
private fun FloorStatusChip(text: String, tint: Color, busy: Boolean = false) {
    val c = LocalMaestriColors.current
    Box(
        Modifier
            .softHalo(radius = 15.dp, elevation = 2.dp)
            .background(c.glass, RoundedCornerShape(15.dp))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = tint)
            Text(text, fontSize = 11.5.sp, color = tint, maxLines = 1)
        }
    }
}

/** Frame-07 48pt action row button (.rowbtn / .ac = terracotta filled, the primary). */
@Composable
private fun RowScope.RowBtn(label: String, fill: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalMaestriColors.current
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = Rhythm.TouchTarget.dp)
            .background(
                if (fill) c.accent else c.canvasChrome,
                controlShape(),
            )
            .then(
                if (fill) Modifier.border(1.dp, c.accent, controlShape())
                else Modifier.border(1.dp, c.hairline.copy(alpha = 0.6f), controlShape())
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (fill) c.onAccent else c.secondaryLabel,
            maxLines = 1,
        )
    }
}

/** Worktree card, frame 07: title-row + mono identity line + 48pt action rows.
 *  Card ring turns Crimson when the floor needs attention (clone missing / blocked
 *  status / attention). The chip ladder is honest to FloorRow's parsed fields — the
 *  mock's "PR · ready" chip needs the floor's pullRequest datum, which FloorRow does
 *  not parse (wire parse addition banned this lane); flagged as the open item. */
@Composable
private fun FloorListRow(
    f: FloorRow,
    onOpen: () -> Unit,
    onLand: () -> Unit,
    onDismissAction: () -> Unit,
    onCopyAgain: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val attention = f.isCloneMissing || f.statusState == "blocked" || f.attentionCount > 0

    Box(
        Modifier
            .fillMaxWidth()
            .then(if (f.isRemoving) Modifier.alpha(0.45f) else Modifier)
            .warmRing(
                if (attention) c.danger.copy(alpha = 0.55f) else c.hairline,
                MaestriRadii.Card,
            )
            .background(c.card, cardShape())
            .clickable { if (!f.isRemoving) onOpen() }
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ── line 1: name 13.5/600 + status chip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    f.isRemoving -> FloorStatusChip(S.removingFloor, tint = c.secondaryLabel, busy = true)
                    f.isCloneMissing -> FloorStatusChip("\u26a0 " + S.needsAttention, tint = c.danger)
                    f.statusState == "blocked" || f.attentionCount > 0 ->
                        FloorStatusChip("\u26a0 " + S.needsAttention, tint = c.danger)
                    else -> FloorStatusChip(S.cleanFloor, tint = c.positive)
                }
            }
            // ── line 2: mono 10.5 identity (branch · kind path) — the mock's sha pair is
            // not in FloorRow (open item); worktree path is real (feed row `folder`).
            Text(
                buildString {
                    f.branch?.let { append(it) }
                    f.folder?.let {
                        if (isNotEmpty()) append("  ·  ")
                        append(if (f.cloneKind == "copy") "copy " else "worktree ")
                        append(it)
                    }
                    if (f.isCloneMissing) {
                        if (isNotEmpty()) append("  ·  ")
                        append(S.cloneDirectoryMissing)
                    }
                    if (f.isGround) {
                        append("${S.ground}")
                    }
                },
                style = TextStyle(fontFamily = MaestriType.Mono, fontSize = 10.5.sp, lineHeight = 16.8.sp, color = Palette.StoneGray),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // ── status line (host words verbatim when present) + attention count
            if (f.statusText != null || f.attentionCount > 0) {
                Text(
                    buildString {
                        if (f.statusText != null) append("${stateLabel(f.statusState)}: ${f.statusText}")
                        f.statusProgress?.let { append(" (${(it * 100).toInt()}%)") }
                        if (f.attentionCount > 0) append(if (isNotEmpty()) "  ·  " else ""); append("⚠${f.attentionCount}")
                    },
                    fontSize = 11.sp,
                    color = if (f.statusState == "blocked") c.danger else c.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // ── 48pt action rows (ground keeps no card actions)
            if (!f.isGround) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.isCloneMissing) {
                        RowBtn(S.copyAgain, enabled = !f.isRemoving, onClick = onCopyAgain)
                        RowBtn(S.dismiss, onClick = onDismissAction)
                    } else {
                        RowBtn(S.reviewAction, onClick = onOpen)
                        RowBtn(S.land, fill = true, onClick = onLand)
                        RowBtn(S.dismiss, onClick = onDismissAction)
                    }
                }
            }
        }
    }
}

// ==================================================================== create-floor sheet

/** Simple radio row used by the iso-kind picker and branch options. */
@Composable
private fun RadioRow(title: String, desc: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 6.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The New Floor sheet. "Repository isolation": Git worktree / Independent copy (=APFS copy
 * per canCopyIndependently) / Same folder as Ground (simple floor); branch block for the
 * git kinds ("Create a new branch or use an existing one"); Clone Ground layout toggle.
 * The Pull Request side lists the project's open PRs; provider-less hosts surface their
 * own 409 reason text verbatim plus a "Search, or paste a link" field (number/#42/link).
 */
@Composable
private fun CreateFloorSheet(
    api: FloorsApi,
    wsId: String,
    onDismissed: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isoMode by remember { mutableStateOf(0) }          // 0 worktree, 1 independent copy, 2 simple (ground folder)
    var pickExisting by remember { mutableStateOf(false) }
    var pickedBranch by remember { mutableStateOf<String?>(null) }
    var newBranchText by remember { mutableStateOf("") }
    var copyGround by remember { mutableStateOf(false) }
    var isPrMode by remember { mutableStateOf(false) }
    var prPick by remember { mutableStateOf<app.maestri.wire.PrListResult.PrEntry?>(null) }
    var prQuery by remember { mutableStateOf("") }   // doubles as the paste field ("Search, or paste a link")

    var branches by remember { mutableStateOf<app.maestri.wire.BranchesResult?>(null) }
    var branchesErr by remember { mutableStateOf<String?>(null) }
    var prListData by remember { mutableStateOf<app.maestri.wire.PrListResult?>(null) }
    var prListErr by remember { mutableStateOf<String?>(null) }

    var creating by remember { mutableStateOf(false) }
    var createErr by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<CreateFloorResult?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            branches = api.branches(wsId)
        } catch (e: Exception) {
            branchesErr = errText(e)
        }
    }
    // PR list: fetch when the PR side opens or when typing pauses (client filters while
    // the person types and asks with query on a pause — contract wording).
    var prQueryStable by remember { mutableStateOf("") }
    LaunchedEffect(prQuery) {
        kotlinx.coroutines.delay(400)
        prQueryStable = prQuery
    }
    LaunchedEffect(isPrMode, prQueryStable) {
        if (!isPrMode) return@LaunchedEffect
        try {
            prListData = api.prList(wsId, prQueryStable.takeIf { it.isNotBlank() })
            prListErr = null
        } catch (e: Exception) {
            prListErr = errText(e)   // host's reason, verbatim
            prListData = null
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (created != null) {
            val r = created ?: return@Column
            Text(
                if (r.isPending) S.preparingTheBranch else "${S.newFloor} — ${r.floorId ?: ""}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 18.dp),
            )
            Button(onClick = onDismissed, modifier = Modifier.fillMaxWidth()) { Text("OK") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isPrMode) S.pullRequest else S.newFloor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissed, enabled = !creating) { Text("Cancel") }
            }
            if (!isPrMode) {
                Text(S.floorsExplanation, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Floor name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text(S.repositoryIsolation, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val gitOk = branches == null || (branches?.isGitRepository ?: true)
                RadioRow(
                    title = "Git worktree" + (branches?.current?.let { " on $it" } ?: ""),
                    desc = S.worktreeRepoDesc,
                    selected = isoMode == 0,
                    enabled = gitOk,
                    onClick = { isoMode = 0 },
                )
                RadioRow(S.independentCopy, S.independentCopyShortDesc, isoMode == 1, enabled = gitOk && (branches?.canCopyIndependently ?: true)) { isoMode = 1 }
                RadioRow(S.sameFolderAsGround, S.simpleFloorDesc, isoMode == 2, enabled = true) { isoMode = 2 }
                if (gitOk == false) Text(S.projectNotGit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (isoMode != 2) {
                    Spacer(Modifier.height(8.dp))
                    Text(S.createANewBranchOrUseAnExistingOne, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !pickExisting, onClick = { pickExisting = false; pickedBranch = null })
                        OutlinedTextField(
                            value = if (pickExisting) (pickedBranch ?: "") else newBranchText,
                            onValueChange = {
                                if (pickExisting) { pickExisting = false; pickedBranch = null }
                                newBranchText = it
                            },
                            label = { Text(S.newBranchName) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    branches?.let { b ->
                        val others = b.branches.filter { it != b.current }
                        if (others.isEmpty()) Text(S.repositoryHasOnlyCurrentBranch, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        others.take(6).forEach { br ->
                            Row(Modifier.fillMaxWidth().clickable { pickExisting = true; pickedBranch = br }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                RadioButton(selected = pickExisting && pickedBranch == br, onClick = null)
                                Spacer(Modifier.width(4.dp))
                                Text(br, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        // client-side pre-warning; the host's own 409 text arrives at create
                        val clash = (pickExisting && pickedBranch == b.current) || (!pickExisting && newBranchText == b.current)
                        if (clash) Text(S.groundBranchCheckedOutWorktree, fontSize = 11.sp, color = LocalMaestriColors.current.warning)
                    }
                    branchesErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = copyGround, onCheckedChange = { copyGround = it })
                        Column {
                            Text(S.cloneGroundLayout, fontSize = 13.sp)
                            Text(S.cloneGroundLayoutDesc, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prQuery,
                    onValueChange = { prQuery = it },
                    label = { Text(S.prListSearchOrPaste) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    prListErr != null -> Text(prListErr!!, color = LocalMaestriColors.current.warning, fontSize = 12.5.sp)
                    prListData != null && prListData!!.pullRequests.isEmpty() ->
                        Text(S.noOpenPullRequests, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    prListData != null -> Column {
                        prListData!!.pullRequests.take(10).forEach { pr ->
                            Row(Modifier.fillMaxWidth().clickable { prPick = pr }.padding(vertical = 4.dp)) {
                                RadioButton(selected = prPick == pr, onClick = null)
                                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text("#${pr.number} ${pr.title ?: ""}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(pr.author, pr.branch, pr.floorName?.let { "${S.floor}: $it" }).joinToString(" · "),
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (pr.isDraft) Text(S.draft, fontSize = 10.sp, color = LocalMaestriColors.current.warning)
                            }
                        }
                    }
                    else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            ErrorLine(createErr)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !creating,
                    onClick = {
                        creating = true
                        createErr = null
                        scope.launch {
                            try {
                                created = when {
                                    isPrMode -> {
                                        val prText = prPick?.let { "#${it.number}" } ?: prQuery.trim()
                                        api.createFloor(wsId, name, pullRequest = prText.takeIf { it.isNotBlank() })
                                    }
                                    isoMode == 2 -> api.createFloor(wsId, name, gitIsolation = false)
                                    isoMode == 1 -> api.createFloor(
                                        wsId, name, gitIsolation = true, independentCopy = true,
                                        copyGround = copyGround.takeIf { it },
                                    )
                                    pickExisting -> api.createFloor(
                                        wsId, name, gitIsolation = true, branch = pickedBranch,
                                        existingBranch = true, copyGround = copyGround.takeIf { it },
                                    )
                                    else -> api.createFloor(
                                        wsId, name, gitIsolation = true,
                                        branch = newBranchText.takeIf { it.isNotBlank() },
                                        copyGround = copyGround.takeIf { it },
                                    )
                                }
                            } catch (e: Exception) {
                                createErr = errText(e)   // host's words verbatim (name/branch clash, pullRequest 4xx…)
                            } finally {
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (creating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text(if (creating) S.creatingFloor else "Create")
                }
                OutlinedButton(onClick = { isPrMode = !isPrMode; prPick = null }, enabled = !creating) {
                    Text(if (isPrMode) "← ${S.newFloor}" else S.pullRequest)
                }
            }
        }
    }
}

// ==================================================================== floor overview

/**
 * Floor overview: identity + isolation + status + the lifecycle actions per contract
 * (Set Status… needs floorStatus; land/delete only real floors; ground unloads, never lands).
 */
@Composable
fun FloorOverviewScreen(
    api: FloorsApi,
    wsId: String,
    wsName: String,
    floorIdOrGround: String,
    onBack: () -> Unit,
    onOpenFiles: (rootPath: String) -> Unit,
    onOpenChanges: (rootPath: String) -> Unit,
) {
    val isGround = floorIdOrGround == "ground"
    var row by remember { mutableStateOf<FloorRow?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionErr by remember { mutableStateOf<String?>(null) }
    var showStatus by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLand by remember { mutableStateOf(false) }
    var showPr by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    // Root of this floor's file tree: the clone folder when the row carries one
    // (isolated floor), else the workspace's working directory (ground + simple).
    var filesRoot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(row?.id) {
        val f = row
        filesRoot = f?.folder ?: runCatching { api.workingDirectory(wsId) }.getOrNull()
    }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            val s = api.floorsSummary(wsId)
            row = (if (isGround) s.floors.filter { it.isGround } else s.floors.filter { it.id == floorIdOrGround }).firstOrNull()
            err = null
            if (!isGround && row == null) gone = true     // landed away / deleted by another client
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    LaunchedEffect(wsId, floorIdOrGround) {
        refresh()
        while (true) {
            kotlinx.coroutines.delay(4000)
            refresh()
        }
    }

    if (gone) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Spacer(Modifier.height(20.dp))
            Text(S.thisFloorIsntThereAnymore, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Column(Modifier.weight(1f)) {
                Text(row?.name ?: floorIdOrGround, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(wsName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        ErrorLine(err)

        row?.let { f ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor(f.statusState))
                        Text(
                            when {
                                f.isGround -> S.ground
                                f.cloneKind == "copy" -> S.independentCopy
                                f.isGitIsolated -> S.gitWorktreeOn.format(f.branch ?: "") + (" (copy-on-write)")
                                else -> S.sameFolderAsGround
                            },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        Text(stateLabel(f.statusState).ifBlank { "" }, fontSize = 12.sp, color = statusColor(f.statusState))
                    }
                    f.statusText?.let {
                        Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        f.statusProgress?.let { p ->
                            LinearProgressIndicator(
                                progress = { p.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                    f.branch?.let {
                        InfoRow("Branch", it, trailing = "⧉")   // copy branch name
                    }
                    f.folder?.let { InfoRow("Folder", it) }
                    if (f.attentionCount > 0) Text("⚠ ${f.attentionCount} · ${S.lldNeedAttention.format(f.attentionCount)}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (f.isCloneMissing) Text("⚠ ${S.cloneDirectoryMissing}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { filesRoot?.let { onOpenFiles(it) } },
                enabled = filesRoot != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("▤ ${S.files}") }
            OutlinedButton(
                onClick = { filesRoot?.let { onOpenChanges(it) } },
                enabled = filesRoot != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("≡ ${S.changes}") }
            if (!isGround && (row?.isGitIsolated == true)) {
                Button(onClick = { showLand = true }, modifier = Modifier.fillMaxWidth()) { Text("⬇ ${S.landFloor}…") }
                OutlinedButton(onClick = { showPr = true }, modifier = Modifier.fillMaxWidth()) { Text("⑂ ${S.pullRequest}") }
            }
            if (isGround) {
                // ground: no land/no delete; PR context route 404s on ground (probed) — not offered.
            }
            OutlinedButton(onClick = { showStatus = true }, modifier = Modifier.fillMaxWidth()) { Text("◌ ${S.setStatus}") }
            if (!isGround) {
                OutlinedButton(onClick = { showRename = true }, modifier = Modifier.fillMaxWidth()) { Text("✎ ${S.renameFloor}") }
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            api.unloadFloor(wsId, floorIdOrGround)
                            actionErr = null
                        } catch (e: Exception) {
                            actionErr = errText(e)
                        }
                        busy = false
                        refresh()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("⏻ ${S.unload}") }
            if (!isGround) {
                OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("🗑 ${S.deleteFloor}…")
                }
            }
        }
        ErrorLine(actionErr)
    }

    val f = row
    if (showStatus && f != null) {
        SetStatusSheet(
            api = api, wsId = wsId, floorIdOrGround = floorIdOrGround, current = f,
            onDone = { showStatus = false; scope.launch { refresh() } },
        )
    }
    if (showRename && f != null) RenameFloorDialog(
        current = f.name,
        onCancel = { showRename = false },
        onRename = { name ->
            busy = true
            scope.launch {
                try {
                    api.renameFloor(wsId, floorIdOrGround, name)
                    actionErr = null
                    showRename = false
                } catch (e: Exception) {
                    actionErr = errText(e)
                }
                busy = false
                refresh()
            }
        },
    )
    if (showDelete && f != null) {
        DeleteFloorSheet(
            api = api, wsId = wsId, row = f,
            onDone = {
                showDelete = false
                if (it == null) {
                    scope.launch { refresh() }
                } else {
                    onBack()   // deleted (or force-confirmed) — leave the overview
                }
            },
        )
    }
    if (showLand && f != null) {
        LandFloorSheet(
            api = api, wsId = wsId, floor = f,
            onDone = { showLand = false; scope.launch { refresh() } },
        )
    }
    if (showPr && f != null) {
        FloorPullRequestSheet(api = api, wsId = wsId, floor = f, onDone = { showPr = false })
    }
}

@Composable
private fun InfoRow(label: String, value: String, trailing: String? = null) {
    val clip = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = trailing != null) { clip.setText(AnnotatedString(value)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ------------------------------------------------------------------- set status sheet

private val KNOWN_STATES = listOf("working", "blocked", "review", "done")

/** Set Status…: known states only (working/blocked/review/done) + text + 0…1 progress.
 *  Progress is kept only while the state doesn't change (contract's editor rule). */
@Composable
private fun SetStatusSheet(api: FloorsApi, wsId: String, floorIdOrGround: String, current: FloorRow, onDone: () -> Unit) {
    var state by remember { mutableStateOf(current.statusState ?: "working") }
    var text by remember { mutableStateOf(current.statusText ?: "") }
    var progress by remember { mutableStateOf<Float?>(current.statusProgress?.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
        Text(S.whatsHappeningOnThisFloor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KNOWN_STATES.forEach { st ->
                FilterChip(selected = state == st, onClick = {
                    if (st != state) {
                        state = st
                        progress = null   // state change discards progress (contract)
                    }
                }, label = { Text(stateLabel(st)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(S.floor) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("max 120 chars (host trims)", fontSize = 10.sp) },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("progress", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = (progress ?: 0f),
                onValueChange = { progress = it },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            Text((progress ?: 0f).let { "${(it * 100).toInt()}%" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { progress = null }) { Text("clear") }
        }
        ErrorLine(erro)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
            Button(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            api.setFloorStatus(
                                wsId, floorIdOrGround,
                                FloorsApi.FloorStatusEdit(state, text.take(120).trim(), progress?.toDouble()),
                            )
                            erro = null
                            onDone()
                        } catch (e: Exception) {
                            erro = errText(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun RenameFloorDialog(current: String, onCancel: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(S.renameFloor) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------- delete floor sheet

/** Delete Floor…: confirm + Delete Floor and Branch; 409 (clone unremovable) → the host's
 *  reason and a force retry ("Remove the floor from workspace anyway?"). */
@Composable
private fun DeleteFloorSheet(api: FloorsApi, wsId: String, row: FloorRow, onDone: (deleted: Boolean?) -> Unit) {
    var deleteBranch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var forcing by remember { mutableStateOf(false) }
    var cleanupFailText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text(if (deleteBranch) S.deleteFloorAndBranch else S.deleteFloor) },
        text = {
            Column {
                Text(
                    when {
                        cleanupFailText != null -> S.failedToCleanUpFloor.format(row.name, cleanupFailText ?: "")
                        row.cloneKind == "copy" -> S.independentCopyDeleteDesc
                        row.isGitIsolated -> S.worktreeDeleteDesc
                        else -> S.deleteConfirmNoBranch.format(row.name)
                    },
                    fontSize = 13.sp,
                )
                if (cleanupFailText == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(S.deleteConfirmNoBranch.format(row.name), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.isGitIsolated || row.branch != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteBranch, onCheckedChange = { deleteBranch = it })
                        Text("🗑 " + S.deleteTheBranchAfterTheMerge.format(row.branch ?: ""), fontSize = 12.sp)
                    }
                }
                if (cleanupFailText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(S.theFloorsCloneCouldNotBeRemoved, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                ErrorLine(erro)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            api.deleteFloor(wsId, row.id ?: "", deleteBranch, force = forcing)
                            onDone(true)
                        } catch (e: ApiException) {
                            if (e.status == 409 && !forcing) {
                                cleanupFailText = e.message   // host's reason → force path
                                forcing = true
                            } else {
                                erro = errText(e)
                            }
                        } catch (e: Exception) {
                            erro = errText(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(if (forcing) "Delete anyway" else if (deleteBranch) S.deleteFloorAndBranch else S.deleteFloor, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

// ======================================================================= landing sheet

/** Land floor: target picker (the project's local branches) → landing preview
 *  (FloorLanding: commitCount / files ±/ conflicts) → Land (with deleteBranch + keepFloor).
 *  409 refusal (uncommitted work / conflicts) shows the host's own Portuguese text verbatim. */
@Composable
private fun LandFloorSheet(api: FloorsApi, wsId: String, floor: FloorRow, onDone: () -> Unit) {
    var target by remember { mutableStateOf(floor.branch ?: "") }
    var landing by remember { mutableStateOf<app.maestri.wire.FloorLanding?>(null) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var deleteBranch by remember { mutableStateOf(false) }
    var keepFloor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var doneMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadPreview() {
        try {
            landing = api.landingPreview(wsId, floor.id ?: "", target.takeIf { it.isNotBlank() })
            loadErr = null
        } catch (e: Exception) {
            loadErr = errText(e); landing = null
        }
    }
    LaunchedEffect(floor.id, target) {
        if (floor.isGitIsolated) loadPreview()
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Text(S.landFloor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Text(S.targetBranch, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val opts = landing?.branches ?: emptyList()
        if (opts.isEmpty()) {
            Text(S.noOtherBranches, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            opts.forEach { br ->
                Row(Modifier.fillMaxWidth().clickable { target = br }.padding(vertical = 3.dp)) {
                    RadioButton(selected = target == br, onClick = { target = br })
                    Text(br, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 4.dp))
                    if (br == landing?.groundBranch) {
                        Text("  · ${S.checkedOutFromGit}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        landing?.let { l ->
            Spacer(Modifier.height(10.dp))
            if (l.isMerge) {
                Text(S.mergeIntoQ.format(l.branch ?: S.ground, l.target ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(S.theHostMergesItNow, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(S.maestriWillUpdateThisBranchOnGround, fontSize = 12.5.sp)
            }
            if (l.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1518)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("⚠ ${S.codeConflictsWereFound}", color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(S.conflictsWith.format("${l.conflicts.size} path(s)"), fontSize = 11.sp, color = LocalMaestriColors.current.warning)
                        l.conflicts.take(8).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (l.commitCount == 0) {
                Text(S.noChangesAgainst.format(l.target ?: l.groundBranch ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(S.lldCommits.format(l.commitCount), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val add = l.files.sumOf { it.additions }
                val del = l.files.sumOf { it.deletions }
                Text(S.lldLinesAddedRemoved.format(add, del), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                l.files.take(12).forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("+${f.additions}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(" -${f.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (l.files.size > 12) Text("…${l.files.size - 12} more", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ErrorLine(loadErr)

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = deleteBranch, onCheckedChange = { deleteBranch = it })
            Text(S.deleteTheBranchAfterTheMerge.format(floor.branch ?: ""), fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = keepFloor, onCheckedChange = { keepFloor = it })
            Text(S.keepThisFloorAfterLanding, fontSize = 12.sp)
        }

        ErrorLine(erro)
        doneMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
            Button(
                enabled = !busy && floor.isGitIsolated && landing != null && (landing?.conflicts?.isEmpty() ?: true),
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = api.land(wsId, floor.id ?: "", target, deleteBranch, keepFloor)
                            doneMsg = when {
                                keepFloor -> S.landResultFloorStays
                                r.mergedInto != null -> S.landResultMerged.format(r.mergedInto)
                                else -> S.itsBranchStaysInTheProject   // no-merge land: branch kept (W3)
                            }
                            erro = null
                            kotlinx.coroutines.delay(1500)
                            onDone()
                        } catch (e: Exception) {
                            erro = errText(e)       // host's refusal words, verbatim
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(4.dp))
                Text(if (busy) S.landing else S.landFloor)
            }
        }
    }
}

// ==================================================================== floor PR sheet

/**
 * FloorPullRequestForm: prefill from …/pull-request/context (title = last commit's message,
 * base picker), draft toggle when supportsDraft, "Ask an Agent" for the floor's running
 * agents (task review). Open → 503/409 show the host's text verbatim; 502 offers
 * "Open on the Host's Website Instead". Unpushed-count note under the compose box.
 */
@Composable
private fun FloorPullRequestSheet(api: FloorsApi, wsId: String, floor: FloorRow, onDone: () -> Unit) {
    var ctx by remember { mutableStateOf<PrContext?>(null) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }   // 503 unavailable (provider-less)
    var openedUrl by remember { mutableStateOf<String?>(null) }
    var openedOnWebsite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var ctxLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(floor.id) {
        try {
            val c = api.prContext(wsId, floor.id ?: "")
            ctx = c
            title = c.title ?: floor.name
            body = c.body ?: ""
            base = c.base ?: ""
            loadErr = null
        } catch (e: Exception) {
            loadErr = errText(e)
        } finally {
            ctxLoaded = true
        }
    }
    fun ask(task: String, agent: PrContext.PrAgent) {
        busy = true
        scope.launch {
            try {
                api.prHandOff(wsId, floor.id ?: "", agent.id, task, null, null)
                erro = null
            } catch (e: Exception) {
                erro = errText(e)
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        if (!ctxLoaded) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(S.openPullRequest, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            ctx?.problem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = LocalMaestriColors.current.warning, fontSize = 12.5.sp)   // host's reason, verbatim (client-visible)
            }
            ErrorLine(loadErr)
            ctx?.let { c ->
                Spacer(Modifier.height(10.dp))
                Text(S.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                Text(S.body, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                Spacer(Modifier.height(6.dp))
                if (c.base != null) InfoRow(S.targetBranch, c.base)
                if (c.supportsDraft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft, onCheckedChange = { draft = it })
                        Text(S.draft, fontSize = 13.sp)
                    }
                    if (draft) Text(S.draftsCantBeMerged, fontSize = 11.sp, color = LocalMaestriColors.current.warning)
                }
                if ((c.unpushedCommits ?: 0) > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (c.unpushedCommits == 1) S.oneCommitNotInPr else S.lldCommitsNotInPr.format(c.unpushedCommits),
                        fontSize = 11.5.sp, color = LocalMaestriColors.current.warning,
                    )
                }
                if (c.agents.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(S.askAgentToReview, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    c.agents.take(4).forEach { ag ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            StatusDot(if (ag.isRunning) MaterialTheme.colorScheme.primary else Color.Gray)
                            Text("  ${ag.name}", fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy && ag.isRunning, onClick = { ask("review", ag) }) { Text(S.askAgentToReview) }
                        }
                    }
                }
                if (c.providerName != null) InfoRow("provider", c.providerName)
                if (c.account != null) InfoRow("account", c.account)
            }
            Spacer(Modifier.height(10.dp))
            if (erro != null) {
                Text(erro!!, color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp)
                if (erro.orEmpty().startsWith("503")) Text(S.opensOnTheHostsWebsiteAfterThePush, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (openedUrl != null || openedOnWebsite) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF12251A)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            if (openedUrl != null) S.itsOpenPageOpened else S.hostPageOpenedFinishThere,
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                        openedUrl?.let {
                            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (openedUrl == null && !openedOnWebsite) {
                    OutlinedButton(onClick = onDone, enabled = !busy) { Text("Close") }
                    Button(
                        enabled = !busy && ctx != null && ctx?.problem == null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // If the tree context said this repo IS a floor checkout, the floor route wins.
                            busy = true
                            scope.launch {
                                try {
                                    val r = api.prOpen(wsId, floor.id ?: "", base.ifBlank { "main" }, title.trim(), body, draft.takeIf { ctx?.supportsDraft == true })
                                    openedUrl = r.url
                                    openedOnWebsite = r.openedOnWebsite
                                    erro = null
                                } catch (e: ApiException) {
                                    unavailable = e.status == 503     // "unavailable" = host's words, client-visible
                                    erro = "HTTP ${e.status}" + (e.code?.let { " · $it" } ?: "") + (": ${e.message}")
                                } catch (e: Exception) {
                                    erro = errText(e)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(if (busy) S.checkingPullRequest else S.openPullRequest)
                    }
                } else {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

// ========================================================================== file tree

/**
 * FileTree browser (FileTreeScreen / DirectoryBrowserSheet model): entries of GET
 * …/nodes/{ft}/tree?path=&hidden= (root = the floor's clone dir or the workspace's working
 * directory), git line from the listing's `git` block, search, truncated-list note.
 */
@Composable
fun FileTreeScreen(
    api: FloorsApi,
    wsId: String,
    wsName: String,
    rootPath: String,
    onBack: () -> Unit,
    onOpenFile: (relPath: String) -> Unit,
    onOpenChanges: () -> Unit,
) {
    var nodeId by remember { mutableStateOf<String?>(null) }
    var rel by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<FileTreeListing?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<app.maestri.wire.FileTreeSearch?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(rootPath) {
        try {
            nodeId = api.fileTreeFor(wsId, rootPath)
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    LaunchedEffect(nodeId, rel, hidden) {
        val n = nodeId ?: return@LaunchedEffect
        try {
            listing = api.treeList(wsId, n, rel, hidden)
            searchHits = null
            err = null
        } catch (e: Exception) {
            err = errText(e)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Column(Modifier.weight(1f)) {
                Text(S.fileTree, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rootPath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onOpenChanges) { Text("≡ ${S.changes}") }
        }

        val l = listing
        if (l != null) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                l.gitBranch?.let {
                    Text("⑂ $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text("Δ${l.changedCount}", fontSize = 11.sp, color = if (l.changedCount > 0) LocalMaestriColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: run {
                    Text(S.projectNotGit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { hidden = !hidden }) {
                    Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                    Text(S.showHiddenFiles, fontSize = 10.5.sp)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(S.searchThisFolder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (query.isNotBlank()) {
                    TextButton(onClick = {
                        searching = true
                        scope.launch {
                            try {
                                searchHits = api.treeSearch(wsId, nodeId ?: "", query, hidden)
                            } catch (e: Exception) { err = errText(e) }
                            searching = false
                        }
                    }) { Text("⌕") }
                }
            },
        )

        ErrorLine(err)
        Spacer(Modifier.height(6.dp))

        val hits = searchHits
        if (hits != null) {
            Text("${S.searchThisFolder} — ${hits.hits.size}${if (hits.isTruncated) " · ${S.partialList}" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            hits.hits.forEach { EntryRow(it, it.path, onOpenDir = { rel = it }, onOpenFile = onOpenFile) }   // search: rel ignored, path root-relative
        } else if (l == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (l.entries.isEmpty()) {
            Text(if (l.path.isEmpty()) S.emptyFolder else "${S.emptyFolder} · ${l.path}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } else {
            if (rel.isNotEmpty()) {
                Text(
                    "…",
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable {
                        rel = rel.substringBeforeLast('/', "")
                    }.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(l.entries, key = { it.path }) { EntryRow(it, rel, onOpenDir = { rel = it }, onOpenFile = onOpenFile) }
            }
            if (l.isTruncated) {
                Spacer(Modifier.height(4.dp))
                Text("≡ ${S.partialList} · " + S.onlyTheFirstLldItemsAreShown.format(l.entries.size), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EntryRow(
    e: FileTreeEntry, rel: String, onOpenDir: (String) -> Unit, onOpenFile: (String) -> Unit,
) {
    // entry.path is ROOT-relative in every listing (probed: ?path=sub returns "sub/m1.txt"),
    // so navigation and tree/file?path= always use e.path as-is.
    val relOpen = e.path
    Row(
        Modifier.fillMaxWidth().clickable {
            if (e.isDirectory) onOpenDir(relOpen) else onOpenFile(relOpen)
        }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Text(if (e.isDirectory) "▤ ${e.name}" else if (e.isSymbolicLink) "↗ ${e.name}" else "▪ ${e.name}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (e.gitStatus != null) {
            Text(e.gitStatus, color = when (e.gitStatus) {
                "M", "?" -> LocalMaestriColors.current.warning
                "A", "R" -> MaterialTheme.colorScheme.primary
                "D", "!" -> MaterialTheme.colorScheme.error
                else -> Color.Gray
            }, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
        }
        Text(if (e.isDirectory) "${e.size}" else "${e.size}b", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ====================================================================== file screen

/** FileTreeFileScreen — read-only file content (tree/file bytes; text preview; binary card). */
@Composable
fun FileScreen(
    api: FloorsApi,
    wsId: String,
    rootPath: String,
    relPath: String,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf<FloorsApi.FileContent?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rootPath, relPath) {
        try {
            val nodeId = api.fileTreeFor(wsId, rootPath)
            content = api.treeFile(wsId, nodeId, relPath)
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Text(relPath, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        ErrorLine(err)
        val c = content
        when {
            err != null -> Text(S.couldntLoadFile, fontWeight = FontWeight.SemiBold)
            c is FloorsApi.FileContent.Text -> OutputCard(c.content)
            c is FloorsApi.FileContent.Binary -> Card(colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface)) {
                Column(Modifier.padding(12.dp)) {
                    Text(S.binary, fontWeight = FontWeight.SemiBold)
                    Text("${c.size} bytes · ${c.mime}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

// =================================================================== changes screen

/**
 * FileTreeChangesScreen: staged/unstaged change lists (± counts), per-file stage/unstage via
 * the FileTreeGit stage/unstage actions, commit with message (Stage All and Commit /
 * Commit), the git output card (git's own words), Stash, and the git overview sheet.
 */
@Composable
fun ChangesScreen(
    api: FloorsApi,
    wsId: String,
    wsName: String,
    rootPath: String,
    onBack: () -> Unit,
    onOpenDiff: (path: String, scope: String) -> Unit,
) {
    var nodeId by remember { mutableStateOf<String?>(null) }
    var changes by remember { mutableStateOf<FileTreeChanges?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var commitMsg by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var stageAll by remember { mutableStateOf(true) }
    var showGit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            changes = api.treeChanges(wsId, nodeId ?: return)
            err = null
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    LaunchedEffect(rootPath) {
        try {
            nodeId = api.fileTreeFor(wsId, rootPath)
            refresh()
        } catch (e: Exception) {
            err = errText(e)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Column(Modifier.weight(1f)) {
                Text(S.changes, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(changes?.branch ?: "…", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showGit = true }, enabled = nodeId != null && !busy) { Text(S.gitActionsForThisRepository) }
            TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("↺") }
        }
        ErrorLine(err)

        val c = changes
        if (c == null && err == null) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (c != null && c.staged.isEmpty() && c.unstaged.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(S.workingTreeIsClean, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (c != null) {
            ChangeSection(S.staged, c.staged, onOpenDiff, onStage = { path, stage ->
                scope.launch {
                    busy = true
                    try {
                        val r = api.gitAction(wsId, nodeId ?: return@launch, if (stage) "stage" else "unstage", api.gitStage(listOf(path)))
                        output = r.output
                    } catch (e: Exception) {
                        err = errText(e)
                    }
                    busy = false
                    refresh()
                }
            })
            Spacer(Modifier.height(10.dp))
            ChangeSection(S.untracked.ifBlank { "Unstaged" }, c.unstaged, onOpenDiff, onStage = { path, stage ->
                scope.launch {
                    busy = true
                    try {
                        val r = api.gitAction(wsId, nodeId ?: return@launch, if (stage) "stage" else "unstage", api.gitStage(listOf(path)))
                        output = r.output
                    } catch (e: Exception) {
                        err = errText(e)
                    }
                    busy = false
                    refresh()
                }
            })
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = commitMsg,
            onValueChange = { commitMsg = it },
            label = { Text(S.commitMessage) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { stageAll = !stageAll }) {
                Text(if (stageAll) "☑ ${S.stageAllAndCommit.split(' ')[0]}" else "☐")
            }
            Button(
                enabled = !busy && commitMsg.isNotBlank() && (stageAll || !changes?.staged.isNullOrEmpty()),
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    err = null
                    scope.launch {
                        try {
                            val r = api.gitAction(wsId, nodeId ?: return@launch, "commit", api.gitCommit(commitMsg.trim(), stageAll))
                            output = r.output ?: S.gitRefusedWithoutSayingWhy
                            commitMsg = ""
                        } catch (e: Exception) {
                            err = errText(e)
                        }
                        busy = false
                        refresh()
                    }
                },
            ) { Text(if (stageAll) S.stageAllAndCommit else S.commit) }
        }
        output?.let {
            Spacer(Modifier.height(8.dp))
            Text("git:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutputCard(it)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !busy && nodeId != null,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val r = api.gitAction(wsId, nodeId ?: return@launch, "stashPush", api.gitStashPush(null))
                        output = r.output
                    } catch (e: Exception) {
                        err = errText(e)
                    }
                    busy = false
                    refresh()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("${S.stashChanges} — ${S.putsAwayEveryChange}") }
    }

    if (showGit && nodeId != null) {
        ModalBottomSheet(onDismissRequest = { showGit = false }) {
            GitActionsSheet(api = api, wsId = wsId, nodeId = nodeId ?: "", onFinished = { showGit = false })
        }
    }
}

@Composable
private fun ChangeSection(
    title: String,
    list: List<FileTreeChange>,
    onOpenDiff: (path: String, scope: String) -> Unit,
    onStage: (path: String, stage: Boolean) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("${list.sumOf { it.additions ?: 0 }}+ ${list.sumOf { it.deletions ?: 0 }}−", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list.take(20).forEach { c ->
            val scopeStr = if (c.isStaged) "staged" else "unstaged"
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusGlyph(c.status),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when (c.status) {
                        "M", "?" -> LocalMaestriColors.current.warning
                        "A", "R" -> MaterialTheme.colorScheme.primary
                        "D", "!" -> MaterialTheme.colorScheme.error
                        else -> Color.Gray
                    },
                )
                Column(Modifier.weight(1f).padding(start = 6.dp).clickable { onOpenDiff(c.path, scopeStr) }) {
                    Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.directory.isNotEmpty()) Text(c.directory, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.additions != null || c.deletions != null) {
                        Text(
                            ((c.additions ?: 0).let { "+$it" }) + " " + ((c.deletions ?: 0).let { "-$it" }),
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onStage(c.path, !c.isStaged) }) {
                    Text(if (c.isStaged) S.unstagesThisFile else S.stagesThisFile)
                }
            }
        }
    }
}

private fun statusGlyph(letter: String): String = when (letter) {
    "M" -> "M"; "A" -> "A"; "D" -> "D"; "R" -> "R"; "C" -> "C"; "?" -> "?"; "!" -> "!"; "U" -> "U"; else -> letter
}

// ====================================================================== diff screen

/** Unified diff of one change: FileTreeDiff (scope staged|unstaged|combined). */
@Composable
fun DiffScreen(api: FloorsApi, wsId: String, nodeId: String, path: String, scope: String, onBack: () -> Unit) {
    var diff by remember { mutableStateOf<FileTreeDiff?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nodeId, path, scope) {
        try {
            diff = api.treeDiff(wsId, nodeId, path, scope)
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { BackLabel("Back") }
            Column(Modifier.weight(1f)) {
                Text(path, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scope, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ErrorLine(err)
        when {
            err != null -> Text(S.couldntLoadDiff, fontWeight = FontWeight.SemiBold)
            diff == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            diff!!.patch.isBlank() -> Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(S.noTextDiff, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> DiffPatch(diff!!.patch)
        }
    }
}

@Composable
private fun DiffPatch(patch: String) {
    Surface(color = Color(0xFF0B0C0E), shape = controlShape(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
            patch.lineSequence().forEach { line ->
                val (fg, bg) = when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> Color(0xFF6C757D) to Color.Transparent
                    line.startsWith("@@") || line.startsWith("@") -> Color(0xFF63B3ED) to Color.Transparent
                    line.startsWith("+") -> Color(0xFF7BD88F) to Color(0xFF12251A)
                    line.startsWith("-") -> Color(0xFFFF5C5C) to Color(0xFF2A1518)
                    else -> Color(0xFF9FB3A1) to Color.Transparent
                }
                Box(Modifier.fillMaxWidth().background(bg)) {
                    Text(line.ifBlank { " " }, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = fg, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

// ====================================================================== git actions

/** "Git actions for this repository" — FileTreeGitOverview + the named git actions. */
@Composable
private fun GitActionsSheet(api: FloorsApi, wsId: String, nodeId: String, onFinished: () -> Unit) {
    var ov by remember { mutableStateOf<FileTreeGitOverview?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var filterBranch by remember { mutableStateOf("") }
    var filterStash by remember { mutableStateOf("") }
    var showNewBranch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(nodeId) {
        try {
            ov = api.treeGitOverview(wsId, nodeId)
        } catch (e: Exception) {
            err = errText(e)
        }
    }
    fun run(action: String, args: org.json.JSONObject) {
        busy = true
        scope.launch {
            try {
                val r = api.gitAction(wsId, nodeId, action, args)
                output = r.output
                err = null
                ov = api.treeGitOverview(wsId, nodeId)
            } catch (e: Exception) {
                err = errText(e)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
        Text(S.gitActionsForThisRepository, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        ov?.let { o ->
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    o.branch?.let { "⑂ $it" },
                    if (o.hasUpstream) "▲" else null,
                    o.remotes.take(2).joinToString().ifBlank { null },
                ).joinToString("  ·  ").ifBlank { S.projectNotGit },
                fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorLine(err)
        output?.let { Spacer(Modifier.height(6.dp)); OutputCard(it) }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("pull" to S.pull, "push" to S.push, "sync" to S.sync, "fetch" to S.fetch).forEach { (a, label) ->
                OutlinedButton(enabled = !busy, onClick = { run(a, api.gitNoArgs()) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy, onClick = { showNewBranch = true }) { Text(S.newBranchAction, fontSize = 12.sp) }
            OutlinedButton(enabled = !busy && ov?.remotes?.isNotEmpty() == true, onClick = { run("publish", api.gitPublish(ov?.remotes?.first() ?: "")) }) { Text(S.publishBranch, fontSize = 12.sp) }
        }

        ov?.let { o ->
            Spacer(Modifier.height(10.dp))
            Text(S.searchBranches, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = filterBranch, onValueChange = { filterBranch = it }, label = { Text(S.searchBranches) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val visible: List<String> = (o.localBranches + (if (filterBranch.isNotBlank()) o.remoteBranches else emptyList()))
                .distinct()
                .filter { it.contains(filterBranch, ignoreCase = true) }
                .take(8)
            visible.forEach { br ->
                val isCurrent = br == o.branch
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (br.startsWith("origin/")) "⑂ " else "⑂ ") + br + if (isCurrent) "  ●" else "",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).clickable { run("checkout", api.gitCheckout(br)) },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(enabled = !busy && !isCurrent, onClick = { run("checkout", api.gitCheckout(br)) }) { Text(S.checkout, fontSize = 11.sp) }
                    IconButton(enabled = !busy && !isCurrent, onClick = { run("deleteBranch", api.gitDeleteBranchForce(br.removePrefix("origin/"))) }) {
                        Text("🗑", fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${S.stash} · ${o.stashes.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !busy, onClick = { run("stashPush", api.gitStashPush(null)) }) { Text(S.stashChanges, fontSize = 11.sp) }
            }
            OutlinedTextField(value = filterStash, onValueChange = { filterStash = it }, label = { Text(S.searchStashes) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (o.stashes.isEmpty()) Text(S.noStashes, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            o.stashes.filter { it.message.contains(filterStash, ignoreCase = true) }.take(8).forEach { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.message, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(st.ref, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !busy, onClick = { run("stashApply", api.gitStashRef(st.ref)) }) { Text("apply", fontSize = 11.sp) }
                    TextButton(enabled = !busy, onClick = { run("stashPop", api.gitStashRef(st.ref)) }) { Text("pop", fontSize = 11.sp) }
                    IconButton(enabled = !busy, onClick = { run("stashDrop", api.gitStashRef(st.ref)) }) { Text("🗑", fontSize = 12.sp) }
                }
            }

            o.source?.let { src ->
                Spacer(Modifier.height(8.dp))
                Text("${S.fetchFromSourceRepo} · ${src.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(S.fetchFromSourceDesc, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                src.branches.take(6).forEach { br ->
                    TextButton(enabled = !busy, onClick = { run("fetchFromSource", api.gitFetchFromSource(br)) }) { Text(br, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        if (ov == null && err == null) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    if (showNewBranch) {
        var nb by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewBranch = false },
            title = { Text(S.newBranchAction.trimEnd('…')) },
            text = { OutlinedTextField(value = nb, onValueChange = { nb = it }, label = { Text(S.newBranchName) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = !busy && nb.isNotBlank(), onClick = {
                    showNewBranch = false
                    run("newBranch", api.gitNewBranch(nb.trim()))
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showNewBranch = false }) { Text("Cancel") } },
        )
    }
}
