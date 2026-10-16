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
            )
        }
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
 */
data class CanvasSnapshot(
    val origin: CanvasPoint,
    val zoom: Double,
    val bounds: CanvasRect?,
    val nodes: List<CanvasNode>,
    val connections: List<CanvasConnection>,
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
            )
        }
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
) {
    fun dedupeKey(): String = "$epoch/$sequence"

    companion object {
        fun parse(o: JSONObject): MutationEvent? {
            val m = o.optJSONObject("mutation") ?: return null
            return MutationEvent(
                mutationId = if (m.has("mutationId")) m.getString("mutationId") else null,
                sequence = m.optLong("sequence", -1L),
                epoch = m.optString("epoch", ""),
                kind = m.optJSONObject("kind")?.optString("kind") ?: "",
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