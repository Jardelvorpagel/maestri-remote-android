package app.maestri.wire

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maestri Wire models, parsed defensively with org.json.
 *
 * Protocol conventions honored here:
 *  - Optional fields are OMITTED from the host, never null: every optional is a nullable
 *    Kotlin property and must be read with .opt* accessors.
 *  - UUIDs are UPPERCASE strings and are repeated literally (we never rebuild them).
 *  - Enums (item `kind`, terminal `status`, etc.) are open strings: unknown values become
 *    a generic representation, never a parsing failure.
 *  - Unknown JSON members are ignored.
 */

class ApiException(
    val status: Int,
    val code: String?,
    override val message: String
) : Exception(message)

fun parseWireError(status: Int, body: String?): ApiException {
    val obj = runCatching { body?.let { JSONObject(it) } }.getOrNull()
    val err = obj?.optJSONObject("error")
    return ApiException(
        status = status,
        code = err?.optString("code"),
        message = err?.optString("message") ?: "HTTP $status"
    )
}

// ----------------------------------------------------------------------------- /api/info

data class ServerInfo(
    val name: String,
    val protocolVersion: Int,
    val capabilities: Set<String>,
    val requiresPassword: Boolean,
    val activeWorkspaceId: String?,
    val role: String?,
    val hosts: List<String>,
) {
    fun has(cap: String) = capabilities.contains(cap)

    companion object {
        fun parse(o: JSONObject): ServerInfo {
            val caps = mutableListOf<String>()
            val capsArr = o.optJSONArray("capabilities") ?: JSONArray()
            for (i in 0 until capsArr.length()) caps.add(capsArr.optString(i))
            return ServerInfo(
                name = o.optString("name", ""),
                protocolVersion = o.optInt("protocolVersion", 0),
                capabilities = caps.toSet(),
                requiresPassword = o.optBoolean("requiresPassword", false),
                activeWorkspaceId = if (o.has("activeWorkspaceId")) o.getString("activeWorkspaceId") else null,
                role = if (o.has("role")) o.getString("role") else null,
                hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
    }
}

// -------------------------------------------------------------------- GET /api/workspaces

data class WorkspaceMeta(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val isPinned: Boolean,
    val terminalCount: Int,
    val runningTerminalCount: Int,
    val attentionCount: Int,
    val hasActivity: Boolean,
    val isLoaded: Boolean,
    val activeFloorId: String?,
    val groupName: String?,
    val isLocked: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): WorkspaceMeta = WorkspaceMeta(
            id = o.getString("id"),
            name = o.optString("name", ""),
            icon = if (o.has("icon")) o.getString("icon") else null,
            color = if (o.has("color")) o.getString("color") else null,
            isPinned = o.optBoolean("isPinned", false),
            terminalCount = o.optInt("terminalCount", 0),
            runningTerminalCount = o.optInt("runningTerminalCount", 0),
            attentionCount = o.optInt("attentionCount", 0),
            hasActivity = o.optBoolean("hasActivity", false),
            isLoaded = o.optBoolean("isLoaded", false),
            activeFloorId = if (o.has("activeFloorId") && !o.isNull("activeFloorId"))
                o.getString("activeFloorId") else null,
            groupName = if (o.has("groupName")) o.getString("groupName") else null,
            isLocked = o.optBoolean("isLocked", false),
        )
    }
}

data class FloorMeta(
    val id: String?,          // ground floor: no id (absent in payload)
    val name: String,
    val isActive: Boolean,
    val statusState: String?,  // FloorStatus.state, open string
    val statusText: String?,
    val statusProgress: Double?,
    val attentionCount: Int?,
    val hasActivity: Boolean?,
    val isDormant: Boolean?,
    // ---- P4-A additive (NotificationsRouter): optional fields, appended only.
    val branch: String?,
    val isCloneMissing: Boolean?,
    val pullState: String?,        // FloorPullRequest.state: open | merged | closed
    val pullAttention: String?,    // conflicts | checksFailing | changesRequested | approved | checksRunning
    val pullTitle: String?,
    val pullNumber: Int?,
) {
    companion object {
        fun parse(o: JSONObject): FloorMeta = FloorMeta(
            id = if (o.has("id") && !o.isNull("id")) o.getString("id") else null,
            name = o.optString("name", ""),
            isActive = o.optBoolean("isActive", false),
            statusState = o.optJSONObject("status")?.let { s ->
                if (s.has("state")) s.getString("state") else null
            },
            statusText = o.optJSONObject("status")?.optString("text")?.ifBlank { null },
            statusProgress = o.optJSONObject("status")?.optDouble("progress")?.takeIf { !it.isNaN() },
            attentionCount = if (o.has("attentionCount")) o.optInt("attentionCount") else null,
            hasActivity = if (o.has("hasActivity")) o.optBoolean("hasActivity") else null,
            isDormant = if (o.has("isDormant")) o.optBoolean("isDormant") else null,
            branch = if (o.has("branch") && !o.isNull("branch")) o.optString("branch") else null,
            isCloneMissing = if (o.has("isCloneMissing")) o.optBoolean("isCloneMissing") else null,
            pullState = o.optJSONObject("pullRequest")?.let { pr ->
                if (pr.has("state") && !pr.isNull("state")) pr.optString("state") else null
            },
            pullAttention = o.optJSONObject("pullRequest")?.optString("attention")?.ifBlank { null },
            pullTitle = o.optJSONObject("pullRequest")?.optString("title")?.ifBlank { null },
            pullNumber = o.optJSONObject("pullRequest")?.optInt("number")?.takeIf { it > 0 },
        )
    }
}

/** P4-A additive: a pending floor clone (wire truth: pendingFloors: [{id, name, branch, completedItems, totalItems, error?}]). */
data class PendingFloorMeta(
    val id: String,
    val name: String,
    val branch: String?,
    val completedItems: Int,
    val totalItems: Int,
    val error: String?,
) {
    companion object {
        fun parse(o: JSONObject): PendingFloorMeta = PendingFloorMeta(
            id = o.optString("id"),
            name = o.optString("name"),
            branch = if (o.has("branch") && !o.isNull("branch")) o.optString("branch") else null,
            completedItems = o.optInt("completedItems", 0),
            totalItems = o.optInt("totalItems", 0),
            error = o.optString("error", "").ifBlank { null },
        )
    }
}

// ------------------------------------------------------------------------- FeedSnapshot

data class TerminalCard(
    val id: String,
    val nodeId: String,
    val name: String,
    val agentType: String,
    val icon: String?,
    val color: String?,
    val status: String,          // open string
    val floorId: String?,
    val floorName: String?,
    val isRunning: Boolean,
    val cols: Int,
    val rows: Int,
    val preview: List<String>,   // plain text lines, last lines of the terminal
    val isManager: Boolean,
    val needsAttention: Boolean,
    val isLive: Boolean,
    val isActive: Boolean?,
    val accountName: String?,
    val maestroRequestMessage: String?,  // {message, createdAt} → message only
    // ---- P4-A additive (NotificationsRouter): optional fields, appended only — positional callers unaffected.
    val maestroCreatedAt: String? = null,  // {message, createdAt} → createdAt, maestro dedup key only (default = caller-safe, matches pendingFloors pattern)
    val chatPreview: ChatCardPreview? = null,  // present while the chat face is ON (banked wire truth; default = caller-safe, matches pendingFloors pattern)
) {
    companion object {
        fun parse(o: JSONObject): TerminalCard = TerminalCard(
            id = o.getString("id"),
            nodeId = o.optString("nodeId", ""),
            name = o.optString("name", ""),
            agentType = o.optString("agentType", ""),
            icon = if (o.has("icon")) o.getString("icon") else null,
            color = if (o.has("color")) o.getString("color") else null,
            status = o.optString("status", ""),
            floorId = if (o.has("floorId") && !o.isNull("floorId")) o.getString("floorId") else null,
            floorName = if (o.has("floorName")) o.getString("floorName") else null,
            isRunning = o.optBoolean("isRunning", false),
            cols = o.optInt("cols", 80),
            rows = o.optInt("rows", 24),
            preview = o.optJSONArray("preview")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
            isManager = o.optBoolean("isManager", false),
            needsAttention = o.optBoolean("needsAttention", false),
            isLive = o.optBoolean("isLive", false),
            isActive = if (o.has("isActive")) o.optBoolean("isActive") else null,
            accountName = if (o.has("accountName")) o.getString("accountName") else null,
            maestroRequestMessage = o.optJSONObject("maestroRequest")?.optString("message"),
            maestroCreatedAt = o.optJSONObject("maestroRequest")?.optString("createdAt"),
            chatPreview = o.optJSONObject("chatPreview")?.let { c -> runCatching { ChatCardPreview.parse(c) }.getOrNull() },
        )
    }
}

/**
 * P4-A additive: the terminal-card chat miniatura — at most four recent entries plus an
 * unreadCount when the host is showing the chat (wire truth, Chat do terminal section).
 * Powers the feed-diffed chat notification row WITHOUT opening a second chat stream.
 */
data class ChatCardPreview(
    val entries: List<ChatEntry>,
    val unreadCount: Int?,
) {
    companion object {
        fun parse(c: JSONObject): ChatCardPreview = ChatCardPreview(
            entries = c.optJSONArray("entries")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    val e = a.optJSONObject(idx) ?: return@mapNotNull null
                    runCatching { ChatEntry.parse(e) }.getOrNull()
                }
            } ?: emptyList(),
            unreadCount = if (c.has("unreadCount")) c.optInt("unreadCount") else null,
        )
    }
}

data class NoteCard(
    val nodeId: String,
    val fileName: String,
    val displayName: String,
    val color: String,
    val floorName: String?,
    val preview: List<String>,
) {
    companion object {
        fun parse(o: JSONObject): NoteCard = NoteCard(
            nodeId = o.getString("nodeId"),
            fileName = o.optString("fileName", ""),
            displayName = o.optString("displayName", "").ifBlank { o.optString("fileName", "note") },
            color = o.optString("color", "graphite"),
            floorName = if (o.has("floorName")) o.getString("floorName") else null,
            preview = o.optJSONArray("preview")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------- canvas mirror

// Host canvas geometry is in plain points (a 20 pt grid); parsed as doubles and
// never rounded — the host enforces the grid, we are read-only.
data class CanvasPoint(val x: Double, val y: Double) {
    companion object {
        fun parse(o: JSONObject): CanvasPoint = CanvasPoint(
            x = o.optDouble("x", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            y = o.optDouble("y", 0.0).takeIf { !it.isNaN() } ?: 0.0,
        )
    }
}

data class CanvasRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0

    companion object {
        fun parse(o: JSONObject): CanvasRect = CanvasRect(
            x = o.optDouble("x", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            y = o.optDouble("y", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            width = o.optDouble("width", 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0,
            height = o.optDouble("height", 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0,
        )
    }
}

/**
 * A node on a single floor's canvas (CanvasNode). Per the docs a node has exactly one
 * payload per kind; kinds are open strings — unknown kinds still carry frame + title and
 * render as a simple box. Only the `terminal` payload is consumed in v1; note/text/file/
 * link/fileTree/portal/connector/noteStack payloads are ignored (graceful box).
 *
 * `id` is the NODE id (UUID verbatim); a terminal node's payload re-addresses the
 * TERMINAL id under `.terminal.id` — they are different ids, never rebuild them.
 */
data class CanvasNode(
    val id: String,
    val kind: String,               // open string: terminal | note | text | … | unknown
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val color: String?,             // #RRGGBB verbatim; conversion is a UI concern
    val zIndex: Int,
    val frame: CanvasRect,
    val isNodeLocked: Boolean,
    val groupId: String?,
    val terminal: TerminalCard?,    // present when kind == "terminal" and payload parses
    val text: CanvasTextBlock?,     // present when kind == "text": the styled text payload
    val portal: CanvasPortal?,      // present when kind == "portal": the host-managed card payload
) {
    companion object {
        fun parse(o: JSONObject): CanvasNode {
            val kind = o.optString("kind", "")
            return CanvasNode(
                id = o.getString("id"),
                kind = kind,
                title = o.optString("title", ""),
                subtitle = if (o.has("subtitle")) o.getString("subtitle") else null,
                icon = if (o.has("icon")) o.getString("icon") else null,
                color = if (o.has("color")) o.getString("color") else null,
                zIndex = o.optInt("zIndex", 0),
                frame = o.optJSONObject("frame")?.let { runCatching { CanvasRect.parse(it) }.getOrNull() }
                    ?: CanvasRect(0.0, 0.0, 600.0, 400.0),
                isNodeLocked = o.optBoolean("isNodeLocked", false),
                groupId = if (o.has("groupId") && !o.isNull("groupId")) o.getString("groupId") else null,
                terminal = if (kind == "terminal") o.optJSONObject("terminal")?.let {
                    runCatching { TerminalCard.parse(it) }.getOrNull()
                } else null,
                text = if (kind == "text") o.optJSONObject("text")?.let {
                    runCatching { CanvasTextBlock.parse(it) }.getOrNull()
                } else null,
                portal = if (kind == "portal") o.optJSONObject("portal")?.let {
                    runCatching { CanvasPortal.parse(it) }.getOrNull()
                } else null,
            )
        }
    }
}

/**
 * A kind == "portal" node's payload (corpus §5ter capture + docs §140): a host-managed
 * browser/device card. Read-only from a client: creation answers 400 "Unknown node kind"
 * and the navigate/back/forward/reload/unload writes never probed (CANDIDATE). The
 * `device` payload marks a device portal (no address, snapshot = the device screen);
 * per docs §140 only runtime "ready"/"live" carry a snapshot worth fetching.
 */
data class CanvasPortal(
    val portalId: String,
    val name: String,
    val url: String?,
    val currentURL: String?,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val chromeHidden: Boolean,
    val status: String?,
    val isUnloaded: Boolean,
    val runtime: String?,
    val runtimeDetail: String?,
    val devicePlatform: String?,
    val deviceName: String?,
) {
    companion object {
        fun parse(o: JSONObject): CanvasPortal {
            val device = o.optJSONObject("device")
            return CanvasPortal(
                portalId = o.getString("portalId"),
                name = o.optString("name", ""),
                url = if (o.has("url") && !o.isNull("url")) o.getString("url") else null,
                currentURL = if (o.has("currentURL") && !o.isNull("currentURL")) o.getString("currentURL") else null,
                canGoBack = o.optBoolean("canGoBack", false),
                canGoForward = o.optBoolean("canGoForward", false),
                chromeHidden = o.optBoolean("chromeHidden", false),
                status = o.optString("status").takeIf { it.isNotBlank() },
                isUnloaded = o.optBoolean("isUnloaded", false),
                runtime = o.optString("runtime").takeIf { it.isNotBlank() },
                runtimeDetail = o.optString("runtimeDetail").takeIf { it.isNotBlank() },
                devicePlatform = device?.optString("platform")?.takeIf { it.isNotBlank() },
                deviceName = device?.optString("deviceName")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * The styled payload a `kind == "text"` node carries: the block's content plus its styling.
 *
 * The host hands back only what has been set, so every field but the content is optional: weight and
 * family default to what a fresh block uses ("regular"/"sans") and an unset colour is absent.
 */
data class CanvasTextBlock(
    val text: String,
    val fontSize: Int,
    val fontWeight: String,
    val fontFamily: String,
    val color: String?,
    val isMonospaced: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): CanvasTextBlock = CanvasTextBlock(
            text = o.optString("text", ""),
            fontSize = o.optDouble("fontSize", 14.0).toInt().coerceIn(8, 200),
            fontWeight = o.optString("fontWeight", "regular").takeIf { it.isNotBlank() } ?: "regular",
            fontFamily = o.optString("fontFamily", "sans").takeIf { it.isNotBlank() } ?: "sans",
            color = if (o.has("color") && !o.isNull("color")) o.getString("color") else null,
            isMonospaced = o.optBoolean("isMonospaced", false),
        )
    }
}

/** A rope between two nodes: polyline in canvas coordinates, edge-to-edge (not centers). */
data class CanvasConnection(
    val id: String,                 // UUID verbatim
    val fromNodeId: String,
    val toNodeId: String,
    val kind: String,               // open string: terminal | note | crossFloor | … | unknown
    val isActive: Boolean,
    val points: List<CanvasPoint>,
) {
    companion object {
        fun parse(o: JSONObject): CanvasConnection = CanvasConnection(
            id = o.getString("id"),
            fromNodeId = o.optString("fromNodeId", ""),
            toNodeId = o.optString("toNodeId", ""),
            kind = o.optString("kind", ""),
            isActive = o.optBoolean("isActive", false),
            points = o.optJSONArray("points")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { CanvasPoint.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList(),
        )
    }
}

/**
 * CanvasSnapshot (capability canvasMirroring): exactly one floor's canvas, riding inside
 * every full feed snapshot. `origin`/`zoom` are the HOST viewport — informative only.
 * `bounds` may be absent. cableTies / groups / drawings are deliberately not modeled:
 * unknown members are ignored per contract, and v1 renders none of them, so absent
 * handling = survive. Mutation events carry no canvas state (attribution only), so the
 * snapshot is the sole source of truth for this object.
 *
 * P1a renders and writes drawings and cable ties, so those two members are parsed now;
 * `groups` is still not modeled — a group's identity reaches the client through its
 * members' `groupId`, which is all the group actions here need.
 */
data class CanvasSnapshot(
    val origin: CanvasPoint,
    val zoom: Double,
    val bounds: CanvasRect?,
    val nodes: List<CanvasNode>,
    val connections: List<CanvasConnection>,
    val drawings: List<CanvasDrawing> = emptyList(),
    val cableTies: List<CanvasCableTie> = emptyList(),
) {
    companion object {
        fun parse(o: JSONObject): CanvasSnapshot {
            val nodes = o.optJSONArray("nodes")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { CanvasNode.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList()
            val connections = o.optJSONArray("connections")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { CanvasConnection.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList()
            return CanvasSnapshot(
                origin = o.optJSONObject("origin")?.let { runCatching { CanvasPoint.parse(it) }.getOrNull() }
                    ?: CanvasPoint(0.0, 0.0),
                zoom = o.optDouble("zoom", 1.0).takeIf { !it.isNaN() && it != 0.0 } ?: 1.0,
                bounds = o.optJSONObject("bounds")?.let { runCatching { CanvasRect.parse(it) }.getOrNull() },
                nodes = nodes,
                connections = connections,
                drawings = o.optJSONArray("drawings")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { CanvasDrawing.parse(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList(),
                cableTies = o.optJSONArray("cableTies")?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { CanvasCableTie.parse(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList(),
            )
        }
    }
}

/**
 * A drawing the host serializes with the same 14 fields its create route requires. Points are local
 * to `origin`; `size`/`originalSize` are bare `[w, h]` pairs on the wire (tolerated as `{x, y}`
 * here because earlier captures showed both shapes).
 */
data class CanvasDrawing(
    val id: String,
    val tool: String,
    val points: List<CanvasPoint>,
    val origin: CanvasPoint,
    val size: CanvasPoint,
    val color: String,
    val lineWidth: Double,
    val opacity: Double,
    val zIndex: Int,
    val rotation: Double,
    val isLocked: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): CanvasDrawing = CanvasDrawing(
            id = o.getString("id"),
            tool = o.optString("tool", "pen"),
            // Points are bare [x, y] pairs (the write schema is explicit about this).
            points = o.optJSONArray("points")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    val p = a.optJSONArray(idx) ?: return@mapNotNull null
                    runCatching { CanvasPoint(p.getDouble(0), p.getDouble(1)) }.getOrNull()
                }
            } ?: emptyList(),
            origin = parseVec(o.opt("origin")) ?: CanvasPoint(0.0, 0.0),
            size = parseVec(o.opt("size")) ?: CanvasPoint(0.0, 0.0),
            color = o.optString("color", ""),
            lineWidth = o.optDouble("lineWidth", 3.0),
            opacity = o.optDouble("opacity", 1.0),
            zIndex = o.optInt("zIndex", 0),
            rotation = o.optDouble("rotation", 0.0),
            isLocked = o.optBoolean("isLocked", false),
        )

        /** `[x, y]` or `{x, y}` — both have been seen for drawing vectors. */
        private fun parseVec(v: Any?): CanvasPoint? = when (v) {
            is JSONArray -> runCatching { CanvasPoint(v.getDouble(0), v.getDouble(1)) }.getOrNull()
            is JSONObject -> runCatching { CanvasPoint.parse(v) }.getOrNull()
            else -> null
        }
    }
}

/** A tie knot binding the ropes that cross under it (`memberConnectionIds` in crossing order). */
data class CanvasCableTie(
    val id: String,
    val position: CanvasPoint,
    val memberConnectionIds: List<String>,
) {
    companion object {
        fun parse(o: JSONObject): CanvasCableTie = CanvasCableTie(
            id = o.getString("id"),
            position = o.optJSONObject("position")?.let { runCatching { CanvasPoint.parse(it) }.getOrNull() }
                ?: CanvasPoint(0.0, 0.0),
            memberConnectionIds = o.optJSONArray("memberConnectionIds")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
        )
    }
}

/** FeedItem discriminated by kind: terminal | pendingPrompt | note | (open) */
sealed interface FeedItem {
    val kind: String

    data class TerminalFeedItem(val terminal: TerminalCard) : FeedItem {
        override val kind: String get() = "terminal"
    }

    data class PendingPromptFeedItem(val terminal: TerminalCard, val prompt: String) : FeedItem {
        override val kind: String get() = "pendingPrompt"
    }

    data class NoteFeedItem(val note: NoteCard) : FeedItem {
        override val kind: String get() = "note"
    }

    data class UnknownFeedItem(override val kind: String, val title: String?) : FeedItem
}

data class FeedSnapshot(
    val workspaceId: String,
    val workspaceName: String,
    val workspace: WorkspaceMeta?,
    val floors: List<FloorMeta>,
    val items: List<FeedItem>,
    val epoch: String,
    val receivedOrder: Long,   // monotonic receive counter for HTTP-vs-socket ordering
    val canvas: CanvasSnapshot?,   // capability canvasMirroring: one floor's canvas per snapshot
    // ---- P4-A additive (NotificationsRouter): optional, appended only (default keeps positional callers safe).
    val pendingFloors: List<PendingFloorMeta> = emptyList(),
) {
    /** Terminal + pendingPrompt terminal ids -> all terminal cards in this snapshot. */
    val terminalCards: List<TerminalCard>
        get() = items.mapNotNull {
            when (it) {
                is FeedItem.TerminalFeedItem -> it.terminal
                is FeedItem.PendingPromptFeedItem -> it.terminal
                else -> null
            }
        }

    /** Terminal ids that currently hold a pending prompt. */
    val pendingPromptIds: Set<String>
        get() = items.mapNotNull { (it as? FeedItem.PendingPromptFeedItem)?.terminal?.id }.toSet()

    companion object {
        fun parse(o: JSONObject, order: Long): FeedSnapshot {
            val ws = o.optJSONObject("workspace")
            val floors = o.optJSONArray("floors")?.let { a ->
                (0 until a.length()).map { FloorMeta.parse(a.getJSONObject(it)) }
            } ?: emptyList()

            val items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    val item = a.getJSONObject(idx)
                    val kind = item.optString("kind", "")
                    when (kind) {
                        "terminal" -> item.optJSONObject("terminal")?.let {
                            FeedItem.TerminalFeedItem(TerminalCard.parse(it))
                        }
                        "pendingPrompt" -> item.optJSONObject("terminal")?.let {
                            FeedItem.PendingPromptFeedItem(TerminalCard.parse(it), item.optString("prompt", ""))
                        }
                        "note" -> item.optJSONObject("note")?.let {
                            FeedItem.NoteFeedItem(NoteCard.parse(it))
                        }
                        else -> {
                            // Unknown kinds are "some other kind", not a failure.
                            FeedItem.UnknownFeedItem(
                                    kind,
                                    if (item.has("title") && !item.isNull("title")) item.getString("title") else null
                                )
                        }
                    }
                }
            } ?: emptyList()

            val pending = o.optJSONArray("pendingFloors")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { PendingFloorMeta.parse(a.getJSONObject(idx)) }.getOrNull()
                }
            } ?: emptyList()

            return FeedSnapshot(
                workspaceId = ws?.optString("id", "") ?: "",
                workspaceName = ws?.optString("name", "") ?: "",
                workspace = ws?.let { w ->
                    runCatching { WorkspaceMeta.parse(w) }.getOrNull()
                },
                floors = floors,
                items = items,
                epoch = o.optString("epoch", ""),
                receivedOrder = order,
                canvas = o.optJSONObject("canvas")?.let {
                    runCatching { CanvasSnapshot.parse(it) }.getOrNull()
                },
                pendingFloors = pending,
            )
        }
    }
}

// -------------------------------------------------------------------------- feed socket

/** MutationEvent, kept only for (epoch, sequence) dedupe and epoch-change detection. */
data class MutationEvent(
    val mutationId: String?,
    val sequence: Long,
    val epoch: String,
    val kind: String,
    /** Node the mutation is about, when the kind carries one (nodeMoved/nodeRemoved/…). */
    val nodeId: String? = null,
) {
    fun dedupeKey(): String = "$epoch/$sequence"

    companion object {
        fun parse(o: JSONObject): MutationEvent? {
            val m = o.optJSONObject("mutation") ?: return null
            val kind = m.optJSONObject("kind")
            return MutationEvent(
                mutationId = if (m.has("mutationId")) m.getString("mutationId") else null,
                sequence = m.optLong("sequence", -1L),
                epoch = m.optString("epoch", ""),
                kind = kind?.optString("kind") ?: "",
                nodeId = kind?.optString("nodeId")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

// --------------------------------------------------------------------- terminal stream

data class Geometry(val cols: Int, val rows: Int) {
    companion object {
        fun clamp(cols: Int, rows: Int) = Geometry(
            cols = cols.coerceIn(20, 250),
            rows = rows.coerceIn(5, 120),
        )
    }
}