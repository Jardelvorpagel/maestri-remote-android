package app.maestri.wire

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Canvas write bodies and route paths, exactly as this host accepts them.
 *
 * Authority: the recovered write schemas (2026-10-16). Two rules from that document are load-bearing:
 * optional fields are OMITTED rather than sent as null, and the host SNAPS a moved frame to the
 * 20-pt grid while create-time x/y pass through unsnapped.
 */
object CanvasWrites {

    const val GRID = 20

    /** Optimistic display position of a move: the host will land on this grid anyway. */
    fun snap(v: Float): Float = (v / GRID).roundToInt() * GRID.toFloat()

    /**
     * The ten-name colour vocabulary with the hex this client sends wherever the host wants hex.
     *
     * The host's three colour contracts differ (groups = hex, notes = vocabulary name, text blocks =
     * hex on the text route), so the vocabulary lives here once and the UI reads its swatches from it.
     * Groups and text blocks carry no name on the wire, so these hexes are this client's canonical
     * encoding of the vocabulary rather than a value read back from the host.
     */
    val COLOR_HEX: Map<String, String> = linkedMapOf(
        "yellow" to "#F5D142",
        "pink" to "#F08BB4",
        "blue" to "#6FA8F5",
        "green" to "#7BC47F",
        "orange" to "#F0A65E",
        "purple" to "#B48CF2",
        "white" to "#F2F3F5",
        "charcoal" to "#4A4E57",
        "slate" to "#7C8798",
        "midnight" to "#2B3A55",
    )

    /** Hex for a vocabulary name; unknown names return null so no write is attempted. */
    fun hexFor(name: String?): String? = COLOR_HEX[name?.trim()?.lowercase()]

    /** Vocabulary name for a hex the host reported (it stores names for notes, hex for groups). */
    fun nameForHex(hex: String?): String? {
        val want = hex?.trim()?.uppercase() ?: return null
        if (!want.startsWith("#")) return null
        return COLOR_HEX.entries.firstOrNull { it.value.uppercase() == want }?.key
    }

    private fun point(x: Float, y: Float): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble())

    /** Geometry INSIDE a Drawing is a bare `[x, y]` pair, unlike the `{x, y}` of node positions. */
    private fun pair(x: Float, y: Float): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble())

    // ------------------------------------------------------------------ paths

    fun createNodePath(wsId: String) = "api/workspaces/$wsId/nodes"
    fun nodePath(wsId: String, nodeId: String, op: String) = "api/workspaces/$wsId/nodes/$nodeId/$op"
    fun deleteNodePath(wsId: String, nodeId: String) = "api/workspaces/$wsId/nodes/$nodeId"
    fun nodesPath(wsId: String, op: String) = "api/workspaces/$wsId/nodes/$op"
    fun groupPath(wsId: String, groupId: String, op: String) = "api/workspaces/$wsId/groups/$groupId/$op"
    fun groupsPath(wsId: String, op: String = "") =
        if (op.isEmpty()) "api/workspaces/$wsId/groups" else "api/workspaces/$wsId/groups/$op"
    fun tiePath(wsId: String, id: String, op: String = "") =
        if (op.isEmpty()) "api/workspaces/$wsId/ties/$id" else "api/workspaces/$wsId/ties/$id/$op"
    fun tiesPath(wsId: String) = "api/workspaces/$wsId/ties"
    fun drawingPath(wsId: String, id: String) = "api/workspaces/$wsId/drawings/$id"
    fun drawingsPath(wsId: String) = "api/workspaces/$wsId/drawings"

    // ------------------------------------------------------------------ bodies

    /** `{kind, floorId?, x?, y?, text?, color?, mutationId?}` — create coords are NOT snapped. */
    fun createNode(
        kind: String,
        x: Float,
        y: Float,
        text: String? = null,
        color: String? = null,
        fontFamily: String? = null,
        mutationId: String? = null,
    ): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("x", x.toDouble())
        put("y", y.toDouble())
        text?.let { put("text", it) }
        color?.let { put("color", it) }
        fontFamily?.let { put("fontFamily", it) }
        mutationId?.let { put("mutationId", it) }
    }

    fun frame(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        bringToFront: Boolean? = null,
        mutationId: String? = null,
    ): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width)
        put("height", height)
        bringToFront?.let { put("bringToFront", it) }
        mutationId?.let { put("mutationId", it) }
    }

    fun rename(name: String) = JSONObject().put("name", name)

    fun lock(locked: Boolean) = JSONObject().put("locked", locked)

    /** `{text, fontSize?, fontWeight?, fontFamily?, isMonospaced?, color?}` — size is clamped host-side. */
    fun text(
        text: String,
        fontSize: Int? = null,
        fontWeight: String? = null,
        fontFamily: String? = null,
        color: String? = null,
        isMonospaced: Boolean? = null,
        mutationId: String? = null,
    ): JSONObject = JSONObject().apply {
        put("text", text)
        fontSize?.let { put("fontSize", it) }
        fontWeight?.let { put("fontWeight", it) }
        fontFamily?.let { put("fontFamily", it) }
        color?.let { put("color", it) }
        isMonospaced?.let { put("isMonospaced", it) }
        mutationId?.let { put("mutationId", it) }
    }

    fun batchDelete(nodeIds: List<String>, mutationId: String? = null): JSONObject = JSONObject().apply {
        put("nodeIds", JSONArray().also { arr -> nodeIds.forEach { arr.put(it) } })
        mutationId?.let { put("mutationId", it) }
    }

    fun connection(fromNodeId: String, toNodeId: String, mutationId: String? = null): JSONObject =
        JSONObject().apply {
            put("fromNodeId", fromNodeId)
            put("toNodeId", toNodeId)
            mutationId?.let { put("mutationId", it) }
        }

    fun group(nodeIds: List<String>, name: String? = null, mutationId: String? = null): JSONObject =
        JSONObject().apply {
            put("nodeIds", JSONArray().also { arr -> nodeIds.forEach { arr.put(it) } })
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            mutationId?.let { put("mutationId", it) }
        }

    fun ungroup(nodeIds: List<String>, drawingIds: List<String> = emptyList()): JSONObject = JSONObject().apply {
        put("nodeIds", JSONArray().also { arr -> nodeIds.forEach { arr.put(it) } })
        put("drawingIds", JSONArray().also { arr -> drawingIds.forEach { arr.put(it) } })
    }

    fun ungroupPath(wsId: String) = "api/workspaces/$wsId/ungroup"

    /**
     * Group colour: `{colorHex}` — HEX ONLY (a vocabulary name is answered 400 "Invalid color"),
     * and a real JSON `null` is the confirmed clear form. The key must be spelled `colorHex`: a
     * misnamed key is silently ignored with a 200.
     */
    fun groupColor(colorHex: String?): JSONObject = JSONObject().apply {
        if (colorHex == null) put("colorHex", JSONObject.NULL) else put("colorHex", colorHex)
    }

    /**
     * Single-note colour: `{color}` — the VOCABULARY NAME only (raw hex is answered 400 "Invalid
     * color"), `customColor` rides alongside. There is no accepted clear form on this route.
     */
    fun noteColor(name: String, customColor: String? = null): JSONObject = JSONObject().apply {
        put("color", name)
        customColor?.let { put("customColor", it) }
    }

    /** `{tie: {id, position {x,y}, memberConnectionIds}}` — rope ids in crossing order. */
    fun tie(
        id: String,
        x: Float,
        y: Float,
        memberConnectionIds: List<String>,
        mutationId: String? = null,
    ): JSONObject = JSONObject().apply {
        put(
            "tie",
            JSONObject()
                .put("id", id)
                .put("position", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
                .put("memberConnectionIds", JSONArray().also { arr -> memberConnectionIds.forEach { arr.put(it) } }),
        )
        mutationId?.let { put("mutationId", it) }
    }

    fun position(x: Float, y: Float) = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())

    /**
     * `{drawing: Drawing, floorId?, mutationId?}` — ALL 14 Drawing fields are required and
     * `floorId: "ground"` is rejected outright (omit it for the ground floor).
     *
     * Points are LOCAL to `origin`; `size` is the current extent and `originalSize` the untransformed
     * one (they differ once a drawing is scaled).
     */
    fun drawing(
        id: String,
        tool: String,
        points: List<Pair<Float, Float>>,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        color: String,
        lineWidth: Float,
        opacity: Float = 1f,
        zIndex: Int = 0,
        isLocked: Boolean = false,
        rotation: Float = 0f,
        textContent: String = "",
        textFontSize: Float = 14f,
        originalWidth: Float = width,
        originalHeight: Float = height,
        floorId: String? = null,
        mutationId: String? = null,
    ): JSONObject = JSONObject().apply {
        val d = JSONObject()
            .put("id", id)
            .put("tool", tool)
            .put(
                "points",
                JSONArray().also { arr -> points.forEach { (px, py) -> arr.put(point(px, py)) } },
            )
            .put("origin", pair(originX, originY))
            .put("originalSize", pair(originalWidth, originalHeight))
            .put("size", pair(width, height))
            .put("color", color)
            .put("lineWidth", lineWidth.toDouble())
            .put("opacity", opacity.toDouble())
            .put("zIndex", zIndex)
            .put("isLocked", isLocked)
            .put("rotation", rotation.toDouble())
            .put("textContent", textContent)
            .put("textFontSize", textFontSize.toDouble())
        put("drawing", d)
        if (floorId != null && floorId != "ground") put("floorId", floorId)
        mutationId?.let { put("mutationId", it) }
    }

    /** Drawing ids are echoed as `drawingId` and cannot be reused while the drawing exists (409). */
    fun newDrawingId(): String = UUID.randomUUID().toString().uppercase()

    fun newTieId(): String = UUID.randomUUID().toString().uppercase()

    fun newMutationId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"
}

/** A node frame the client is showing optimistically until the host's snapshot agrees. */
data class NodeFrameOverride(val x: Float, val y: Float, val width: Int, val height: Int)

/**
 * Optimistic canvas state and the correlation bookkeeping around it.
 *
 * Rules taken from the contract and the recovered schemas:
 *  - dedupe/reconcile by `(epoch, sequence)`; a NEW epoch means resync, so every optimistic overlay
 *    is dropped;
 *  - `mutationId` is a correlation label only — it is echoed back in the emitted mutation event, and
 *    it is NOT an idempotency token, so an ambiguous write is never re-sent (see [settleFailed]);
 *  - a host snapshot remains the source of truth: an override is discarded as soon as the snapshot
 *    reports the same frame.
 */
class CanvasOptimism {

    /** nodeId -> frame we are drawing until the host catches up. */
    val frames: SnapshotStateMap<String, NodeFrameOverride> = mutableStateMapOf()

    /** nodeId -> true for nodes removed optimistically (hidden until the snapshot drops them). */
    val removed: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** Writes in flight, by mutationId (kind + when it was sent), for correlation and diagnostics. */
    val pending: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** Mutation ids we deliberately will not repeat (`settleFailed`). */
    private val abandoned = HashSet<String>()

    private var epoch: String? = null

    fun onSnapshot(snap: FeedSnapshot) {
        val e = snap.epoch
        if (epoch != null && epoch != e) {
            clearAll()
        }
        epoch = e
        val nodes = snap.canvas?.nodes.orEmpty().associateBy { it.id }
        // Host agreed with an optimistic frame -> stop overriding it.
        frames.keys.toList().forEach { id ->
            val host = nodes[id]
            val mine = frames[id] ?: return@forEach
            if (host == null) return@forEach
            val same = kotlin.math.abs(host.frame.x - mine.x.toDouble()) < 1.0 &&
                kotlin.math.abs(host.frame.y - mine.y.toDouble()) < 1.0 &&
                host.frame.width == mine.width.toDouble() &&
                host.frame.height == mine.height.toDouble()
            if (same) frames.remove(id)
        }
        // Host no longer lists an optimistically removed node -> drop the placeholder.
        removed.keys.toList().forEach { id -> if (nodes[id] == null) removed.remove(id) }
    }

    /** Echo of our own write: reconcile without ever re-sending it. */
    fun onMutation(m: MutationEvent) {
        val id = m.mutationId ?: return
        pending.remove(id)
        m.nodeId?.let { nodeId ->
            when (m.kind) {
                "nodeMoved" -> frames.remove(nodeId)
                "nodeRemoved" -> removed.remove(nodeId)
            }
        }
    }

    fun beginMove(nodeId: String, mutationId: String, frame: NodeFrameOverride) {
        pending[mutationId] = "move"
        frames[nodeId] = frame
    }

    fun beginRemove(nodeIds: List<String>, mutationId: String) {
        pending[mutationId] = "remove"
        nodeIds.forEach { removed[it] = true }
    }

    /**
     * A write failed. The overlay is reverted and the mutation id retired: the outcome is ambiguous
     * only if the request may have landed, and re-sending is explicitly forbidden, so the user is
     * told instead of us guessing. Returns true when this write had already been settled.
     */
    fun settleFailed(mutationId: String, nodeIds: List<String> = emptyList()): Boolean {
        val known = pending.containsKey(mutationId)
        pending.remove(mutationId)
        abandoned.add(mutationId)
        nodeIds.forEach { removed.remove(it) }
        return known
    }

    fun wasAbandoned(mutationId: String): Boolean = abandoned.contains(mutationId)

    fun clearAll() {
        frames.clear()
        removed.clear()
        pending.clear()
        epoch = null
    }
}
