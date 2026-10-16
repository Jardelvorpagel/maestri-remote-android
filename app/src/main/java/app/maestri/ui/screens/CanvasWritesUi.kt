package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.CanvasStrings
import app.maestri.wire.CanvasCableTie
import app.maestri.wire.CanvasConnection
import app.maestri.wire.CanvasNode
import app.maestri.wire.CanvasOptimism
import app.maestri.wire.CanvasWrites
import app.maestri.wire.NodeFrameOverride
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.abs
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.controlShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.vector.ImageVector
import app.maestri.ui.Style.IconOnly

// ====================================================================================== modes

/** What the canvas is doing right now; `Placing`/`Connecting`/`TieLinking` consume the next tap. */
sealed interface CanvasMode {
    data object Idle : CanvasMode
    data class Placing(val kind: String) : CanvasMode
    data class Connecting(val fromNodeId: String) : CanvasMode
    data class TieLinking(val firstConnectionId: String) : CanvasMode
}

/** A locally drawn stroke that has not been answered by the host yet. */
data class OptimisticStroke(
    val id: String,
    val points: List<Offset>,
    val color: String,
    val lineWidth: Float,
)

/** What a colour picker is being opened for. */
sealed interface ColorTarget {
    data class Node(val node: CanvasNode) : ColorTarget
    data class Group(val groupId: String) : ColorTarget
}

// ================================================================================= controller

/**
 * Every canvas write the client can make, in one place: state the UI binds to, the route each
 * action takes, and the iOS wording for whatever goes wrong.
 *
 * Failure handling follows the recovered schemas rather than a retry loop: a write that fails is
 * surfaced (never silently swallowed, never re-sent — an ambiguous mutation must not be repeated),
 * and the optimistic overlay is rolled back so the canvas keeps showing the host's truth.
 */
class CanvasWriteController(
    private val session: WireSession,
    private val scope: CoroutineScope,
    private val wsId: String,
) {
    val optimism: CanvasOptimism = session.canvasOptimism

    var mode: CanvasMode by mutableStateOf(CanvasMode.Idle)
        private set

    /** Latest transient message, in iOS wording. [bannerIsError] picks the colour. */
    var banner: String? by mutableStateOf(null)
        private set
    var bannerIsError: Boolean by mutableStateOf(false)
        private set

    /** Selected node ids (marquee or tap); drives the selection bar and batch actions. */
    val selection: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    var menuFor: CanvasNode? by mutableStateOf(null)
    var renameFor: CanvasNode? by mutableStateOf(null)
    var renameText: String by mutableStateOf("")
    var confirmIds: List<String>? by mutableStateOf(null)
    var groupDialog: Boolean by mutableStateOf(false)
    var groupName: String by mutableStateOf("")
    var groupActionsFor: String? by mutableStateOf(null)
    var colorFor: ColorTarget? by mutableStateOf(null)
    var textFor: CanvasNode? by mutableStateOf(null)
    var textDraft: String by mutableStateOf("")
    var textSize: Int by mutableStateOf(14)
    var textWeight: String by mutableStateOf("regular")
    var textFamily: String by mutableStateOf("sans")
    var textColor: String by mutableStateOf("")
    var busy: Boolean by mutableStateOf(false)
        private set

    /** Strokes drawn here and not yet confirmed by a snapshot. */
    val strokes = mutableStateListOf<OptimisticStroke>()

    /** Drawing ids this session created, newest last: "erase the last drawing you made". */
    private val myDrawings = mutableStateListOf<String>()

    private val strings = CanvasStrings

    // ---------------------------------------------------------------------------- plumbing

    fun enterMode(m: CanvasMode) {
        mode = m
    }

    fun info(msg: String) {
        banner = msg
        bannerIsError = false
    }

    fun error(msg: String) {
        banner = msg
        bannerIsError = true
    }

    fun dismissBanner() {
        banner = null
    }

    /**
     * Runs one write. Failures become [error] with the iOS failure sentence for that action; the
     * exception text is appended so a host refusal is legible (logcat carries it too).
     */
    private fun write(label: String, onFail: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("QACanvas", "$label failed: ${e.message}")
                error(onFail)
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------------ node actions

    fun selectNode(node: CanvasNode, canvas: app.maestri.wire.CanvasSnapshot?) {
        // Tapping any member of a group selects the whole group (group = set of nodes).
        val gid = node.groupId
        if (gid != null) {
            canvas?.nodes?.filter { it.groupId == gid }?.forEach { selection[it.id] = true }
            selection[node.id] = true
        } else {
            selection[node.id] = true
        }
    }

    fun clearSelection() = selection.clear()

    fun moveNode(nodeId: String, x: Float, y: Float, width: Int, height: Int) {
        // Optimistic immediately (this is what the finger is dragging), then one frame write.
        optimism.frames[nodeId] = NodeFrameOverride(x, y, width, height)
        val mutationId = CanvasWrites.newMutationId("move")
        optimism.beginMove(nodeId, mutationId, NodeFrameOverride(x, y, width, height))
        write("move", strings.maestriCouldnTMoveThatNode) {
            session.canvasWrite(
                CanvasWrites.nodePath(wsId, nodeId, "frame"),
                CanvasWrites.frame(x, y, width, height, bringToFront = true, mutationId = mutationId),
            )
        }
    }

    /** Live position while the finger is down (no write yet). */
    fun previewMove(nodeId: String, x: Float, y: Float, width: Int, height: Int) {
        optimism.frames[nodeId] = NodeFrameOverride(x, y, width, height)
    }

    fun rename(node: CanvasNode, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return                       // empty keeps the previous name
        menuFor = null
        write("rename", strings.maestriCouldnTRenameThat) {
            session.canvasWrite(CanvasWrites.nodePath(wsId, node.id, "rename"), CanvasWrites.rename(trimmed))
        }
    }

    fun toggleLock(node: CanvasNode) {
        menuFor = null
        write("lock", strings.couldntChangeLock) {
            session.canvasWrite(CanvasWrites.nodePath(wsId, node.id, "lock"), CanvasWrites.lock(!node.isNodeLocked))
        }
    }

    fun duplicate(node: CanvasNode) {
        menuFor = null
        write("duplicate", "Maestri couldn't duplicate that.") {
            session.canvasWrite(CanvasWrites.nodePath(wsId, node.id, "duplicate"))
        }
    }

    fun askDelete(ids: List<String>) {
        menuFor = null
        confirmIds = ids
    }

    fun confirmDelete(ids: List<String>, wasSelection: Boolean) {
        confirmIds = null
        if (ids.isEmpty()) return
        val mutationId = CanvasWrites.newMutationId("del")
        optimism.beginRemove(ids, mutationId)
        write("delete", strings.maestriCouldnTDeleteThoseNodes) {
            try {
                if (ids.size == 1) {
                    session.canvasWrite(CanvasWrites.deleteNodePath(wsId, ids[0]), method = "DELETE")
                } else {
                    // Batch rule: ONE call for a multi-selection.
                    session.canvasWrite(CanvasWrites.nodesPath(wsId, "delete"), CanvasWrites.batchDelete(ids, mutationId))
                }
            } catch (e: Exception) {
                optimism.settleFailed(mutationId, ids)
                throw e
            }
        }
        if (wasSelection) selection.clear()
    }

    fun connect(fromNodeId: String, toNodeId: String) {
        mode = CanvasMode.Idle
        write("connect", strings.maestriCouldnTConnectThoseNodes) {
            try {
                session.canvasWrite(
                    "api/workspaces/$wsId/connections",
                    CanvasWrites.connection(fromNodeId, toNodeId, CanvasWrites.newMutationId("conn")),
                )
                info(strings.connectingFromHere)
            } catch (e: Exception) {
                // The host refuses some pairs outright (400 "These nodes can't be connected"); iOS names
                // the pair rather than the request, then leaves connect mode, which is already done above.
                error(strings.thisNodeCanTTakeThatConnection)
            }
        }
    }

    fun disconnect(fromNodeId: String, toNodeId: String) {
        menuFor = null
        write("disconnect", strings.maestriCouldnTDisconnectThoseNodes) {
            session.canvasWrite(
                "api/workspaces/$wsId/connections",
                CanvasWrites.connection(fromNodeId, toNodeId),
                method = "DELETE",
            )
        }
    }

    /** "Remove All Connections": one DELETE per rope the node currently has. */
    fun disconnectAll(nodeId: String, connections: List<CanvasConnection>) {
        menuFor = null
        val mine = connections.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
        if (mine.isEmpty()) {
            info(strings.thisConnectionIsNoLongerOnTheCanvas)
            return
        }
        write("disconnectAll", strings.maestriCouldnTDisconnectThoseNodes) {
            mine.forEach { c ->
                session.canvasWrite(
                    "api/workspaces/$wsId/connections",
                    CanvasWrites.connection(c.fromNodeId, c.toNodeId),
                    method = "DELETE",
                )
            }
        }
    }

    fun createNode(kind: String, x: Float, y: Float) {
        mode = CanvasMode.Idle
        write("create", "Maestri couldn't add that.") {
            val body = CanvasWrites.createNode(kind, x, y, mutationId = CanvasWrites.newMutationId("new"))
            val res = session.canvasWrite(CanvasWrites.createNodePath(wsId), body)
            android.util.Log.d("QACanvas", "create $kind -> ${res.optString("nodeId")} at $x,$y")
        }
    }

    // ------------------------------------------------------------------------------ groups

    fun groupSelected(nodeIds: List<String>) {
        if (nodeIds.size < 2) {
            // iOS refuses a one-node group with this exact sentence.
            error(strings.aGroupNeedsAtLeastTwoNodesOnThisFloor)
            return
        }
        groupName = ""
        groupDialog = true
    }

    fun confirmGroup(nodeIds: List<String>, name: String) {
        groupDialog = false
        val mutationId = CanvasWrites.newMutationId("grp")
        write("group", strings.maestriCouldnTGroupThoseNodes) {
            session.canvasWrite(
                CanvasWrites.groupsPath(wsId),
                CanvasWrites.group(nodeIds, name.trim().ifBlank { null }, mutationId),
            )
        }
    }

    fun groupColor(groupId: String, colorName: String) {
        colorFor = null
        val hex = CanvasWrites.hexFor(colorName)
        if (hex == null) {
            error(strings.maestriCouldnTRecolorThatGroup)
            return
        }
        write("groupColor", strings.maestriCouldnTRecolorThatGroup) {
            session.canvasWrite(
                CanvasWrites.groupPath(wsId, groupId, "color"),
                CanvasWrites.groupColor(hex),
                method = "PUT",
            )
        }
    }

    /** The group route's clear form is a real JSON `null`, which is the only one the host accepts. */
    fun clearGroupColor(groupId: String) {
        colorFor = null
        write("groupColorClear", strings.maestriCouldnTRecolorThatGroup) {
            session.canvasWrite(
                CanvasWrites.groupPath(wsId, groupId, "color"),
                CanvasWrites.groupColor(null),
                method = "PUT",
            )
        }
    }

    fun groupRename(groupId: String, name: String) {
        groupActionsFor = null
        if (name.isBlank()) return
        write("groupRename", strings.maestriCouldnTRenameThatGroup) {
            session.canvasWrite(CanvasWrites.groupPath(wsId, groupId, "rename"), CanvasWrites.rename(name.trim()))
        }
    }

    fun ungroup(nodeIds: List<String>) {
        groupActionsFor = null
        write("ungroup", strings.maestriCouldnTUngroupThoseNodes) {
            session.canvasWrite(CanvasWrites.ungroupPath(wsId), CanvasWrites.ungroup(nodeIds))
        }
        selection.clear()
    }

    // ------------------------------------------------------------------------------- text

    fun openTextEditor(node: CanvasNode) {
        textFor = node
        val block = node.text
        textDraft = block?.text ?: ""
        textSize = block?.fontSize ?: 24
        textWeight = block?.fontWeight ?: "regular"
        textFamily = block?.fontFamily ?: "sans"
        textColor = CanvasWrites.nameForHex(block?.color) ?: ""
    }

    fun saveText() {
        val node = textFor ?: return
        textFor = null
        val body = CanvasWrites.text(
            text = textDraft,
            fontSize = textSize.coerceIn(8, 200),      // host clamps; stay in range by design
            fontWeight = textWeight,
            fontFamily = textFamily,
            color = CanvasWrites.hexFor(textColor),
            isMonospaced = node.text?.isMonospaced,
            mutationId = CanvasWrites.newMutationId("txt"),
        )
        write("text", strings.maestriCouldnTChangeThatText) {
            session.canvasWrite(CanvasWrites.nodePath(wsId, node.id, "text"), body)
        }
    }

    /**
     * Recolour one thing by the contract that kind accepts: a text block takes hex on its own text
     * route (resending the block's styling so nothing is lost), a note takes the vocabulary name,
     * and a group takes hex. The three hosts contracts are deliberately not interchangeable.
     */
    fun setNodeColor(node: CanvasNode, colorName: String) {
        colorFor = null
        if (node.kind == "text") {
            val hex = CanvasWrites.hexFor(colorName)
            if (hex == null) {
                error(strings.couldntChangeColor)
                return
            }
            val block = node.text
            val body = CanvasWrites.text(
                text = block?.text ?: node.title,
                fontSize = block?.fontSize,
                fontWeight = block?.fontWeight,
                fontFamily = block?.fontFamily,
                color = hex,
                isMonospaced = block?.isMonospaced,
                mutationId = CanvasWrites.newMutationId("txtc"),
            )
            write("textColor", strings.maestriCouldnTChangeThatText) {
                session.canvasWrite(CanvasWrites.nodePath(wsId, node.id, "text"), body)
            }
            return
        }
        write("nodeColor", strings.couldntChangeColor) {
            session.canvasWrite(
                CanvasWrites.nodePath(wsId, node.id, "note/color"),
                CanvasWrites.noteColor(colorName),
                method = "PUT",
            )
        }
    }

    // -------------------------------------------------------------------------------- ties

    fun tieRopes(connectionA: String, connectionB: String, x: Float, y: Float) {
        mode = CanvasMode.Idle
        val id = CanvasWrites.newTieId()
        write("tie", strings.maestriCouldnTTieThoseRopes) {
            session.canvasWrite(
                CanvasWrites.tiesPath(wsId),
                CanvasWrites.tie(id, x, y, listOf(connectionA, connectionB), CanvasWrites.newMutationId("tie")),
            )
        }
    }

    fun moveTie(tieId: String, x: Float, y: Float) {
        write("tieMove", strings.maestriCouldnTMoveThatTie) {
            session.canvasWrite(CanvasWrites.tiePath(wsId, tieId, "position"), CanvasWrites.position(x, y))
        }
    }

    fun cutTie(tieId: String) {
        menuFor = null
        write("tieCut", strings.maestriCouldnTCutThatTie) {
            session.canvasWrite(CanvasWrites.tiePath(wsId, tieId), method = "DELETE")
        }
    }

    // ---------------------------------------------------------------------------- drawings

    /** Commit a finished stroke: the local stroke is replaced by the host's snapshot on resync. */
    fun submitStroke(points: List<Offset>, color: String, lineWidth: Float) {
        if (points.size < 2) return
        val id = CanvasWrites.newDrawingId()
        val local = OptimisticStroke(id, points, color, lineWidth)
        strokes.add(local)
        myDrawings.add(id)
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        val localPoints = points.map { it.x - minX to it.y - minY }
        write("draw", strings.maestriCouldnTAddThatDrawing) {
            session.canvasWrite(
                CanvasWrites.drawingsPath(wsId),
                CanvasWrites.drawing(
                    id = id,
                    tool = "pen",
                    points = localPoints,
                    originX = minX,
                    originY = minY,
                    width = (maxX - minX).coerceAtLeast(1f),
                    height = (maxY - minY).coerceAtLeast(1f),
                    color = color,
                    lineWidth = lineWidth,
                    mutationId = CanvasWrites.newMutationId("draw"),
                ),
            )
        }
    }

    fun eraseLastDrawing() {
        val id = myDrawings.lastOrNull()
        if (id == null) {
            info(strings.erasesTheLastDrawingYouMade)
            return
        }
        myDrawings.remove(id)
        strokes.removeAll { it.id == id }
        write("erase", strings.maestriCouldnTEraseThatDrawing) {
            session.canvasWrite(CanvasWrites.drawingPath(wsId, id), method = "DELETE")
        }
    }

    /** Drop local strokes the host now reports (their ids came back in the snapshot). */
    fun reconcileDrawings(snapshotIds: Set<String>) {
        if (strokes.isEmpty()) return
        strokes.removeAll { it.id in snapshotIds || myDrawings.contains(it.id) && it.id in snapshotIds }
    }
}

// ======================================================================== hit testing

/**
 * Hit-testing in CANVAS space, over the snapshot plus whatever the optimistic overlay is showing.
 * `frameOf` is consulted first so a node the finger is dragging stays under it.
 */
class CanvasHitIndex(
    private val nodes: List<CanvasNode>,
    private val connections: List<CanvasConnection>,
    private val ties: List<CanvasCableTie>,
    private val frameOf: (String) -> Rect?,
) {
    private val nodesById = nodes.associateBy { it.id }

    /** Topmost node containing [p] (highest zIndex wins), if any. */
    fun nodeAt(p: Offset): CanvasNode? = nodes
        .sortedByDescending { it.zIndex }
        .firstOrNull { (frameOf(it.id) ?: it.rect()).contains(p) }

    fun tieAt(p: Offset, radius: Float): CanvasCableTie? = ties.firstOrNull { t ->
        val dx = (t.position.x - p.x).toFloat()
        val dy = (t.position.y - p.y).toFloat()
        dx * dx + dy * dy <= radius * radius
    }

    fun tieWith(id: String): CanvasCableTie? = ties.firstOrNull { it.id == id }

    /**
     * Nearest rope within [tol] canvas points, used by tie mode. A circuit-routed rope carries NO
     * points in the snapshot, so the centre-to-centre chord stands in for it (as the renderer does).
     */
    fun ropeAt(p: Offset, tol: Float): CanvasConnection? {
        var best: CanvasConnection? = null
        var bestD = tol
        connections.forEach { c ->
            val pts = c.points.ifEmpty {
                val from = nodesById[c.fromNodeId]?.rect()
                val to = nodesById[c.toNodeId]?.rect()
                if (from == null || to == null) emptyList()
                else listOf(
                    app.maestri.wire.CanvasPoint(from.center.x.toDouble(), from.center.y.toDouble()),
                    app.maestri.wire.CanvasPoint(to.center.x.toDouble(), to.center.y.toDouble()),
                )
            }
            for (i in 0 until (pts.size - 1)) {
                val a = Offset(pts[i].x.toFloat(), pts[i].y.toFloat())
                val b = Offset(pts[i + 1].x.toFloat(), pts[i + 1].y.toFloat())
                val d = distanceToSegment(p, a, b)
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
        }
        return best
    }
}

private fun CanvasNode.rect(): Rect =
    Rect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat())

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 <= 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}

// ====================================================================== gesture machine

/** Movement beyond this while arming means the user is panning, not holding. */
private const val HOLD_SLOP_DP = 40f

/**
 * One gesture machine for the whole canvas, because the gestures overlap:
 *
 *  - hold then drag on a node               → move that node;
 *  - hold then drag on empty canvas         → marquee select;
 *  - hold without moving                    → context menu / selection menu;
 *  - movement past touch slop before that   → pan (two fingers → pan+pinch);
 *  - quick tap                              → select (terminal: open; text: edit);
 *  - pen mode, one finger                   → draw (a second finger cancels the stroke).
 *
 * The hold deadline is the platform long-press timeout, but movement is judged against EVENT TIMES
 * rather than delivery times: a busied main thread (or a batched injection) can hand the movement of
 * a fast drag over after the deadline, and judging by delivery would turn every such pan into a hold.
 * A pan always keeps the full delta from the finger-down point, so a flick never pans by zero.
 */
private enum class Phase { Pending, Pan, Tap, Hold }

internal suspend fun PointerInputScope.detectCanvasInteractions(
    key: Any?,
    penActive: Boolean,
    hitIndex: () -> CanvasHitIndex,
    toCanvas: (stageDp: Offset) -> Offset,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onTap: (canvas: Offset) -> Unit,
    onLongPress: (canvas: Offset, node: CanvasNode?) -> Unit,
    onNodeDrag: (nodeId: String, x: Float, y: Float) -> Unit,
    onNodeDragEnd: (nodeId: String) -> Unit,
    onLockedRefusal: (node: CanvasNode) -> Unit = {},
    onMarquee: (Rect?) -> Unit,
    onMarqueeEnd: (Rect) -> Unit,
    onStrokeCancel: () -> Unit,
    onStroke: (List<Offset>) -> Unit,
    tieRadius: Float = 40f,
    onTieMove: (tieId: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onTieMoveEnd: (tieId: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
) {
    awaitEachGesture {
        val density = this.density
        fun canvasAt(px: Offset): Offset = toCanvas(Offset(px.x / density, px.y / density))

        val down = awaitFirstDown(requireUnconsumed = false)
        val startScreen = down.position
        val hit = hitIndex()
        val startCanvas = canvasAt(startScreen)

        // ---------------------------------------------------------------- pen: straight to draw
        if (penActive) {
            val pts = mutableListOf(startCanvas)
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    // Two fingers never draw: hand the gesture back to pan/zoom.
                    cancelled = true
                    onStrokeCancel()
                    return@awaitEachGesture
                }
                val p = canvasAt(pressed.first().position)
                pts.add(p)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (!cancelled) onStroke(pts)
            return@awaitEachGesture
        }

        // ----------------------------------------------------- phase 1: hold, or pan
        // Movement past touch slop before the deadline is a pan and keeps the FULL delta from the
        // finger-down point; reaching the deadline without movement is a hold. Event times decide,
        // not delivery times (a busied main thread can hand the movement over late).
        val timeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        val deadline = down.uptimeMillis + timeout
        var phase = Phase.Pending
        try {
            withTimeout(timeout) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val live = event.changes.filter { it.pressed }
                    if (live.isEmpty()) {
                        phase = Phase.Tap
                        return@withTimeout Unit
                    }
                    if (live.size >= 2) {
                        phase = Phase.Pan
                        return@withTimeout Unit
                    }
                    if ((live.first().position - startScreen).getDistance() > slop) {
                        phase = Phase.Pan
                        return@withTimeout Unit
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            phase = Phase.Hold
        }
        if (phase == Phase.Tap) {
            if (!penActive) onTap(startCanvas)          // released inside the hold window: a tap
            return@awaitEachGesture
        }
        val anchorCanvas = startCanvas

        onMarquee(null)

        var dragNode: CanvasNode? = null
        var lockedNode: CanvasNode? = null
        var dragTie: CanvasCableTie? = null
        var marqueeStart: Offset? = null

        var panMode = phase == Phase.Pan
        val liveNow = currentEvent.changes.filter { it.pressed }
        var lastCentroid = liveNow.firstOrNull()?.position ?: startScreen
        var lastSpread = spreadOf(liveNow, lastCentroid)
        var lastCount = liveNow.size
        if (panMode) {
            // Apply the delta the hold window swallowed, then keep panning from here.
            val c = centroidOf(liveNow)
            val anchor = if (liveNow.size >= 2) c else startScreen
            onTransform(c, c - anchor, 1f)
            lastCentroid = c
            lastSpread = spreadOf(liveNow, c)
        }
        var moved = 0f
        var menuFired = false
        var lastCanvasPoint = anchorCanvas

        // Hold completed: decide between moving what is under the finger and marquee select.
        var startFrame: Rect? = null
        if (!panMode) {
            val node = hit.nodeAt(anchorCanvas)
            if (node != null && !node.isNodeLocked) {
                dragNode = node
                startFrame = Rect(node.frame.x.toFloat(), node.frame.y.toFloat(), 0f, 0f)
            } else if (node != null) {
                // Locked: a drag is refused out loud, a plain hold opens the options.
                lockedNode = node
            } else {
                // A tie knot the finger is on is dragged; empty canvas starts a marquee.
                val tie = hit.tieAt(anchorCanvas, tieRadius)
                if (tie != null) dragTie = tie else marqueeStart = anchorCanvas
            }
        }
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                // The release carries the last position: apply the tail of the gesture before ending,
                // otherwise a pan (or a node move) loses whatever the finger covered since the final
                // move event — with coalesced delivery that is a visible slice of the gesture.
                val up = event.changes.firstOrNull { it.id == down.id }
                if (up != null) {
                    if (panMode) {
                        if ((up.position - lastCentroid).getDistance() > 0.5f) {
                            onTransform(up.position, up.position - lastCentroid, 1f)
                        }
                    } else {
                        val dragging = dragNode
                        val from = startFrame
                        if (dragging != null && from != null) {
                            val p = canvasAt(up.position)
                            onNodeDrag(
                                dragging.id,
                                from.left + (p.x - anchorCanvas.x),
                                from.top + (p.y - anchorCanvas.y),
                            )
                            lastCanvasPoint = p
                        }
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                break
            }
            if (panMode) {
                val c = centroidOf(pressed)
                if (pressed.size != lastCount) {
                    // A finger joined or left: re-anchor instead of jumping.
                    lastCount = pressed.size
                    lastCentroid = c
                    lastSpread = spreadOf(pressed, c)
                    continue
                }
                val spread = spreadOf(pressed, c)
                val zoom = if (pressed.size >= 2 && lastSpread > 0f) spread / lastSpread else 1f
                onTransform(c, c - lastCentroid, zoom)
                lastCentroid = c
                lastSpread = spread
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                continue
            }
            if (pressed.size >= 2) {
                // Second finger: abandon the hold-gesture and pan/zoom instead.
                panMode = true
                dragNode = null
                lockedNode = null
                dragTie = null
                marqueeStart = null
                onMarquee(null)
                lastCentroid = centroidOf(pressed)
                lastSpread = spreadOf(pressed, lastCentroid)
                lastCount = pressed.size
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                continue
            }
            val change = pressed.first()
            if (change.uptimeMillis < deadline && (change.position - startScreen).getDistance() > slop) {
                // Moved inside the hold window, handed over after it: a pan, not a hold.
                panMode = true
                dragNode = null
                lockedNode = null
                dragTie = null
                marqueeStart = null
                onMarquee(null)
                onTransform(change.position, change.position - startScreen, 1f)
                lastCentroid = change.position
                lastSpread = 0f
                lastCount = 1
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                continue
            }
            val p = canvasAt(change.position)
            lastCanvasPoint = p
            moved += (change.position - lastCentroid).getDistance()
            when {
                dragNode != null -> {
                    val n = dragNode
                    val dx = p.x - anchorCanvas.x
                    val dy = p.y - anchorCanvas.y
                    if (n != null && startFrame != null) {
                        onNodeDrag(n.id, startFrame.left + dx, startFrame.top + dy)
                    }
                }
                dragTie != null -> onTieMove(dragTie.id, p.x, p.y)
                marqueeStart != null -> onMarquee(
                    Rect(
                        minOf(marqueeStart.x, p.x),
                        minOf(marqueeStart.y, p.y),
                        maxOf(marqueeStart.x, p.x),
                        maxOf(marqueeStart.y, p.y),
                    )
                )
            }
            if (!menuFired && moved > slop) menuFired = true
            lastCentroid = change.position
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        when {
            dragNode != null -> {
                if (menuFired) onNodeDragEnd(dragNode.id) else {
                    // Held and released without moving: that is the context menu.
                    onLongPress(anchorCanvas, dragNode)
                }
            }
            dragTie != null -> onTieMoveEnd(dragTie.id, lastCanvasPoint.x, lastCanvasPoint.y)
            lockedNode != null -> {
                if (menuFired) onLockedRefusal(lockedNode) else onLongPress(anchorCanvas, lockedNode)
            }
            marqueeStart != null -> {
                val end = lastCanvasPoint
                val r = Rect(
                    minOf(marqueeStart.x, end.x),
                    minOf(marqueeStart.y, end.y),
                    maxOf(marqueeStart.x, end.x),
                    maxOf(marqueeStart.y, end.y),
                )
                onMarquee(null)
                if (r.width > 2f && r.height > 2f) onMarqueeEnd(r) else onLongPress(anchorCanvas, null)
            }
        }
        if (key == null) Unit
    }
}

// ============================================================================ composables

/** Toolbar chips: create / draw / tie / erase-last, plus the current mode's cancel. */
@Composable
fun CanvasToolbar(
    mode: CanvasMode,
    canTie: Boolean,
    onAdd: () -> Unit,
    onDraw: () -> Unit,
    onTie: () -> Unit,
    onErase: () -> Unit,
    onCancelMode: () -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
    onColor: () -> Unit,
    onDeleteSelected: () -> Unit,
    selectionCount: Int,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("+ Add", mode !is CanvasMode.Placing, onAdd)
        Chip("✎ Draw", true, onDraw)
        Chip("⌘ Tie", canTie, onTie)
        Chip("⌫ Erase", true, onErase)
        if (mode !is CanvasMode.Idle) {
            Chip(
                Icons.Filled.Close,
                when (mode) {
                    is CanvasMode.Placing -> "Cancel"
                    is CanvasMode.Connecting -> CanvasStrings.cancelsConnecting
                    is CanvasMode.TieLinking -> "Cancel tie"
                    else -> "Cancel"
                },
                true,
                onCancelMode,
            )
        }
        if (selectionCount > 0) {
            Spacer(Modifier.width(8.dp))
            Chip("⊞ " + CanvasStrings.group, true, onGroup)
            Chip(CanvasStrings.ungroup, true, onUngroup)
            Chip("◐ " + CanvasStrings.colors.first(), true, onColor)
            Chip("✕ " + CanvasStrings.deleteSelectedNodes.trimEnd('?'), true, onDeleteSelected)
        }
    }
}

@Composable
private fun Chip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Chip(null, label, enabled, onClick)
}

@Composable
private fun Chip(icon: ImageVector?, label: String, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(LocalMaestriColors.current.canvasChromeElevated.copy(alpha = if (enabled) 0.9f else 0.4f), pillShape())
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconOnly(icon, size = 13.dp, tint = fg)
                Spacer(Modifier.width(4.dp))
            }
            Text(label, fontSize = 11.sp, color = fg, maxLines = 1)
        }
    }
}

/** The iOS wording for the mode the canvas is in ("Tap where the note should go", …). */
@Composable
fun CanvasModeBanner(mode: CanvasMode, onCancel: () -> Unit) {
    val text = when (mode) {
        is CanvasMode.Placing -> when (mode.kind) {
            "text" -> CanvasStrings.tapWhereTheTextShouldGo
            "fileTree" -> CanvasStrings.tapWhereTheFolderShouldGo
            else -> CanvasStrings.tapWhereTheNoteShouldGo
        }
        is CanvasMode.Connecting -> CanvasStrings.tapTheNodeToConnectTo
        is CanvasMode.TieLinking -> "Tap the second rope"
        else -> null
    } ?: return
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(LocalMaestriColors.current.warning.copy(alpha = 0.18f), controlShape())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, color = LocalMaestriColors.current.warning, maxLines = 2, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel", fontSize = 12.sp) }
    }
}

/** Transient banner for failures and confirmations (iOS failure sentences). */
@Composable
fun CanvasBanner(message: String?, isError: Boolean, onDismiss: () -> Unit) {
    val msg = message ?: return
    LaunchedEffect(msg) {
        delay(4_000)
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background((if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.18f), controlShape())
            .clickable { onDismiss() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(msg, fontSize = 12.sp, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, maxLines = 3)
    }
}

/** Long-press menu for a node: the iOS option list, minus what this client cannot do yet. */
@Composable
fun NodeContextMenu(
    node: CanvasNode,
    connections: List<CanvasConnection>,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onLock: () -> Unit,
    onDuplicate: () -> Unit,
    onConnect: () -> Unit,
    onDisconnectAll: () -> Unit,
    onDelete: () -> Unit,
    onEditText: (() -> Unit)?,
) {
    val mine = connections.filter { it.fromNodeId == node.id || it.toNodeId == node.id }
    MenuCard(onDismiss) {
        Text(
            node.title.ifBlank { node.kind },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        MenuItem(CanvasStrings.rename, onRename)
        if (onEditText != null) MenuItem("Edit text", onEditText)
        MenuItem(if (node.isNodeLocked) "Unlock" else "Lock", onLock)
        MenuItem("Duplicate", onDuplicate)
        MenuItem(CanvasStrings.connectToAnotherNode, onConnect)
        if (mine.size > 1) MenuItem(CanvasStrings.removeAllConnections, onDisconnectAll)
        MenuItem(CanvasStrings.removeConnection, onDisconnectAll)
        MenuItem("Delete", onDelete)
    }
}

/** Menu for a cable tie: move (drag the knot) or cut it. */
@Composable
fun TieContextMenu(
    onDismiss: () -> Unit,
    onCut: () -> Unit,
) {
    MenuCard(onDismiss) {
        Text("Tie", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MenuItem("Cut the tie", onCut)
    }
}

@Composable
private fun MenuCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(260.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}

/** Small centred dialog used by rename / group name / confirmations. */
@Composable
fun CanvasInputDialog(
    title: String,
    value: String,
    confirmLabel: String = "OK",
    onValue: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
) {
    Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(320.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 15.sp, maxLines = 2)
                body?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(LocalMaestriColors.current.canvasChromeElevated, controlShape())
                        .padding(10.dp)
                ) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        value = value,
                        onValueChange = onValue,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

/** Text-block editor: content plus the styling bar (size, weight, family, colour). */
@Composable
fun TextBlockEditor(
    draft: String,
    size: Int,
    weight: String,
    family: String,
    color: String,
    onDraft: (String) -> Unit,
    onSize: (Int) -> Unit,
    onWeight: (String) -> Unit,
    onFamily: (String) -> Unit,
    onColor: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(460.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Text", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(LocalMaestriColors.current.canvasChromeElevated, controlShape())
                        .padding(10.dp)
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = size.sp,
                        ),
                        modifier = Modifier.fillMaxSize().focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("A−", size > 8, { onSize((size - 2).coerceAtLeast(8)) })
                    Chip("A+", size < 200, { onSize((size + 2).coerceAtMost(200)) })
                    Text("$size", fontSize = 12.sp, modifier = Modifier.width(30.dp))
                    Chip(weight, true) {
                        onWeight(
                            when (weight) {
                                "regular" -> "medium"
                                "medium" -> "bold"
                                else -> "regular"
                            }
                        )
                    }
                    Chip(family, true) { onFamily(if (family == "sans") "serif" else "sans") }
                    Chip(if (color.isBlank()) "colour" else "colour ✓", true) {
                        onColor(CanvasStrings.colors.first())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CanvasStrings.colors.forEach { name ->
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .background(namedColor(name), pillShape())
                                .border(
                                    if (color == name) 2.dp else 1.dp,
                                    if (color == name) MaterialTheme.colorScheme.primary else LocalMaestriColors.current.canvasChromeElevated,
                                    pillShape(),
                                )
                                .clickable { onColor(name) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(name, fontSize = 10.sp, color = Color(0xFF101114))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onSave) { Text("Save") }
                }
            }
        }
    }
}

/** The create menu: the node kinds this client can place. */
@Composable
fun CreateMenu(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    MenuCard(onDismiss) {
        Text(
            "Add to canvas",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        MenuItem("New Note", { onPick("note") })
        MenuItem("New Text", { onPick("text") })
        MenuItem("New Folder", { onPick("fileTree") })
    }
}

/** Confirmation dialog (destructive actions): title + body + Cancel/Confirm, no input field. */
@Composable
fun CanvasConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(340.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 15.sp, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** Cable-tie knots, drawn in canvas space over the ropes they bind. */
@Composable
fun TieLayer(
    ties: List<CanvasCableTie>,
    tiePositions: SnapshotStateMap<String, Offset>,
) {
    if (ties.isEmpty()) return
    val tieAccent = LocalMaestriColors.current.warning
    Canvas(Modifier.fillMaxSize()) {
        val s = density
        ties.forEach { t ->
            val p = tiePositions[t.id] ?: Offset(t.position.x.toFloat(), t.position.y.toFloat())
            drawCircle(tieAccent.copy(alpha = 0.9f), radius = 7f * s, center = Offset(p.x * s, p.y * s))
            drawCircle(Color(0xFF0B0C0E), radius = 3.5f * s, center = Offset(p.x * s, p.y * s))
        }
    }
}

/** Freehand drawings: host drawings (points are local to their origin) plus local strokes. */
@Composable
fun DrawingLayer(
    drawings: List<app.maestri.wire.CanvasDrawing>,
    pending: List<OptimisticStroke>,
) {
    if (drawings.isEmpty() && pending.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val s = density
        fun stroke(pts: List<Offset>, color: String, width: Float) {
            if (pts.size < 2) return
            val path = androidx.compose.ui.graphics.Path()
            pts.forEachIndexed { i, p ->
                val x = p.x * s
                val y = p.y * s
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = parseInkColor(color),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = width * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
        drawings.forEach { d ->
            val ox = d.origin.x.toFloat()
            val oy = d.origin.y.toFloat()
            stroke(d.points.map { Offset(ox + it.x.toFloat(), oy + it.y.toFloat()) }, d.color, d.lineWidth.toFloat())
        }
        pending.forEach { stroke(it.points, it.color, it.lineWidth) }
    }
}

/** Drawing colour: `#RRGGBB` or one of the host's named colours. */
private fun parseInkColor(raw: String): Color {
    if (raw.startsWith("#") && raw.length == 7) {
        runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull()?.let { return it }
    }
    return when (raw) {
        "yellow" -> Color(0xFFF5D142)
        "pink" -> Color(0xFFF08BB4)
        "blue" -> Color(0xFF6FA8F5)
        "green" -> Color(0xFF7BC47F)
        "orange" -> Color(0xFFF0A65E)
        "purple" -> Color(0xFFB48CF2)
        "white" -> Color(0xFFF2F3F5)
        "charcoal" -> Color(0xFF4A4E57)
        "slate" -> Color(0xFF7C8798)
        "midnight" -> Color(0xFF2B3A55)
        else -> Color(0xFFF2F3F5)
    }
}

/** The fixed colour vocabulary as swatches (names verbatim from the iOS binary). */
@Composable
fun ColorPalettePicker(
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(340.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = LocalMaestriColors.current.canvasSurface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                CanvasStrings.colors.chunked(5).forEach { row ->
                    Row(Modifier.padding(bottom = 8.dp)) {
                        row.forEach { name ->
                            Box(
                                Modifier
                                    .padding(end = 8.dp)
                                    .background(namedColor(name), controlShape())
                                    .clickable { onPick(name) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(name, fontSize = 10.sp, color = Color(0xFF101114))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onClear?.let { TextButton(onClick = it) { Text(CanvasStrings.clearColor) } }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** Swatch for a vocabulary name: the same hex this client sends to the host for that name. */
private fun namedColor(name: String): Color =
    CanvasWrites.hexFor(name)?.let { parseInkColor(it) } ?: Color(0xFF8A8F98)

/** Rubber band for a marquee selection, drawn in canvas space inside the camera layer. */
@Composable
fun MarqueeRect(rect: Rect?) {
    val r = rect ?: return
    val marqueeAccent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val s = density
        drawRect(
            color = marqueeAccent.copy(alpha = 0.12f),
            topLeft = Offset(r.left * s, r.top * s),
            size = androidx.compose.ui.geometry.Size(r.width * s, r.height * s),
        )
        drawRect(
            color = marqueeAccent.copy(alpha = 0.9f),
            topLeft = Offset(r.left * s, r.top * s),
            size = androidx.compose.ui.geometry.Size(r.width * s, r.height * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * s),
        )
    }
}

/** Selection ring for a node the marquee or a tap selected. */
@Composable
fun SelectionRing(selected: Boolean, modifier: Modifier = Modifier) {
    if (!selected) return
    Box(
        modifier
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), cardShape())
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), cardShape())
    )
}

/** Helper the screen uses to keep its own `Rect` ↔ canvas conversions honest. */
internal fun rectOf(frame: app.maestri.wire.CanvasRect): Rect =
    Rect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat())

internal fun Rect.intersectsNode(node: CanvasNode): Boolean =
    !(node.frame.x + node.frame.width < left || node.frame.x > right ||
        node.frame.y + node.frame.height < top || node.frame.y > bottom) && abs(0f) >= 0f
