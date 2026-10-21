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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import app.maestri.ui.Style.monoHud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import app.maestri.ui.CanvasStrings
import app.maestri.ui.iconGlyph
import app.maestri.ui.notes.FicharioActions
import app.maestri.ui.notes.FicharioTabRailInline
import app.maestri.ui.NotesStrings
import app.maestri.wire.CanvasCableTie
import app.maestri.wire.CanvasConnection
import app.maestri.wire.CanvasNode
import app.maestri.wire.CanvasPortal
import app.maestri.wire.CanvasPoint
import app.maestri.wire.CanvasWrites
import app.maestri.wire.SnapshotMedia
import androidx.compose.runtime.mutableStateMapOf
import app.maestri.store.PairingStore
import app.maestri.wire.FeedSnapshot
import app.maestri.wire.FicharioState
import app.maestri.wire.NotesApi
import app.maestri.wire.PresencePeer
import app.maestri.wire.TerminalCard
import app.maestri.wire.session.WireSession
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.softHalo
import app.maestri.ui.Style.warmRing
import app.maestri.ui.Style.pillShape
import app.maestri.ui.Style.cardShape
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.BackLabel
import app.maestri.ui.Style.GlyphOrIcon
import app.maestri.ui.Style.vendorColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Overline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
    onOpenNote: (wsId: String, nodeId: String) -> Unit,
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

    // Canvas writes (P1a): one controller owns the modes, the selection, the dialogs and every
    // route; the optimistic overlay it exposes is what makes a write visible before the host echoes.
    val ctl = remember(session, wsId, scope) { CanvasWriteController(session, scope, wsId) }
    val caps = session.serverInfo
    val canWrite = caps?.has("canvasWrite") == true && caps?.role == "owner"
    val writeReason = CanvasStrings.writeGateReason(canWrite, caps)
    val ficharioCaps = caps?.has("nativeNotes") == true && caps?.has("noteStackWrites") == true &&
        caps?.has("noteStackPages") == true
    val canFileNotes = ficharioCaps && caps?.has("noteStackFiling") == true
    val ficharioReason = if (ficharioCaps) null else NotesStrings.YOUR_COMPUTER_CANT_TAKE_FICHARIO_CHANGES_YET
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    val fichario = remember(session, wsId, toastContext) {
        FicharioActions(NotesApi(session), wsId) { message: String ->
            android.widget.Toast.makeText(toastContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val optimism = ctl.optimism
    var marquee by remember { mutableStateOf<Rect?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var penActive by remember { mutableStateOf(false) }
    var tieMenu by remember { mutableStateOf<CanvasCableTie?>(null) }
    var portalFor by remember { mutableStateOf<CanvasNode?>(null) }
    val tiePositions = remember { mutableStateMapOf<String, Offset>() }

    // Persisted per-workspace view state, as iOS keeps it in UserDefaults
    // (`remote.camera.<serverId>.<wsId>` = [x,y,zoom] plus the view's followMac flag).
    var following by remember { mutableStateOf(false) }

    // design-v2 modern (todo #31): Stack (single-column cards + board preview strip)
    // is the PRIMARY state; Board = the full spatial stage. Defaults to Stack (spec §4).
    var viewMode by remember { mutableStateOf(CanvasViewMode.Stack) }
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

    // Optimistic writes: a NEW epoch means resync, so every overlay is dropped; the host echoing
    // our own mutationId retires the pending entry; a snapshot that agrees drops the override.
    val lastMutation by session.feed.lastMutation.collectAsState()
    LaunchedEffect(snapshot) {
        val s = snapshot ?: return@LaunchedEffect
        if (s.workspaceId == wsId) optimism.onSnapshot(s)
    }
    LaunchedEffect(lastMutation) { lastMutation?.let { optimism.onMutation(it) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 480.dp   // v2.1 phone arrangement (frame 04) below the tablet width
        val stageW = (maxWidth - if (compact) 32.dp else 0.dp).value   // board-card h-insets (16 ×2)
        val stageH = (maxHeight - if (compact) 92.dp else 0.dp).value  // board-card v-insets (4 top + 88 band)
        val snap = snapshot
        val canvas = snap?.takeIf { it.workspaceId == wsId }?.canvas

        // ---------------------------------------------------------------- interaction helpers
        // Canvas points ≙ stage dp run through the camera; the pointer machine hands us stage dp.
        fun hitIndexNow(): CanvasHitIndex = CanvasHitIndex(
            nodes = canvas?.nodes.orEmpty(),
            connections = canvas?.connections.orEmpty(),
            ties = canvas?.cableTies.orEmpty(),
            frameOf = { id ->
                optimism.frames[id]?.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
            },
        )

        fun stageToCanvas(stage: Offset): Offset =
            Offset((stage.x - camera.offsetX) / camera.scale, (stage.y - camera.offsetY) / camera.scale)

        // A terminal node still opens its terminal; a text node opens the inline editor;
        // a portal node opens the read-only portal viewer (host-managed card).
        fun openOrSelect(node: CanvasNode) {
            val t = node.terminal
            when {
                node.kind == "terminal" && t != null -> onOpenTerminal(t.id, t.name)
                node.kind == "note" -> onOpenNote(wsId, node.id)
                node.kind == "text" -> ctl.openTextEditor(node)
                node.kind == "portal" && node.portal != null -> portalFor = node
                else -> ctl.selectNode(node, canvas)
            }
        }

        // One tap handler for every mode: it either finishes a pending mode or acts on what is hit.
        fun tapAt(p: Offset) {
            val hit = hitIndexNow()
            when (val m = ctl.mode) {
                is CanvasMode.Placing -> ctl.createNode(m.kind, p.x, p.y)
                is CanvasMode.Connecting -> {
                    val node = hit.nodeAt(p)
                    when {
                        node == null -> ctl.error(CanvasStrings.canTConnectToThis)
                        node.id == m.fromNodeId -> ctl.error(CanvasStrings.alreadyConnected)
                        else -> ctl.connect(m.fromNodeId, node.id)
                    }
                }
                is CanvasMode.TieLinking -> {
                    val rope = hit.ropeAt(p, ROPE_TOUCH_DP)
                    when {
                        rope == null -> ctl.error("Tap a rope")
                        m.firstConnectionId.isEmpty() -> {
                            ctl.enterMode(CanvasMode.TieLinking(rope.id))
                            ctl.info("Tap the second rope")
                        }
                        rope.id == m.firstConnectionId -> ctl.error("Tap another rope")
                        else -> ctl.tieRopes(m.firstConnectionId, rope.id, p.x, p.y)
                    }
                }
                CanvasMode.Idle -> {
                    val node = hit.nodeAt(p)
                    val tie = if (node == null) hit.tieAt(p, TIE_TOUCH_DP) else null
                    when {
                        node != null -> openOrSelect(node)
                        tie != null -> tieMenu = tie
                        hit.ropeAt(p, ROPE_TOUCH_DP) != null -> ctl.clearSelection()
                        else -> ctl.clearSelection()
                    }
                }
            }
        }

        // Long-press without dragging: the node options menu.
        fun longPressAt(p: Offset, node: CanvasNode?) {
            if (node != null) ctl.menuFor = node
        }

        fun selectInRect(r: Rect) {
            val nodes = canvas?.nodes.orEmpty()
            ctl.clearSelection()
            nodes.filter { node ->
                val f = optimism.frames[node.id]?.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
                    ?: rectOf(node.frame)
                f.left <= r.right && f.right >= r.left && f.top <= r.bottom && f.bottom >= r.top
            }.forEach { ctl.selectNode(it, canvas) }
            android.util.Log.d("QACanvas", "marquee selected ${ctl.selection.size}")
        }

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

        // Full-screen gesture surface BEHIND the overlay chrome. Pointer positions arrive as stage
        // px; the helpers above turn them into canvas points for hit-testing and for optimistic
        // frames, so a move never has to round-trip through the host to be visible.
        // (Board state only — Stack renders the card list below.)
        if (viewMode == CanvasViewMode.Board) Box(
            Modifier
                .then(
                    if (compact) Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp, bottom = 88.dp)
                    else Modifier
                )
                .fillMaxSize()
                .then(
                    if (compact) Modifier
                        .clip(RoundedCornerShape(MaestriRadii.Card))
                        .background(Palette.SunkenSurface)              // v2.1 --card-2 #232220 (frame 04)
                        .warmRing(hairline = Palette.WarmSilver.copy(alpha = 0.14f), radius = MaestriRadii.Card)
                    else Modifier
                )
                .pointerInput(following, ctl.mode, penActive, canvas) {
                    // Pan/pinch are hidden while following the Mac; the chip turns it off.
                    if (following) return@pointerInput
                    val d = 1f / this.density  // px → dp
                    detectCanvasInteractions(
                        key = ctl.mode,
                        penActive = penActive,
                        hitIndex = { hitIndexNow() },
                        toCanvas = { stage -> stageToCanvas(stage) },
                        onTransform = { centroidPx, panPx, zoom ->
                            camera.apply(
                                centroid = Offset(centroidPx.x * d, centroidPx.y * d),
                                pan = Offset(panPx.x * d, panPx.y * d),
                                zoom = zoom,
                            )
                            persistCamera()
                        },
                        onTap = { p -> tapAt(p) },
                        onLockedRefusal = { ctl.info(CanvasStrings.thisNodeIsLockedOnYourComputer) },
                        onLongPress = { p, node -> longPressAt(p, node) },
                        onNodeDrag = { id, x, y ->
                            val n = canvas?.nodes?.firstOrNull { it.id == id }
                            if (n != null) {
                                ctl.previewMove(id, x, y, n.frame.width.toInt(), n.frame.height.toInt())
                            }
                        },
                        onNodeDragEnd = { id ->
                            val ov = optimism.frames[id]
                            if (ov != null) {
                                // The host snaps a move to the 20-pt grid: send what it will store.
                                ctl.moveNode(
                                    id,
                                    CanvasWrites.snap(ov.x),
                                    CanvasWrites.snap(ov.y),
                                    ov.width,
                                    ov.height,
                                )
                            }
                        },
                        onMarquee = { marquee = it },
                        onMarqueeEnd = { r -> selectInRect(r) },
                        onStrokeCancel = { },
                        onStroke = { pts -> ctl.submitStroke(pts, PEN_INK, PEN_WIDTH) },
                        tieRadius = TIE_TOUCH_DP,
                        onTieMove = { id, x, y -> tiePositions[id] = Offset(x, y) },
                        onTieMoveEnd = { id, x, y ->
                            tiePositions[id] = Offset(x, y)
                            ctl.moveTie(id, x, y)
                        },
                    )
                }
        ) {
            if (compact) {
                // v2.1 (frame 04): the minimap's 24px grid is painted on the SCREEN axis —
                // it does not track the camera, matching the artifact's static grid.
                Canvas(Modifier.matchParentSize()) {
                    val gridColor = Palette.WarmSilver.copy(alpha = 0.05f)
                    var gx = 0f
                    while (gx <= size.width) {
                        drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 1f)
                        gx += 24.dp.toPx()
                    }
                    var gy = 0f
                    while (gy <= size.height) {
                        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1f)
                        gy += 24.dp.toPx()
                    }
                }
            }
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
                val visibleNodes = canvas?.nodes.orEmpty().filter { optimism.removed[it.id] != true }
                ConnectionsLayer(canvas = canvas, nodesById = nodeIndex)
                DrawingLayer(canvas?.drawings.orEmpty(), ctl.strokes)
                TieLayer(canvas?.cableTies.orEmpty(), tiePositions)
                visibleNodes.forEach { node ->
                    key(node.id) {
                        // An optimistic frame wins over the snapshot until the host reports the same one.
                        val ov = optimism.frames[node.id]
                        val shown = if (ov != null) node.copy(frame = app.maestri.wire.CanvasRect(
                            ov.x.toDouble(), ov.y.toDouble(), ov.width.toDouble(), ov.height.toDouble(),
                        )) else node
                        CanvasNodeCard(
                            node = shown,
                            snap = snap,
                            selected = ctl.selection[node.id] == true,
                            compact = compact,
                            session = session,
                            wsId = wsId,
                            onOpenNote = onOpenNote,
                        )
                    }
                }
                MarqueeRect(marquee)
                CursorsLayer(self = selfPresence, peers = peers, nodeIndex = nodeIndex)
            }

            if (snap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("no snapshot yet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // v2.1 (frame 04): mono counts line bottom-start on the phone — REAL derived
            // counts (the mock's "12 pts · 2 floors" is decorative), rope/note legend tinted.
            if (compact && canvas != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${canvas.nodes.size} pts · ${snap.floors.size} floors · rope ",
                        fontFamily = FontFamily.Monospace, fontSize = 9.5.sp,
                    )
                    Text("#F0A65E", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = RopeInk)
                    Text(" · note ", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                    Text("#F5D142", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = NoteYellow)
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
            TextButton(onClick = onBack) { BackLabel("Feed") }
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
            // design-v2 modern: segmented Stack⇄Board (44pt, ivory-on-charcoal thumb).
            CanvasViewSegmented(
                mode = viewMode,
                onStack = { viewMode = CanvasViewMode.Stack },
                onBoard = { viewMode = CanvasViewMode.Board },
            )
            // Camera chrome is a Board-state concern; Stack keeps the socket chip only.
            if (viewMode == CanvasViewMode.Board) {
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
            } else {
                ConnChip(socketState, socketError)
            }
        }

        // Write tools + the mode banner sit in their own row so the header keeps its layout.
        Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(top = 56.dp).statusBarsPadding()) {
            if (viewMode == CanvasViewMode.Board && penActive) {
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(LocalMaestriColors.current.warning.copy(alpha = 0.18f), controlShape())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(CanvasStrings.drawWithOneFingerTwoFingersPanAndZoom, fontSize = 11.sp, color = LocalMaestriColors.current.warning)
                }
            }
            CanvasModeBanner(mode = ctl.mode, onCancel = { ctl.enterMode(CanvasMode.Idle) })
            CanvasBanner(message = ctl.banner, isError = ctl.bannerIsError, onDismiss = { ctl.dismissBanner() })
            if (ctl.busy) {
                Text("·  working…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Zoom badge — Board state only, lifted above the floating toolbar's clearance band.
        if (viewMode == CanvasViewMode.Board) Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 88.dp)
                .background(LocalMaestriColors.current.canvasSurface.copy(alpha = 0.85f), pillShape())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                "zoom ${(camera.scale * 100).roundToInt()}%" + if (following) " · following Mac" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // design-v2 modern: the floating 7-pill thumb toolbar (spec §4 04) — glass
        // rgba(20,20,19,.72) pills (hairline ring + halo; no backdrop blur in Compose 1.7),
        // 44pt targets, ✕ destructive in Error Crimson, z-index 10, 88px content band so
        // it never covers the stage. Board state only.
        // The 7-pill toolbar rides BOTH states (the example page shows it over the
        // stack preview); tap-needing modes (＋/✎/⌘) auto-switch to Board above.
        val selCount = ctl.selection.size
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (compact) 16.dp else 24.dp)
                .zIndex(10f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            writeReason?.let {
                Text(
                    it,
                    fontSize = 10.5.sp,
                    color = LocalMaestriColors.current.secondaryLabel,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill("＋", "Add node", false, canWrite, false, onClick = { addMenu = true; viewMode = CanvasViewMode.Board })
                GlassPill(
                    "✎", "Draw with one finger", penActive, canWrite, false, liftOnActive = compact,
                    onClick = {
                        penActive = !penActive
                        ctl.enterMode(CanvasMode.Idle)
                        if (penActive) viewMode = CanvasViewMode.Board
                    },
                )
                GlassPill(
                    "⌘", "Tie two ropes",
                    if (compact) ctl.mode is CanvasMode.TieLinking else false,
                    canWrite && (canvas?.connections?.size ?: 0) >= 1,
                    false,
                    liftOnActive = compact,
                    onClick = {
                        penActive = false
                        ctl.enterMode(CanvasMode.TieLinking(""))
                        viewMode = CanvasViewMode.Board
                    },
                )
                GlassPill("⌫", "Erase last drawing", false, canWrite, false, onClick = { ctl.eraseLastDrawing() })
                GlassPill(
                    "◐", "Change color", false, canWrite && selCount > 0, false,
                    onClick = {
                        val node = canvas?.nodes?.firstOrNull { ctl.selection.containsKey(it.id) }
                        ctl.colorFor = node?.groupId?.let { ColorTarget.Group(it) }
                            ?: node?.let { ColorTarget.Node(it) }
                    },
                )
                GlassPill(
                    "⊞", "Group or rename group", true, canWrite && selCount > 0, false,
                    onClick = {
                        val sel = canvas?.nodes?.filter { ctl.selection.containsKey(it.id) }.orEmpty()
                        val gid = sel.firstOrNull()?.groupId
                        if (gid == null) ctl.groupSelected(ctl.selection.keys.toList())
                        else ctl.groupActionsFor = gid          // already grouped: renames it
                    },
                )
                GlassPill(
                    "✕", CanvasStrings.deleteSelectedNodes, false, canWrite && selCount > 0, true,
                    onClick = { ctl.askDelete(ctl.selection.keys.toList()) },
                )
            }
        }
        if (viewMode == CanvasViewMode.Stack) {
            // ── Stack state (primary): single-column node cards + board preview strip (spec §4)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp, bottom = 76.dp)
            ) {
                if (canvas == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("no snapshot yet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    CanvasStackSummary(canvas)
                    val visible = canvas.nodes.filter { optimism.removed[it.id] != true }
                    if (visible.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(CanvasStrings.stackEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            visible.forEach { node ->
                                key(node.id) {
                                    StackNodeCard(
                                        node = node,
                                        snap = snap,
                                        selected = ctl.selection[node.id] == true,
                                        session = session,
                                        wsId = wsId,
                                        onOpenNote = onOpenNote,
                                        onClick = { openOrSelect(node) },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Overline("Board preview")
                    Spacer(Modifier.height(6.dp))
                    CanvasPreviewStrip(canvas)
                }
            }
        }

        // ------------------------------------------------------------------ overlays
        ctl.menuFor?.let { node ->
            val stacks = canvas?.nodes.orEmpty().mapNotNull { it.noteStack }
            val selectedNoteIds = canvas?.nodes.orEmpty()
                .filter { ctl.selection[it.id] == true && it.kind == "note" }
                .map { it.id }
            val ficharioItems = mutableListOf<Pair<String, () -> Unit>>()
            if (node.kind == "note") {
                if (canFileNotes) {
                    stacks.filterNot { s -> s.tabs.any { it.nodeId == node.id } }.forEach { s ->
                        val label = if (s.name.isBlank() || s.name == "null") NotesStrings.FICHARIO else s.name
                        ficharioItems.add(
                            (NotesStrings.ADD_TO_FICHARIO + " · " + label) to {
                                ctl.menuFor = null
                                scope.launch { fichario.fileNote(s.stackNodeId, node.id, null, null) }
                            },
                        )
                    }
                    if (selectedNoteIds.size >= 2) {
                        val ids = (selectedNoteIds + node.id).distinct()
                        ficharioItems.add(
                            "New Fichário" to {
                                ctl.menuFor = null
                                scope.launch { fichario.createFromSelection(ids, null) }
                            },
                        )
                    }
                }
                stacks.filter { s -> s.tabs.any { it.nodeId == node.id } }.forEach { s ->
                    val label = if (s.name.isBlank() || s.name == "null") NotesStrings.FICHARIO else s.name
                    ficharioItems.add(
                        (NotesStrings.REMOVE_FROM_FICHARIO + " · " + label) to {
                            ctl.menuFor = null
                            scope.launch { fichario.removeFromFichario(s.stackNodeId, node.id, 40.0, 40.0) }
                        },
                    )
                }
            }
            node.noteStack?.let { s ->
                ficharioItems.add(
                    NotesStrings.NEW_PAGE to {
                        ctl.menuFor = null
                        scope.launch { fichario.newPage(s.stackNodeId, s.frontNoteId) }
                    },
                )
            }
            NodeContextMenu(
                node = node,
                connections = canvas?.connections.orEmpty(),
                writeDisabledReason = writeReason,
                onDismiss = { ctl.menuFor = null },
                onUngroup = {
                    ctl.menuFor = null
                    val g = node.groupId
                    val members = canvas?.nodes?.filter { it.groupId == g }?.map { it.id }
                    if (g != null && !members.isNullOrEmpty()) {
                        ctl.ungroup(members)    // one workspace-level ungroup for the whole group
                    } else {
                        ctl.error(CanvasStrings.thisGroupIsNoLongerOnTheCanvas)
                    }
                },
                onRename = {
                    ctl.menuFor = null
                    ctl.renameFor = node
                    ctl.renameText = node.title
                },
                onLock = { ctl.toggleLock(node) },
                onDuplicate = { ctl.duplicate(node) },
                onConnect = {
                    ctl.menuFor = null
                    ctl.enterMode(CanvasMode.Connecting(node.id))
                },
                onDisconnectAll = { ctl.disconnectAll(node.id, canvas?.connections.orEmpty()) },
                onDelete = { ctl.askDelete(listOf(node.id)) },
                onEditText = if (node.kind == "text") {
                    {
                        ctl.menuFor = null
                        ctl.openTextEditor(node)
                    }
                } else null,
                onEditNote = if (node.kind == "note") {
                    {
                        ctl.menuFor = null
                        onOpenNote(wsId, node.id)
                    }
                } else null,
                ficharioItems = ficharioItems,
                ficharioDisabledReason = ficharioReason,
            )
        }
        tieMenu?.let { tie ->
            TieContextMenu(
                onDismiss = { tieMenu = null },
                writeDisabledReason = writeReason,
                onCut = {
                    ctl.cutTie(tie.id)
                    tieMenu = null
                },
            )
        }
        if (addMenu) {
            CreateMenu(
                onPick = { kind ->
                    addMenu = false
                    penActive = false
                    ctl.enterMode(CanvasMode.Placing(kind))
                },
                onDismiss = { addMenu = false },
            )
        }
        ctl.renameFor?.let { node ->
            CanvasInputDialog(
                title = CanvasStrings.rename,
                value = ctl.renameText,
                onValue = { ctl.renameText = it },
                onConfirm = {
                    ctl.rename(node, ctl.renameText)
                    ctl.renameFor = null
                },
                onDismiss = { ctl.renameFor = null },
            )
        }
        ctl.confirmIds?.let { ids ->
            val plural = if (ids.size == 1) null else
                "${ids.size} nodes will be removed from the canvas on your computer."
            CanvasConfirmDialog(
                title = if (ids.size == 1) CanvasStrings.deleteThisNode else CanvasStrings.deleteSelectedNodes,
                body = plural ?: CanvasStrings.itSRemovedFromTheCanvasOnYourComputer,
                onConfirm = { ctl.confirmDelete(ids, ids.size > 1) },
                onDismiss = { ctl.confirmIds = null },
            )
        }
        if (ctl.groupDialog) {
            CanvasInputDialog(
                title = CanvasStrings.nameGroup,
                value = ctl.groupName,
                body = CanvasStrings.groupName,
                onValue = { ctl.groupName = it },
                onConfirm = { ctl.confirmGroup(ctl.selection.keys.toList(), ctl.groupName) },
                onDismiss = { ctl.groupDialog = false },
            )
        }
        ctl.groupActionsFor?.let { gid ->
            CanvasInputDialog(
                title = CanvasStrings.groupName,
                value = ctl.groupName,
                onValue = { ctl.groupName = it },
                onConfirm = {
                    ctl.groupRename(gid, ctl.groupName)
                    ctl.groupActionsFor = null
                },
                onDismiss = { ctl.groupActionsFor = null },
            )
        }
        ctl.colorFor?.let { target ->
            ColorPalettePicker(
                title = if (target is ColorTarget.Group) "Change color" else CanvasStrings.changeNoteColor,
                onPick = { name ->
                    when (target) {
                        is ColorTarget.Group -> ctl.groupColor(target.groupId, name)
                        is ColorTarget.Node -> ctl.setNodeColor(target.node, name)
                    }
                },
                onDismiss = { ctl.colorFor = null },
                onClear = (target as? ColorTarget.Group)?.let { g -> { ctl.clearGroupColor(g.groupId) } },
                // NX-05 (grand gate): the host 400s EVERY note-colour clear shape —
                // "Invalid color" / "Invalid body". Note target => chip visible but disabled.
                clearDisabledReason = if (target is ColorTarget.Group) null else CanvasStrings.noteColorClearUnsupported,
            )
        }
        ctl.textFor?.let {
            TextBlockEditor(
                draft = ctl.textDraft,
                size = ctl.textSize,
                weight = ctl.textWeight,
                family = ctl.textFamily,
                color = ctl.textColor,
                onDraft = { d -> ctl.textDraft = d },
                onSize = { s -> ctl.textSize = s },
                onWeight = { w -> ctl.textWeight = w },
                onFamily = { f -> ctl.textFamily = f },
                onColor = { c -> ctl.textColor = c },
                onSave = { ctl.saveText() },
                onDismiss = { ctl.textFor = null },
            )
        }
        portalFor?.let { node ->
            node.portal?.let { portal ->
                PortalViewer(
                    node = node,
                    portal = portal,
                    session = session,
                    onDismiss = { portalFor = null },
                )
            }
        }
    }
}

/** Debounce: camera writes hit DataStore at most twice a second (never the host). */
private const val CAMERA_SAVE_DEBOUNCE_MS = 500L

/** Cursors fade out over the last stretch of the staleness window. */
private const val CURSOR_FADE_MS = 5_000L

/** Presence is reported once the camera settles, not per gesture frame. */
private const val PRESENCE_REPORT_MS = 1_500L

/** Pen ink + width for a local stroke; the host stores exactly what we send. */
private const val PEN_INK = "#F2F3F5"
private const val PEN_WIDTH = 3f

/** Touch tolerance in canvas points for hitting a rope or a tie knot. */
private const val ROPE_TOUCH_DP = 24f
private const val TIE_TOUCH_DP = 30f

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

internal fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sx = 0f
    var sy = 0f
    changes.forEach {
        sx += it.position.x
        sy += it.position.y
    }
    return Offset(sx / changes.size, sy / changes.size)
}

/** Sum of finger distances to the centroid (spread metric for pinch zoom). */
internal fun spreadOf(changes: List<PointerInputChange>, centroid: Offset): Float =
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
    val cursorAccent = LocalMaestriColors.current.warning
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
                drawPath(arrow, color = presenceColor(peer, cursorAccent))
            }
            Box(
                Modifier
                    .background(LocalMaestriColors.current.canvasChromeElevated.copy(alpha = 0.9f), pillShape())
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

private fun presenceColor(peer: PresencePeer, fallback: Color): Color {
    val hex = peer.color ?: return fallback
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

@Composable
private fun FollowChip(following: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val bg = if (following) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else LocalMaestriColors.current.canvasChromeElevated
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        following -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(bg, pillShape())
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
    val ropeAccent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        if (canvas == null) return@Canvas
        val s = density // canvas point → px in this layer's local space
        canvas.connections.forEach { c ->
            val pts = c.points.ifEmpty { fallbackEndpoints(c, nodesById) }
            if (pts.size < 2) return@forEach
            val accent = endpointColor(c, nodesById, ropeAccent)
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

private fun endpointColor(c: CanvasConnection, nodesById: Map<String, CanvasNode>, fallback: Color): Color {
    val raw = nodesById[c.fromNodeId]?.color ?: nodesById[c.toNodeId]?.color
    return parseColor(raw, fallback)
}

/** One canvas node: terminal payload → rich card; anything else → simple titled box. */
@Composable
private fun CanvasNodeCard(
    node: CanvasNode,
    snap: FeedSnapshot?,
    selected: Boolean,
    compact: Boolean = false,   // v2.1 phone blip tints (frame 04); tablet keeps the classic card
    session: WireSession,
    wsId: String,
    onOpenNote: (wsId: String, nodeId: String) -> Unit,
) {
    val t = node.terminal
    if (node.kind == "terminal" && t != null) {
        val pending = snap?.pendingPromptIds?.contains(t.id) == true
        CanvasTerminalNode(node, t, pending, selected, compact = compact)
    } else if (node.kind == "portal" && node.portal != null) {
        CanvasPortalNode(node, node.portal, selected, compact = compact)
    } else {
        // Per docs: an unknown/non-terminal kind still has frame + title — simple box.
        UnknownNodeBox(
            node = node,
            selected = selected,
            compact = compact,
            session = session,
            wsId = wsId,
            onOpenNote = onOpenNote,
        )
    }
}

@Composable
private fun CanvasTerminalNode(
    node: CanvasNode,
    t: TerminalCard,
    pending: Boolean,
    selected: Boolean,
    compact: Boolean = false,
) {
    val accent = parseColor(node.color, parseColor(t.color, MaterialTheme.colorScheme.primary))
    val dotColor = when {
        pending || t.needsAttention -> MaterialTheme.colorScheme.error
        t.isActive == true -> MaterialTheme.colorScheme.primary
        t.isLive -> if (compact) LiveGreen else LocalMaestriColors.current.warning
        else -> Color.Gray
    }
    Box(
        Modifier
            .zIndex(node.zIndex.toFloat())
            .offset(x = node.frame.x.dp, y = node.frame.y.dp)
            .size(node.frame.width.dp, node.frame.height.dp)
    ) {
        // Taps are handled by the canvas gesture machine (one hit-test source), so the card itself
        // is inert; the selection ring shows what the marquee or a tap selected.
        SelectionRing(selected, Modifier.fillMaxSize())
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = cardShape(),
            colors = CardDefaults.cardColors(
                containerColor = if (pending || t.needsAttention) LocalMaestriColors.current.canvasChromeElevated else LocalMaestriColors.current.canvasSurface
            ),
            border = BorderStroke(
                if (selected) 2.dp else 1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.55f),
            ),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphOrIcon(t.icon, fontSize = 22.sp, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.name + (t.accountName?.let { " · $it" } ?: ""),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 19.sp,
                                color = if (compact && t.isLive) LiveGreen else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (t.isManager) Text("  (maestro)", fontSize = 11.sp, color = LocalMaestriColors.current.warning)
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
                            .background(LocalMaestriColors.current.canvasChromeElevated, pillShape())
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
                            .background(Color(0xFF0B0C0E), controlShape())
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

/**
 * A portal node's read-only canvas card: the host owns the page — nothing on the card is
 * a control. The dot mirrors the node's runtime (ready/live green, loading warn,
 * failed/error, unloaded gray); canGoBack/canGoForward read as text on the card, since
 * those write verbs are CANDIDATE residues and must not render as buttons.
 */
@Composable
private fun CanvasPortalNode(
    node: CanvasNode,
    portal: CanvasPortal,
    selected: Boolean,
    compact: Boolean = false,
) {
    val c = LocalMaestriColors.current
    Box(
        Modifier
            .zIndex(node.zIndex.toFloat())
            .offset(x = node.frame.x.dp, y = node.frame.y.dp)
            .size(node.frame.width.dp, node.frame.height.dp)
    ) {
        SelectionRing(selected, Modifier.fillMaxSize())
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = c.canvasSurface),
            border = BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else c.canvasChromeElevated,
            ),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphOrIcon("globe", fontSize = 22.sp, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            portal.name.ifBlank { node.title },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                portal.runtime?.let { append("runtime $it") } ?: append("runtime —")
                                portal.status?.let { append(" · status $it") }
                                if (portal.isUnloaded) append(" · unloaded")
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.size(12.dp).background(portalDotColor(portal), CircleShape))
                }
                (portal.currentURL ?: portal.url)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.canvasChromeElevated, controlShape())
                        .padding(8.dp)
                ) {
                    Text(
                        if (portal.isUnloaded) "unloaded — no live page underneath"
                        else when (portal.runtime) {
                            "ready", "live" -> "snapshot available"
                            "loading" -> "page is loading — snapshot may not be live yet"
                            null -> "runtime unknown"
                            else -> "runtime ${portal.runtime} — snapshot may not be live yet"
                        },
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

@Composable
private fun portalDotColor(portal: CanvasPortal): Color = when (portal.runtime) {
    "ready", "live" -> LiveGreen
    "loading" -> LocalMaestriColors.current.warning
    "failed" -> Palette.Crimson
    else -> if (portal.isUnloaded) Color.Gray else Palette.StoneGray
}

/**
 * The portal viewer: name/URL/runtime/status + the ETag-revalidated snapshot when the
 * runtime carries a live image (docs §140: only ready/live are worth fetching; 404 =
 * honest "no live image" state). No navigation/back/forward/reload/unload controls —
 * those verbs are CANDIDATE and this card reads.
 */
@Composable
private fun PortalViewer(
    node: CanvasNode,
    portal: CanvasPortal,
    session: WireSession,
    onDismiss: () -> Unit,
) {
    val c = LocalMaestriColors.current
    var media by remember(portal.portalId) { mutableStateOf<SnapshotMedia.Result?>(null) }
    var loadingImage by remember(portal.portalId) { mutableStateOf(true) }

    // Data-driven revalidation only: the id (a new portal), the page's currentURL and the
    // runtime are the things that change when the host acts on the portal — the ETag then
    // decides whether bytes move. Never a blind refetch.
    LaunchedEffect(portal.portalId, portal.currentURL, portal.runtime) {
        loadingImage = true
        media = SnapshotMedia.fetch(
            session = session,
            path = "api/portals/${portal.portalId}/snapshot",
            cacheKey = "portal:${portal.portalId}",
        )
        loadingImage = false
    }

    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(640.dp),
            shape = cardShape(),
            colors = CardDefaults.cardColors(containerColor = c.canvasSurface),
            border = BorderStroke(1.dp, c.canvasChromeElevated),
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(portal.name.ifBlank { node.title }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            (portal.currentURL ?: portal.url)?.takeIf { it.isNotBlank() } ?: "no url",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append(portal.runtime?.let { "runtime $it" } ?: "runtime —")
                        portal.status?.let { append(" · status $it") }
                        if (portal.isUnloaded) append(" · unloaded")
                        if (portal.chromeHidden) append(" · chrome hidden")
                        portal.runtimeDetail?.let { append(" · $it") }
                        portal.deviceName?.let { dn -> append(" · device $dn " + (portal.devicePlatform ?: "")) }
                        append(" · history back ${if (portal.canGoBack) "yes" else "no"} / fwd ${if (portal.canGoForward) "yes" else "no"}; the write verbs are awaiting confirmation, not buttons")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = Palette.StoneGray,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .background(c.canvasChromeElevated, controlShape())
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val m = media) {
                        null, is SnapshotMedia.Result.Fresh -> {
                            val bytes = (m as? SnapshotMedia.Result.Fresh)?.bytes
                            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                            if (loadingImage && bmp == null) {
                                Text("fetching snapshot…", style = monoHud(11, color = Palette.StoneGray))
                            } else if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "portal snapshot — ${portal.name}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text("snapshot unavailable — no live image decoded", style = monoHud(11, color = Palette.StoneGray))
                            }
                        }
                        SnapshotMedia.Result.Unavailable -> Text(
                            "no live image — the portal is unloaded or hasn't rendered yet",
                            style = monoHud(11, color = Palette.StoneGray),
                        )
                        is SnapshotMedia.Result.Failure -> Text(
                            "snapshot failed: ${m.message}",
                            style = monoHud(11, color = Palette.StoneGray),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnknownNodeBox(
    node: CanvasNode,
    selected: Boolean,
    compact: Boolean = false,
    session: WireSession,
    wsId: String,
    onOpenNote: (wsId: String, nodeId: String) -> Unit,
) {
    val isNote = compact && node.kind == "note"
    Box(
        Modifier
            .zIndex(node.zIndex.toFloat())
            .offset(x = node.frame.x.dp, y = node.frame.y.dp)
            .size(node.frame.width.dp, node.frame.height.dp)
    ) {
        SelectionRing(selected, Modifier.fillMaxSize())
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = cardShape(),
            colors = CardDefaults.cardColors(
                containerColor = if (isNote) lerp(LocalMaestriColors.current.card, NoteYellow, 0.14f)
                else LocalMaestriColors.current.canvasSurface.copy(alpha = 0.92f)
            ),
            border = BorderStroke(
                if (selected) 2.dp else 1.dp,
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    isNote -> NoteYellow.copy(alpha = 0.40f)   // v2.1 blip.note ring (note 40%)
                    else -> LocalMaestriColors.current.canvasChromeElevated
                },
            ),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    node.title.ifBlank { node.kind },
                    color = if (isNote) NoteYellow else Color.Unspecified,
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
                        if (node.isNodeLocked) "(locked on your computer · kind ${node.kind})"
                        else "(kind ${node.kind})",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        node.noteStack?.let { stack ->
            Box(Modifier.align(Alignment.BottomCenter)) {
                NoteStackRailHost(session = session, wsId = wsId, stack = stack, onOpenNote = onOpenNote)
            }
        }
    }
}

// ============================================================================ design-v2 modern
// (todo #31 — the 04 Canvas pushed frame: Stack ⇄ Board; stack = single-column
// cards + board preview strip; board keeps the full spatial stage behind a
// floating 7-pill glass thumb toolbar.)

/** Canvas page states: Stack is primary (spec §4); Board = the full spatial stage. */
private enum class CanvasViewMode { Stack, Board }

/** Canvas host palette literals — surviving contracts, verbatim hue (spec §2/§4). */
private val RopeInk = Color(0xFFF0A65E)      // rope #F0A65E
private val LiveGreen = Color(0xFF9ECE6A)    // v2.1 --tn-green: live blips (frame 04)
private val NoteYellow = Color(0xFFF5D142)   // host note-yellow #F5D142 (.12 fill / .35 ring rendered)

/** Track for the Stack⇄Board segmented: card-2 lift, hairline ring, 3px inner padding, 44pt tall. */
@Composable
private fun CanvasViewSegmented(
    mode: CanvasViewMode,
    onStack: () -> Unit,
    onBoard: () -> Unit,
) {
    Row(
        Modifier
            .height(44.dp)
            .background(Palette.ScreenLift, RoundedCornerShape(MaestriRadii.Control))
            .border(1.dp, Palette.DarkWarm, RoundedCornerShape(MaestriRadii.Control))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedSpan(CanvasStrings.stackMode, mode == CanvasViewMode.Stack, onStack)
        SegmentedSpan(CanvasStrings.boardMode, mode == CanvasViewMode.Board, onBoard)
    }
}

@Composable
private fun SegmentedSpan(label: String, active: Boolean, onClick: () -> Unit) {
    // Active segment = ivory-on-charcoal (dispatch ruling; captured art) — terracotta stays CTA-only.
    Box(
        Modifier
            .fillMaxHeight()
            .background(
                if (active) Palette.NearBlack else Color.Transparent,
                RoundedCornerShape(MaestriRadii.Control),
            )
            .border(1.dp, if (active) Palette.DarkWarm else Color.Transparent, RoundedCornerShape(MaestriRadii.Control))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Palette.Ivory else Palette.StoneGray,
        )
    }
}

/**
 * The thumb toolbar pill — spec glass = rgba(20,20,19,.72) (#141413 @ .72) with a
 * hairline ring + soft halo. Backdrop blur is not expressible in Compose 1.7, so
 * glass = translucency + ring + halo (wave-3 deviation, everywhere it appears).
 * [active] = terracotta ring + terracotta glyph (the .ipill.ac state), [destructive]
 * = Error Crimson glyph per spec.
 */
@Composable
private fun GlassPill(
    glyph: String,
    label: String,
    active: Boolean,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
    liftOnActive: Boolean = false,   // v2.1 `.press`: the ACTIVE pill lifts 1.06 (frame 01/04)
) {
    val c = LocalMaestriColors.current
    val tint = when {
        !enabled -> c.secondaryLabel.copy(alpha = 0.4f)
        destructive -> Palette.Crimson
        active -> Palette.Terracotta
        else -> c.secondaryLabel
    }
    Box(
        Modifier
            .graphicsLayer { val lift = if (liftOnActive && active) 1.06f else 1f; scaleX = lift; scaleY = lift }
            .size(44.dp)
            .background(Palette.NearBlack.copy(alpha = 0.72f), CircleShape)
            .softHalo(radius = 22.dp, color = if (active) Palette.Terracotta else Palette.NearBlack)
            .border(1.dp, if (active) Palette.Terracotta else Palette.DarkWarm, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 17.sp, color = tint)
    }
}

/** Section overline above the stack cards: real counts + the surviving palette legend. */
@Composable
private fun CanvasStackSummary(canvas: app.maestri.wire.CanvasSnapshot) {
    val floors = canvas.nodes.mapNotNull { it.terminal?.floorName }.distinct().size
    Overline("Nodes · ${canvas.nodes.size} pts · $floors ${if (floors == 1) "floor" else "floors"} · rope #F0A65E · note #F5D142")
}

/**
 * Single-column stack card. Terminal nodes = the dev-1 status-card form (vendor dot,
 * name, mono agent/status row, preview tail, floor pill); note nodes = the host
 * note-yellow tint with a serif 500 title + the fichário chip (contract); text nodes
 * = body card; anything else = the simple titled box.
 */
@Composable
private fun StackNodeCard(
    node: CanvasNode,
    snap: FeedSnapshot?,
    selected: Boolean,
    session: WireSession,
    wsId: String,
    onOpenNote: (wsId: String, nodeId: String) -> Unit,
    onClick: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val isNote = node.kind == "note"
    val isText = node.kind == "text" && node.text != null
    val ring = when {
        selected -> 2.dp to c.accent
        isNote -> 1.dp to NoteYellow.copy(alpha = 0.35f)
        else -> 1.dp to Palette.DarkWarm
    }
    val cardBase = Palette.DarkSurface
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                when {
                    isNote -> lerp(cardBase, NoteYellow, 0.14f)
                    else -> cardBase
                },
                RoundedCornerShape(MaestriRadii.Card),
            )
            .warmRing(hairline = ring.second, radius = MaestriRadii.Card)
    ) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
        when {
            node.kind == "terminal" && node.terminal != null -> {
                val t = node.terminal
                val pending = snap?.pendingPromptIds?.contains(t.id) == true
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(
                            vendorColor(t.agentType) ?: Palette.Claude, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.name,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (t.isManager) {
                            Spacer(Modifier.width(6.dp))
                            Text("(maestro)", fontSize = 11.sp, color = c.warning)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(10.dp).background(
                            when {
                                pending || t.needsAttention -> Palette.Crimson
                                t.isActive == true -> Palette.Success
                                t.isLive -> Palette.Warn
                                else -> Palette.StoneGray
                            }, CircleShape))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ((if (t.isRunning) "running" else "stopped") +
                            (if (t.agentType.isNotBlank()) " · ${t.agentType}" else "") +
                            (if (t.isActive == true) " · working" else "") +
                            (if (t.needsAttention) " · attention" else "")),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = Palette.StoneGray,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    t.preview.lastOrNull()?.takeIf { it.isNotBlank() }?.let { line ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = Palette.WarmSilver,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    t.floorName?.let { fn ->
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.background(Palette.ScreenLift, pillShape()).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            Text(fn, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Palette.StoneGray)
                        }
                    }
                }
            }
            isNote -> {
                Column {
                    // Example-faithful: serif 500 title (note-yellow ink) + fichário chip inline right.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            node.title.ifBlank { "note" },
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Medium,   // spec: serif @ 500 only
                            fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = NoteYellow,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.border(1.dp, NoteYellow.copy(alpha = 0.30f), pillShape()).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            Text(CanvasStrings.fichárioChip, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = NoteYellow)
                        }
                    }
                    node.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            fontSize = 12.5.sp,
                            lineHeight = 12.5.sp * 1.55f,
                            color = Palette.WarmSilver,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            isText -> {
                Text(
                    node.text?.text ?: node.title,
                    fontSize = 13.sp,
                    color = Palette.Ivory,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            node.kind == "portal" && node.portal != null -> {
                val portal = node.portal!!
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            portal.name.ifBlank { node.title },
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(10.dp).background(portalDotColor(portal), CircleShape))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (portal.runtime?.let { "portal · runtime $it" } ?: "portal") +
                            (portal.status?.let { " · $it" } ?: "") +
                            (if (portal.isUnloaded) " · unloaded" else ""),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = Palette.StoneGray,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    (portal.currentURL ?: portal.url)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = Palette.WarmSilver,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            else -> {
                Column {
                    Text(
                        node.title.ifBlank { node.kind },
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    node.subtitle?.let {
                        Text(it, fontSize = 11.sp, color = Palette.StoneGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (node.isNodeLocked) "locked on your computer · ${node.kind}" else node.kind,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Palette.StoneGray,
                    )
                }
            }
        }
        }
        node.noteStack?.let { stack ->
            NoteStackRailHost(session = session, wsId = wsId, stack = stack, onOpenNote = onOpenNote)
        }
    }
}

/**
 * Board preview strip (stack state bottom): 20px grid, node blips scaled from real
 * frames, rope curves between node centers (dashed when the host sends no points),
 * non-interactive — it previews the board, it is not the board. Spec 20-pt rhythm
 * (the mock's 24px grid was a mock-local value; the spec + dispatch say 20).
 */
@Composable
private fun CanvasPreviewStrip(canvas: app.maestri.wire.CanvasSnapshot) {
    val stripH = 172.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(stripH)
            .warmRing(hairline = Palette.DarkWarm, radius = MaestriRadii.Card)
            .background(Palette.ScreenLift, RoundedCornerShape(MaestriRadii.Card))
            .clip(RoundedCornerShape(MaestriRadii.Card))
    ) {
        val w = maxWidth.value
        val h = stripH.value
        val padDp = 16f
        val bounds = canvasBoundsOf(canvas)
        val cw = bounds.width.coerceAtLeast(1f)
        val ch = bounds.height.coerceAtLeast(1f)
        val scale = minOf((w - 2f * padDp) / cw, (h - 2f * padDp) / ch).coerceIn(0.02f, 0.5f)
        val offX = (w - cw * scale) / 2f - bounds.left * scale
        val offY = (h - ch * scale) / 2f - bounds.top * scale
        val byId = remember(canvas) { canvas.nodes.associateBy { it.id } }
        val gridColor = Palette.WarmSilver.copy(alpha = 0.05f)

        // 20px grid + rope curves BEHIND the blips.
        Canvas(Modifier.fillMaxSize()) {
            val step = 20.dp.toPx()
            var gx = 0f
            while (gx <= size.width) {
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += step
            }
            var gy = 0f
            while (gy <= size.height) {
                drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += step
            }
            fun bx(v: Double) = (v * scale + offX) * density   // strip dp → px
            fun byy(v: Double) = (v * scale + offY) * density
            canvas.connections.forEach { conn ->
                val from = byId[conn.fromNodeId]?.frame ?: return@forEach
                val to = byId[conn.toNodeId]?.frame ?: return@forEach
                val x0 = bx(from.centerX).toFloat()
                val y0 = byy(from.centerY).toFloat()
                val x1 = bx(to.centerX).toFloat()
                val y1 = byy(to.centerY).toFloat()
                val dx = x1 - x0
                val dy = y1 - y0
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                // perpendicular bow — the mock's graceful rope curve
                val cxp = (x0 + x1) / 2f - dy / len * (len * 0.12f)
                val cyp = (y0 + y1) / 2f + dx / len * (len * 0.12f)
                val path = Path().apply {
                    moveTo(x0, y0)
                    quadraticBezierTo(cxp, cyp, x1, y1)
                }
                when {
                    conn.isActive -> {
                        drawPath(path, RopeInk.copy(alpha = 0.30f), style = Stroke(width = 5f, cap = StrokeCap.Round))
                        drawPath(path, RopeInk, style = Stroke(width = 2f, cap = StrokeCap.Round))
                    }
                    conn.points.isEmpty() -> drawPath(
                        path, Palette.WarmSilver.copy(alpha = 0.55f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f))),
                    )
                    else -> drawPath(
                        path, Palette.WarmSilver.copy(alpha = 0.55f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Pinned blips: real frames scaled into the strip (min readable size; clipped).
        canvas.nodes.forEach { n ->
            val bw = (n.frame.width * scale).toFloat().coerceAtLeast(30f)
            val bh = (n.frame.height * scale).toFloat().coerceAtLeast(20f)
            val x = (n.frame.x * scale).toFloat() + offX
            val y = (n.frame.y * scale).toFloat() + offY
            val isNote = n.kind == "note"
            Box(
                Modifier
                    .offset(x = x.dp, y = y.dp)
                    .size(bw.dp, bh.dp)
                    .background(
                        if (isNote) lerp(Palette.DarkSurface, NoteYellow, 0.14f) else Palette.DarkSurface,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        1.dp,
                        if (isNote) NoteYellow.copy(alpha = 0.35f) else Palette.DarkWarm,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n.kind == "terminal" && n.terminal != null) {
                            Box(Modifier.size(5.dp).background(vendorColor(n.terminal.agentType) ?: Palette.Claude, CircleShape))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            n.title.ifBlank { n.kind },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.5.sp,
                            color = if (isNote) NoteYellow else Palette.Ivory,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isNote) {
                        n.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = NoteYellow.copy(alpha = 0.70f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        n.terminal?.let { t ->
                            Text(
                                (if (t.isActive == true) "working" else if (t.isLive) "live" else if (t.isRunning) "running" else "stopped") +
                                    (if (t.agentType.isNotBlank()) " · ${t.agentType}" else ""),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = Palette.StoneGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Canvas-space union bounds for the strip's fit math (host bounds when present). */
private fun canvasBoundsOf(canvas: app.maestri.wire.CanvasSnapshot): Rect {
    val b = canvas.bounds
    if (b != null && b.width > 0 && b.height > 0) {
        return Rect(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
    }
    return canvas.nodes
        .map { Rect(it.frame.x.toFloat(), it.frame.y.toFloat(), (it.frame.x + it.frame.width).toFloat(), (it.frame.y + it.frame.height).toFloat()) }
        .reduceOrNull { a, r ->
            Rect(minOf(a.left, r.left), minOf(a.top, r.top), maxOf(a.right, r.right), maxOf(a.bottom, r.bottom))
        }
        ?: Rect(0f, 0f, 800f, 600f)
}

@Composable
private fun NoteStackRailHost(
    session: WireSession,
    wsId: String,
    stack: FicharioState,
    onOpenNote: (wsId: String, nodeId: String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(stack.stackNodeId) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Main).consume()
                }
            },
    ) {
        FicharioTabRailInline(session, wsId, stack) { noteId -> onOpenNote(wsId, noteId) }
    }
}

/** #RRGGBB → Color, verbatim hex; anything malformed → fallback. */
private fun parseColor(raw: String?, fallback: Color): Color {
    if (raw == null || raw.length != 7 || raw[0] != '#') return fallback
    val v = runCatching { raw.substring(1).toLong(16) }.getOrNull() ?: return fallback
    return if (v > 0xFFFFFFL || v < 0L) fallback else Color(0xFF000000L or v)
}