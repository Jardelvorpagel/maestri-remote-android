package app.maestri.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Amber
import app.maestri.ui.NotesStrings as S
import app.maestri.ui.Surface0
import app.maestri.ui.Surface1
import app.maestri.ui.Surface2
import app.maestri.wire.CanvasPoint
import app.maestri.wire.FicharioState
import app.maestri.wire.NotesApi
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fichário components — the notes lane's share of canvas cards (P1b).
 *
 * The canvas lane owns CanvasScreen.kt; THIS file ships the pieces they wire:
 *  · [FicharioTabRailInline] — the tab rail for a `noteStack` card: tap a tab = PUT
 *    …/stack/front (iOS "Shows this page") + opens the page; hold-then-drag = SILENT reorder
 *    via PUT …/stack/order (a permutation of the CURRENT tabs; failure keeps the snapshot
 *    order and toasts "Maestri couldn't reorder those pages."); the overflow tab opens the sheet.
 *  · [FicharioPagesSheet] — the overflow sheet: "Search pages", "%d pages", the
 *    "Front page" badge, per-page "Rename Page" / "Remove from Fichário" (landing), "New Page".
 *  · [FicharioActions] — the node-menu verbs ("Add to Fichário" / "Remove from Fichário" /
 *    "New Page" / file a selection into a new fichário) with exact iOS failure toasts, the
 *    same-floor guard, and the `noteStackPages` capability gate.
 *
 * Snapshot truth: the stack node payload carries {name, frontNoteId, front, tabs:[…]} —
 * parsed by [FicharioState.parse]; the rail renders FROM THE SNAPSHOT; the deck never guesses.
 */

// --------------------------------------------------------------------------- rail (canvas card)

@Composable
fun FicharioTabRailInline(
    session: WireSession,
    wsId: String,
    stack: FicharioState,
    onOpenNote: (noteId: String) -> Unit,
) {
    val api = remember(session) { NotesApi(session) }
    val toastContext = LocalContext.current
    val actions = remember(session, wsId) {
        FicharioActions(api, wsId) { message: String ->
            android.widget.Toast.makeText(toastContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    // The rail's order is the snapshot's order; a local drag permutes VISUALLY, then the
    // silent PUT stack/order confirms (the next snapshot re-renders from the host echo).
    val orderedTabs = remember(stack) { mutableStateOf(stack.tabs) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface0.copy(alpha = 0.55f), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .padding(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            val visible = orderedTabs.value.take(MAX_VISIBLE_TABS)
            visible.forEachIndexed { index, tab ->
                RailTab(
                    tab = tab,
                    isFront = tab.nodeId == stack.frontNoteId,
                    onTabTap = {
                        scope.launch {
                            actions.makeFront(stack.stackNodeId, tab.nodeId)
                            onOpenNote(tab.nodeId)   // tap a tab shows this page, front first
                        }
                    },
                    onDragEnd = { shift ->
                        val target = (index + shift).coerceIn(0, visible.lastIndex)
                        if (target != index) {
                            val list = orderedTabs.value.toMutableList()
                            val moved = list.removeAt(index)
                            list.add(target, moved)
                            orderedTabs.value = list
                            scope.launch { actions.reorder(stack.stackNodeId, list.map { it.nodeId }) }
                        }
                    },
                )
            }
            // overflow tab → the pages sheet
            RailTab(
                tab = FicharioState.FicharioTab("", "⋯", null),
                isFront = false,
                overflow = true,
                onTabTap = { showSheet = true },
                onDragEnd = {},
            )
        }
    }
    if (showSheet) {
        FicharioPagesSheet(
            stack = stack,
            onDismiss = { showSheet = false },
            makeFront = { noteId -> scope.launch { actions.makeFront(stack.stackNodeId, noteId) } },
            newPage = { afterNoteId -> scope.launch { actions.newPage(stack.stackNodeId, afterNoteId) } },
            removePage = { noteId -> scope.launch {
                // landing: just inside the card's edge — the host drops the page at {x,y}
                actions.removeFromFichario(stack.stackNodeId, noteId, landingX = 40.0, landingY = 40.0)
            } },
            renamePage = { noteId, name -> scope.launch { actions.renamePage(noteId, name) } },
        )
    }
}

/** One rail tab: fixed width, 1-line title; front is highlighted (marks the page facing front). */
@Composable
private fun RailTab(
    tab: FicharioState.FicharioTab,
    isFront: Boolean,
    onTabTap: () -> Unit,
    onDragEnd: (dragShift: Int) -> Unit,
    overflow: Boolean = false,
) {
    var draggingOffsetX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val accent = swatchColor(tab.color)
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    Column(
        Modifier
            .width(RAIL_TAB_WIDTH_DP.dp)
            .padding(horizontal = 2.dp),
    ) {
        Box(
            Modifier
                .background(
                    if (isFront) accent.copy(alpha = 0.28f) else Surface1,
                    shape,
                )
                .border(1.dp, if (isFront) accent.copy(alpha = 0.7f) else Surface2, shape)
                .clickable { onTabTap() }
                .pointerInput(overflow) {
                    if (overflow) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true; draggingOffsetX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            draggingOffsetX += dragAmount.x
                        },
                        onDragEnd = {
                            dragging = false
                            onDragEnd((draggingOffsetX / RAIL_TAB_WIDTH_DP).toInt())
                        },
                        onDragCancel = { dragging = false; draggingOffsetX = 0f },
                    )
                }
                .padding(vertical = 5.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tab.title.ifBlank { if (overflow) OVERFLOW_GLYPH else "Page" },
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isFront) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val RAIL_TAB_WIDTH_DP = 64f
private const val MAX_VISIBLE_TABS = 4
private const val OVERFLOW_GLYPH = "⋯"

// ------------------------------------------------------------------------------ pages sheet

/** The overflow sheet: search + count + front badge + rename/remove/new — iOS NoteStackPagesSheet parity. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FicharioPagesSheet(
    stack: FicharioState,
    onDismiss: () -> Unit,
    makeFront: (noteId: String) -> Unit,
    newPage: (afterNoteId: String?) -> Unit,
    removePage: (noteId: String) -> Unit,
    renamePage: (noteId: String, name: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FicharioState.FicharioTab?>(null) }
    val visible = stack.tabs.filter { it.title.contains(query, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S.FICHARIO, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(S.pages(stack.tabs.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(S.SEARCH_PAGES) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(S.NO_PAGES_FOUND, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.height(240.dp)) {
                    itemsIndexed(visible, key = { _, tab -> tab.nodeId.ifBlank { "overflow" } }) { _, tab ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { makeFront(tab.nodeId) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).background(swatchColor(tab.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tab.title.ifBlank { "Page" },
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (tab.nodeId == stack.frontNoteId) {
                                Box(
                                    Modifier
                                        .background(Amber.copy(alpha = 0.2f), RoundedCornerShape(50))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(S.FRONT_PAGE, fontSize = 10.sp, color = Amber)
                                }
                            }
                            TextButton(onClick = { renameTarget = tab }) { Text(S.RENAME_PAGE, fontSize = 12.sp) }
                            TextButton(onClick = { removePage(tab.nodeId) }) { Text(S.REMOVE_FROM_FICHARIO, fontSize = 12.sp) }
                        }
                    }
                }
            }
            TextButton(onClick = { newPage(stack.frontNoteId) }) { Text(S.NEW_PAGE) }
        }
    }
    renameTarget?.let { target ->
        var name by remember(target) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(S.RENAME_PAGE) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = null
                    if (name.isNotBlank()) renamePage(target.nodeId, name.trim())
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
            containerColor = Surface1,
        )
    }
}

// ------------------------------------------------------------------------- node-menu actions

/**
 * Node-menu verbs for the canvas lane's context menu ("Add to Fichário"/"Remove from
 * Fichário"/"New Page"/file-a-selection). Error → toast mapping follows iOS 1:1; stale
 * guards: 409/404 on member ops → "This fichário changed on your computer. Try that again."
 * (page-remove 409 = the page-left guard instead); the capability gate covers old hosts.
 */
class FicharioActions(
    private val api: NotesApi,
    private val wsId: String,
    private val toast: (String) -> Unit,
) {
    /** Capability gate: old host → iOS "Your computer can't take fichário changes yet.". */
    fun capabilityGate(session: WireSession): Boolean {
        val ok = session.serverInfo?.has("noteStackPages") == true &&
            session.serverInfo?.has("noteStackWrites") == true
        if (!ok) toast(S.YOUR_COMPUTER_CANT_TAKE_FICHARIO_CHANGES_YET)
        return ok
    }

    /** "Add to Fichário" — file ONE loose note into an existing fichário (noteStackFiling). */
    suspend fun fileNote(stackId: String, noteId: String, noteFloorId: String?, stackFloorId: String?) {
        if (noteFloorId != null && stackFloorId != null && noteFloorId != stackFloorId) {
            toast(S.A_NOTE_CAN_ONLY_BE_FILED_ON_ITS_OWN_FLOOR)
            return
        }
        try {
            api.stackFilePage(wsId, stackId, noteId)
        } catch (e: Exception) {
            toastWhenStaleElse(e, S.MAESTRI_COULDNT_FILE_THAT_NOTE)
        }
    }

    /** File a whole selection into a NEW fichário (POST note-stacks); iOS's 2-note constraint is client-side. */
    suspend fun createFromSelection(nodeIds: List<String>, floorId: String?) {
        if (nodeIds.size < 2) {
            toast(S.A_FICHARIO_NEEDS_TWO_NOTES_ON_THIS_FLOOR)
            return
        }
        try {
            api.createStack(wsId, nodeIds, floorId)
        } catch (e: Exception) {
            toast(S.MAESTRI_COULDNT_PLACE_THOSE_NOTES)
        }
    }

    /** Tap a tab / sheet row → that page faces front. */
    suspend fun makeFront(stackId: String, frontNoteId: String) {
        try {
            api.stackFront(wsId, stackId, frontNoteId)
        } catch (e: Exception) {
            toastWhenStaleElse(e, S.MAESTRI_COULDNT_TURN_THAT_FICHARIO)
        }
    }

    /** Drag end → silent order write; failures toast, never block the canvas. */
    suspend fun reorder(stackId: String, memberNodeIds: List<String>) {
        try {
            api.stackOrder(wsId, stackId, memberNodeIds)
        } catch (e: Exception) {
            toastWhenStaleElse(e, S.MAESTRI_COULDNT_REORDER_THOSE_PAGES)
        }
    }

    /** "New Page" — POST …/stack/pages (afterNoteId optional; the front tab by default). */
    suspend fun newPage(stackId: String, afterNoteId: String? = null) {
        try {
            api.stackNewPage(wsId, stackId, afterNoteId)
        } catch (e: Exception) {
            toast(S.MAESTRI_COULDNT_ADD_A_PAGE)
        }
    }

    /** "Remove from Fichário" — the page RETURNS to the canvas; {x,y} = optional landing point. */
    suspend fun removeFromFichario(stackId: String, noteId: String, landingX: Double? = null, landingY: Double? = null) {
        val landing = if (landingX != null && landingY != null) CanvasPoint(landingX, landingY) else null
        try {
            api.stackRemovePage(wsId, stackId, noteId, landing)
        } catch (e: Exception) {
            // The host's 409 "Page is not in this fichário" IS the page-left guard.
            val stale = e is NotesApi.NotesApiException && e.status == 409
            toast(if (stale) S.THAT_PAGE_ISNT_IN_THIS_FICHARIO_ANY_MORE else S.MAESTRI_COULDNT_TAKE_THAT_PAGE_OUT)
        }
    }

    /** "Rename Page" (from the sheet) — renames ride the page note node (POST …/nodes/{id}/rename). */
    suspend fun renamePage(noteId: String, name: String) {
        try {
            api.renameNote(wsId, noteId, name)
        } catch (e: Exception) {
            toast(S.MAESTRI_COULDNT_RENAME)
        }
    }

    private fun toastWhenStaleElse(e: Exception, fallback: String) {
        val stale = e is NotesApi.NotesApiException && (e.status == 409 || e.status == 404)
        toast(if (stale) S.THIS_FICHARIO_CHANGED_ON_YOUR_COMPUTER else fallback)
    }
}