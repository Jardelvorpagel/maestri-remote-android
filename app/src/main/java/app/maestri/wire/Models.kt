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