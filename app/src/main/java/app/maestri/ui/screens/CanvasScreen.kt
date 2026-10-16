package app.maestri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.maestri.ui.Amber
import app.maestri.ui.Green
import app.maestri.ui.Surface1
import app.maestri.ui.Surface2
import app.maestri.ui.iconGlyph
import app.maestri.wire.CanvasConnection
import app.maestri.wire.CanvasNode
import app.maestri.wire.CanvasPoint
import app.maestri.store.PairingStore
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.PresencePeer
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Canvas v1 — read-only spatial mirror of the floor's canvas that rides inside every
 * full feed snapshot (capability `canvasMirroring`).
 *
 * Reuses the live FeedSocket: whatever floor the feed mirrors is the canvas we render;
 * new epoch → fresh snapshot → canvas re-renders. Mutation events are attribution-only
 * (no canvas state inside), so the snapshot is the single source of truth.
 *
 * Camera: the whole stage sits inside ONE graphicsLayer carrying scale + translation;
 * gesture updates touch only that layer (deferred reads) so pan/pinch never recomposes
 * node content. Node geometry is static per snapshot (canvas points ≙ dp).
 */
@Composable
fun CanvasScreen(
    session: WireSession,
    wsId: String,
    wsName: String,
    onBack: () -> Unit,
    onOpenTerminal: (terminalId: String, terminalName: String) -> Unit,
) {
    val snapshot by session.feed.snapshot.collectAsState()
    val socketState by session.feed.state.collectAsState()
    val socketError by session.feed.lastError.collectAsState()
    val camera = remember { CameraState() }
    var fitted by remember { mutableStateOf(false) }
    val serverKey = session.serverKey
    val scope = rememberCoroutineScope()
    val peers by session.feed.peers.collectAsState()
    val selfPresence by session.feed.selfPresence.collectAsState()

    // Persisted per-workspace view state, as iOS keeps it in UserDefaults
    // (`remote.camera.<serverId>.<wsId>` = [x,y,zoom] plus the view's followMac flag).
    var following by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun persistCamera() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(CAMERA_SAVE_DEBOUNCE_MS)
            session.store.saveCamera(serverKey, wsId, camera.toView())
        }
    }

    LaunchedEffect(serverKey, wsId) {
        val storedFollow = session.store.snapshotFollowMac(serverKey)
        val stored = session.store.snapshotCamera(serverKey, wsId)
        if (stored != null) {
            camera.set(stored)
            fitted = true            // a restored camera wins over the one-shot fit
        }
        following = storedFollow
        if (storedFollow) fitted = true
        cameraReady = true
    }

    // Defensive seed only; never reset/retarget — the feed socket stays open
    // (owned by the feed screen) and keeps streaming fresh snapshots with the canvas.
    LaunchedEffect(session, wsId) {
        if (session.feed.snapshot.value == null) {
            val p = session.pairing.value ?: return@LaunchedEffect
            runCatching { session.feed.seed(session.feedOnce(p, wsId, null)) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageW = maxWidth.value
        val stageH = maxHeight.value
        val snap = snapshot
        val canvas = snap?.takeIf { it.workspaceId == wsId }?.canvas

        // Fit-to-bounds ONCE at open (stage ready + first snapshot for this workspace);
        // epoch resyncs must NOT jump the camera. The Fit button re-enables.
        LaunchedEffect(cameraReady, fitted, stageW, stageH, snap != null) {
            if (cameraReady && !fitted && !following && snap != null && snap.workspaceId == wsId) {
                contentRect(snap)?.let {
                    camera.fitTo(it, stageW, stageH)
                    persistCamera()      // "Fit" recenters AND persists
                }
                fitted = true
            }
        }

        // Presence: while this canvas is up, report where we are looking — the viewport center in
        // canvas points — so the host can draw our cursor; the same report draws it locally.
        LaunchedEffect(cameraReady, stageW, stageH) {
            if (!cameraReady) return@LaunchedEffect
            snapshotFlow { Triple(camera.scale, camera.offsetX, camera.offsetY) }
                .debounce(PRESENCE_REPORT_MS)
                .collect { (scale, ox, oy) ->
                    session.feed.sendPresencePoint(
                        x = (stageW / 2f - ox) / scale,
                        y = (stageH / 2f - oy) / scale,
                    )
                }
        }
        DisposableEffect(wsId) {
            onDispose { session.feed.sendPresence(null) }   // "nowhere" on the way out
        }

        // Follow-Mac: mirror the host's own camera. The host autosaves origin/zoom, so those
        // ride in the snapshot (not per frame) — following is therefore stepwise, as on iOS.
        LaunchedEffect(following, canvas?.origin, canvas?.zoom) {
            val c = canvas ?: return@LaunchedEffect
            if (!following) return@LaunchedEffect
            camera.mirror(c.origin, c.zoom)
        }

        // Full-screen gesture surface BEHIND the overlay chrome.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(following) {
                    // Pan/pinch are hidden while following the Mac; the chip turns it off.
                    if (following) return@pointerInput
                    detectCanvasGestures { centroidPx, panPx, zoom ->
                        val d = 1f / this.density  // px → dp
                        camera.apply(
                            centroid = Offset(centroidPx.x * d, centroidPx.y * d),
                            pan = Offset(panPx.x * d, panPx.y * d),
                            zoom = zoom,
                        )
                        persistCamera()
                    }
                }
        ) {
            // The stage: one transform layer; children positioned in canvas points (≙ dp).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = camera.scale
                        scaleY = camera.scale
                        translationX = camera.offsetX * density
                        translationY = camera.offsetY * density
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                val nodeIndex = remember(canvas) { nodesById(canvas) }
                ConnectionsLayer(canvas = canvas, nodesById = nodeIndex)
                canvas?.nodes?.forEach { node ->
                    CanvasNodeCard(node, snap, onOpenTerminal)
                }
                CursorsLayer(self = selfPresence, peers = peers, nodeIndex = nodeIndex)
            }

            if (snap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("no snapshot yet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Top overlay chrome (untransformed screen space).
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()   // top chrome clears the system status bar
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Feed") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    "Canvas · $wsName",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canvas != null) {
                    Text(
                        "epoch ${snap?.epoch?.take(8)} · ${canvas.nodes.size} nodes · " +
                            "${canvas.connections.size} ropes",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FollowChip(following = following, enabled = canvas != null) { on ->
                following = on
                if (on) fitted = true    // mirror takes over from here
                scope.launch { session.store.saveFollowMac(serverKey, on) }
                if (!on) persistCamera() // keep where we stopped
            }
            TextButton(
                onClick = { fitted = false },
                enabled = canvas != null && !following,
            ) { Text("⤢ Fit") }
            ConnChip(socketState, socketError)
        }

        // Zoom badge.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Surface1.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                "zoom ${(camera.scale * 100).roundToInt()}%" + if (following) " · following Mac" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Debounce: camera writes hit DataStore at most twice a second (never the host). */
private const val CAMERA_SAVE_DEBOUNCE_MS = 500L

/** Cursors fade out over the last stretch of the staleness window. */
private const val CURSOR_FADE_MS = 5_000L

/** Presence is reported once the camera settles, not per gesture frame. */
private const val PRESENCE_REPORT_MS = 1_500L

// -------------------------------------------------------------------------------- camera

/** Pan + zoom camera, everything in dp (canvas points ≙ dp). Coerced host-like zoom range. */
class CameraState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    fun apply(centroid: Offset, pan: Offset, zoom: Float) {
        val new = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (new != scale) {
            // Keep the world point under the centroid fixed while changing scale.
            val wx = (centroid.x - offsetX) / scale
            val wy = (centroid.y - offsetY) / scale
            offsetX = centroid.x - wx * new
            offsetY = centroid.y - wy * new
            scale = new
        }
        offsetX += pan.x
        offsetY += pan.y
    }

    /** Center [content] (canvas-space rect) inside the stage with padding. */
    fun fitTo(content: Rect, stageWidthDp: Float, stageHeightDp: Float) {
        val w = content.width.toFloat()
        val h = content.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val availW = stageWidthDp - 2f * FIT_PAD_DP
        val availH = stageHeightDp - 2f * FIT_PAD_DP
        if (availW <= 0f || availH <= 0f) return
        scale = (minOf(availW / w, availH / h)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = ((stageWidthDp - w * scale) / 2f - content.left * scale)
        offsetY = ((stageHeightDp - h * scale) / 2f - content.top * scale)
    }

    /** Restore a persisted camera ([x, y, zoom] ≙ offsetX/offsetY/scale). */
    fun set(v: PairingStore.CameraView) {
        scale = v.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = v.x
        offsetY = v.y
    }

    fun toView(): PairingStore.CameraView = PairingStore.CameraView(offsetX, offsetY, scale)

    /**
     * Follow-Mac: adopt the host camera from the snapshot (canvas-space viewport origin + zoom).
     * The host viewport size is not published, so the origin lands at our top-left corner.
     */
    fun mirror(origin: CanvasPoint, zoom: Double) {
        scale = zoom.toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = -origin.x.toFloat() * scale
        offsetY = -origin.y.toFloat() * scale
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 4f
        const val FIT_PAD_DP = 48f
    }
}

// ----------------------------------------------------------------------------- gestures

/**
 * Minimal pan/pinch transform detector on stable pointer APIs
 * (`Modifier.transformable` has no centroid; `detectTransformGestures` was removed in
 * Compose foundation 1.10). Emits (centroid px, pan px delta, zoom ratio). Single pointer
 * → pan; two pointers → pinch around the mid-point. Touch slop gate keeps taps clean;
 * once active, position changes are consumed so child clickables cancel.
 */
private suspend fun PointerInputScope.detectCanvasGestures(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var lastCount = 0
        var lastCentroid = Offset.Zero
        var lastSpread = 0f
        var moved = 0f
        var active = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val centroid = centroidOf(pressed)
            val count = pressed.size
            if (count != lastCount) {
                // Pointer membership changed (finger landed/lifted): re-anchor.
                lastCount = count
                lastCentroid = centroid
                lastSpread = spreadOf(pressed, centroid)
                if (count >= 2) active = true
                continue
            }
            val pan = centroid - lastCentroid
            val spread = spreadOf(pressed, centroid)
            val zoom = if (count >= 2 && lastSpread > 0f) (spread / lastSpread) else 1f
            moved += pan.getDistance()
            if (!active && (moved > slop || count >= 2)) active = true
            if (active) {
                onTransform(centroid, pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            lastCentroid = centroid
            lastSpread = spread
        }
    }
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sx = 0f
    var sy = 0f
    changes.forEach {
        sx += it.position.x
        sy += it.position.y
    }
    return Offset(sx / changes.size, sy / changes.size)
}

/** Sum of finger distances to the centroid (spread metric for pinch zoom). */
private fun spreadOf(changes: List<PointerInputChange>, centroid: Offset): Float =
    if (changes.size < 2) 0f
    else changes.sumOf { (it.position - centroid).getDistance().toDouble() }.toFloat()

// ------------------------------------------------------------------------------ cursors

/**
 * Presence cursors, drawn inside the camera layer at canvas points: our own report plus any peer
 * report the host sends. The contract documents presence as client -> server only (the host draws
 * remote cursors on its own canvas) and defines no peer roster, so peers render when a host does
 * send them and simply do not exist otherwise. Reports older than 20 s fade out and disappear.
 */
@Composable
private fun CursorsLayer(
    self: PresencePeer?,
    peers: List<PresencePeer>,
    nodeIndex: Map<String, CanvasNode>,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
        }
    }
    val cursors = buildList {
        self?.let { add(it) }
        addAll(peers)
    }.mapNotNull { peer ->
        val age = now - peer.atMillis
        if (age > PresencePeer.STALE_MS) return@mapNotNull null
        val pos = presencePosition(peer, nodeIndex) ?: return@mapNotNull null
        val alpha = if (age <= PresencePeer.STALE_MS - CURSOR_FADE_MS) 1f
        else ((PresencePeer.STALE_MS - age).toFloat() / CURSOR_FADE_MS)
        Triple(peer, pos, alpha.coerceIn(0f, 1f))
    }
    cursors.forEach { (peer, pos, alpha) ->
        Row(
            Modifier
                .offset(x = pos.x.dp, y = pos.y.dp)
                .zIndex(900f)
                .graphicsLayer { this.alpha = alpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(10.dp)) {
                val arrow = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height * 0.28f)
                    lineTo(size.width * 0.28f, size.height)
                    close()
                }
                drawPath(arrow, color = presenceColor(peer))
            }
            Box(
                Modifier
                    .background(Surface2.copy(alpha = 0.9f), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(peer.name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** Canvas-space position of a report: an explicit point, else the center of the reported node. */
private fun presencePosition(peer: PresencePeer, nodeIndex: Map<String, CanvasNode>): Offset? {
    if (peer.hasPoint) return Offset((peer.x ?: 0.0).toFloat(), (peer.y ?: 0.0).toFloat())
    val n = peer.nodeId?.let { nodeIndex[it] } ?: return null
    return Offset((n.frame.x + n.frame.width / 2).toFloat(), (n.frame.y + n.frame.height / 2).toFloat())
}

private fun presenceColor(peer: PresencePeer): Color {
    val hex = peer.color ?: return Amber
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Amber)
}

@Composable
private fun FollowChip(following: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val bg = if (following) Green.copy(alpha = 0.22f) else Surface2
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        following -> Green
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(bg, RoundedCornerShape(50))
            .clickable(enabled = enabled) { onToggle(!following) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(if (following) "⚲ Following" else "⚲ Follow Mac", fontSize = 11.sp, color = fg)
    }
}

// --------------------------------------------------------------------------- rendering

/** Content rect to fit: host-reported canvas bounds when present, else union of frames. */
private fun contentRect(snap: FeedSnapshot): Rect? {
    val c = snap.canvas ?: return null
    val b = c.bounds
    if (b != null && b.width > 0 && b.height > 0) {
        return Rect(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
    }
    return c.nodes
        .map { Rect(it.frame.x.toFloat(), it.frame.y.toFloat(), (it.frame.x + it.frame.width).toFloat(), (it.frame.y + it.frame.height).toFloat()) }
        .reduceOrNull { a, r ->
            Rect(
                minOf(a.left, r.left), minOf(a.top, r.top),
                maxOf(a.right, r.right), maxOf(a.bottom, r.bottom),
            )
        }
}

private fun nodesById(canvas: app.maestri.wire.CanvasSnapshot?): Map<String, CanvasNode> =
    canvas?.nodes?.associateBy { it.id } ?: emptyMap()

/** Rope layer: verbatim host polylines (edge-to-edge), active ropes emphasized. */
@Composable
private fun ConnectionsLayer(
    canvas: app.maestri.wire.CanvasSnapshot?,
    nodesById: Map<String, CanvasNode>,
) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvas == null) return@Canvas
        val s = density // canvas point → px in this layer's local space
        canvas.connections.forEach { c ->
            val pts = c.points.ifEmpty { fallbackEndpoints(c, nodesById) }
            if (pts.size < 2) return@forEach
            val accent = endpointColor(c, nodesById)
            val path = Path()
            pts.forEachIndexed { i, p ->
                val x = (p.x * s).toFloat()
                val y = (p.y * s).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (c.isActive) {
                drawPath(
                    path,
                    accent.copy(alpha = 0.30f),
                    style = Stroke(width = 7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path,
                    accent,
                    style = Stroke(width = 3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                drawPath(
                    path,
                    Color(0xFF8A8F98).copy(alpha = 0.55f),
                    style = Stroke(width = 1.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** Missing host points → straight line between the two node frame centers. */
private fun fallbackEndpoints(
    c: CanvasConnection,
    nodesById: Map<String, CanvasNode>,
): List<CanvasPoint> {
    val from = nodesById[c.fromNodeId]?.frame ?: return emptyList()
    val to = nodesById[c.toNodeId]?.frame ?: return emptyList()
    return listOf(
        CanvasPoint(from.centerX, from.centerY),
        CanvasPoint(to.centerX, to.centerY),
    )
}

private fun endpointColor(c: CanvasConnection, nodesById: Map<String, CanvasNode>): Color {
    val raw = nodesById[c.fromNodeId]?.color ?: nodesById[c.toNodeId]?.color
    return parseColor(raw, Green)
}

/** One canvas node: terminal payload → rich card; anything else → simple titled box. */
@Composable
private fun CanvasNodeCard(
    node: CanvasNode,
    snap: FeedSnapshot?,
    onOpenTerminal: (terminalId: String, terminalName: String) -> Unit,
) {
    val t = node.terminal
    if (node.kind == "terminal" && t != null) {
        val pending = snap?.pendingPromptIds?.contains(t.id) == true
        CanvasTerminalNode(
            node, t, pending,
            onOpen = { onOpenTerminal(t.id, t.name) },
        )
    } else {
        // Per docs: an unknown/non-terminal kind still has frame + title — simple box.
        UnknownNodeBox(node)
    }
}

@Composable
private fun CanvasTerminalNode(
    node: CanvasNode,
    t: TerminalCard,
    pending: Boolean,
    onOpen: () -> Unit,
) {
    val accent = parseColor(node.color, parseColor(t.color, Green))
    val dotColor = when {
        pending || t.needsAttention -> app.maestri.ui.Red
        t.isActive == true -> Green
        t.isLive -> Amber
        else -> Color.Gray
    }
    Box(
        Modifier
            .zIndex(node.zIndex.toFloat())
            .offset(x = node.frame.x.dp, y = node.frame.y.dp)
            .size(node.frame.width.dp, node.frame.height.dp)
    ) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (pending || t.needsAttention) Surface2 else Surface1
            ),
            border = BorderStroke(1.5.dp, accent.copy(alpha = 0.55f)),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(iconGlyph(t.icon), fontSize = 22.sp, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.name + (t.accountName?.let { " · $it" } ?: ""),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 19.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (t.isManager) Text("  (maestro)", fontSize = 11.sp, color = Amber)
                        }
                        Text(
                            buildString {
                                append(if (t.isRunning) "running" else "stopped")
                                if (t.isActive == true) append(" · working")
                                else if (t.needsAttention) append(" · attention")
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box(Modifier.size(12.dp).background(dotColor, CircleShape))
                }
                node.subtitle?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                t.floorName?.let { fn ->
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .background(Surface2, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(fn, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                val lines = t.preview.takeLast(3)
                if (lines.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0C0E), RoundedCornerShape(8.dp))
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
            }
        }
    }
}

@Composable
private fun UnknownNodeBox(node: CanvasNode) {
    Box(
        Modifier
            .zIndex(node.zIndex.toFloat())
            .offset(x = node.frame.x.dp, y = node.frame.y.dp)
            .size(node.frame.width.dp, node.frame.height.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.92f)),
            border = BorderStroke(1.dp, Surface2),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    node.title.ifBlank { node.kind },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                node.subtitle?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (node.kind.isNotBlank()) {
                    Text(
                        "(read-only · kind ${node.kind})",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** #RRGGBB → Color, verbatim hex; anything malformed → fallback. */
private fun parseColor(raw: String?, fallback: Color): Color {
    if (raw == null || raw.length != 7 || raw[0] != '#') return fallback
    val v = runCatching { raw.substring(1).toLong(16) }.getOrNull() ?: return fallback
    return if (v > 0xFFFFFFL || v < 0L) fallback else Color(0xFF000000L or v)
}