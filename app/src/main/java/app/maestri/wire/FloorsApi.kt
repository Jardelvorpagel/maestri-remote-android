package app.maestri.wire

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Floors + git file-tree API (P3 full-parity lane). Truth:
 *  - vendor contract wire-docs.txt §"Andares" + §"Árvores de arquivos"
 *  - knowledge/sources/2026-10-16-maestri-wire-write-schemas.md §3 (dynamic probes, marked)
 *  - disposable-sandbox probes /tmp/floorslane/probe*.jsonl (2026-10-16, P3 lane)
 *
 * Route map (all owner, Bearer):
 *  GET  …/{ws}/branches                          {isGitRepository, current?, branches, canCopyIndependently?}
 *  GET  …/{ws}/feed                              floors[] + pendingFloors[] inside the snapshot
 *  POST …/{ws}/floors {name, branch?, existingBranch?, gitIsolation?, copyGround?, independentCopy?, pullRequest?}
 *       → {ok, floorId, branch?, isGitIsolated, isPending?}
 *  DELETE …/{ws}/floors/pending/{id}             409 while copying / 404 "No such pending floor" after landing
 *  GET …/{ws}/floors/{id}/landing?target=        FloorLanding (preview)
 *  POST …/{ws}/floors/{id}/land {targetBranch?, deleteBranch?, keepFloor?} → {ok, branch, mergedInto?}
 *  POST …/{ws}/floors/{id}/unload                {ok} (ground accepted, literal "ground")
 *  DELETE …/{ws}/floors/{id} {deleteBranch, force?} {ok}
 *  POST …/{ws}/floors/{id}/update {name}         {ok}
 *  POST …/{ws}/floors/{id|ground}/status {status|null} {ok}  (key REQUIRED, "{}"=400)
 *  GET  …/{ws}/pull-requests?query=              {repository, pullRequests:[{number, title?, …}]}
 *       409 host's reason when nothing can list (provider-less — observed live)
 *  GET  …/{ws}/floors/{id}/pull-request/context  PrContext (floor variant)
 *  POST …/{ws}/floors/{id}/pull-request {base,title,body,draft?} → {url?, openedOnWebsite}
 *       503 "unavailable" provider-less w/ host's text (observed live)
 *  POST …/{ws}/floors/{id}/pull-request/merge {method, recordId?}
 *  POST …/{ws}/floors/{id}/pull-request/remove {confirmExtraWork?, recordId?} → {ok, branch, mergedInto?}
 *  POST …/{ws}/floors/{id}/pull-request/push {recordId?}
 *  Delete …/{ws}/floors/{id}/pull-request        (forget one)
 *  GET  …/{ws}/nodes/{ft}/tree?path=&hidden=     FileTreeListing
 *  GET  …/{ws}/nodes/{ft}/tree/file?path=        raw bytes (Range ok; 64 MB beyond own disk → 413)
 *  GET  …/{ws}/nodes/{ft}/tree/changes           FileTreeChanges
 *  GET  …/{ws}/nodes/{ft}/tree/diff?path=&scope= FileTreeDiff (scope staged|unstaged|combined)
 *  GET  …/{ws}/nodes/{ft}/tree/search?query=&hidden=  FileTreeSearch (≤200, isTruncated)
 *  GET  …/{ws}/nodes/{ft}/tree/git               FileTreeGitOverview
 *  POST …/{ws}/nodes/{ft}/tree/git {action, …}   FileTreeGitResult {ok, output} — output = git's own words
 *  POST …/{ws}/nodes {kind:"fileTree", path, floorId?} → {ok, nodeId}
 *
 * Convention honored: optional fields OMITTED, never null; open enums parsed tolerantly.
 */

// ---------------------------------------------------------------------- floors summary

/**
 * One row of the feed's `floors` array (térreo included: id null). Superset of Models' FloorMeta:
 * the P3 fields branch/isGitIsolated/isCloneMissing/isRemoving cloneKind/seeding/folder live here.
 */
data class FloorRow(
    val id: String?,                 // absent/null on ground
    val name: String,
    val isActive: Boolean,
    val branch: String?,             // ground carries its branch too (observed)
    val isGitIsolated: Boolean,
    val isCloneMissing: Boolean,
    val isRemoving: Boolean,
    val cloneKind: String?,          // worktree | copy | (simple: absent)
    val seeding: String?,            // copyOnWrite
    val folder: String?,             // absolute path of the floor's clone (observed)
    val statusState: String?,        // FloorStatus open string: working|blocked|review|done|unknown
    val statusText: String?,
    val statusProgress: Double?,
    val statusAuthor: String?,
    val attentionCount: Int,
    val hasActivity: Boolean,
    val isDormant: Boolean,
) {
    val isGround: Boolean get() = id == null
    companion object {
        fun parse(o: JSONObject): FloorRow {
            val st = o.optJSONObject("status")
            return FloorRow(
                id = if (o.has("id") && !o.isNull("id")) o.getString("id") else null,
                name = o.optString("name", "Floor"),
                isActive = o.optBoolean("isActive", false),
                branch = if (o.has("branch") && !o.isNull("branch")) o.getString("branch") else null,
                isGitIsolated = o.optBoolean("isGitIsolated", false),
                isCloneMissing = o.optBoolean("isCloneMissing", false),
                isRemoving = o.optBoolean("isRemoving", false),
                cloneKind = if (o.has("cloneKind") && !o.isNull("cloneKind")) o.getString("cloneKind") else null,
                seeding = if (o.has("seeding") && !o.isNull("seeding")) o.getString("seeding") else null,
                folder = if (o.has("folder") && !o.isNull("folder")) o.getString("folder") else null,
                statusState = st?.optString("state")?.ifBlank { null },
                statusText = st?.optString("text")?.ifBlank { null },
                statusProgress = st?.optDouble("progress")?.takeIf { !it.isNaN() },
                statusAuthor = st?.optString("author")?.ifBlank { null },
                attentionCount = o.optInt("attentionCount", 0),
                hasActivity = o.optBoolean("hasActivity", false),
                isDormant = o.optBoolean("isDormant", false),
            )
        }
    }
}

/** pendingFloors entry: the still-copying floor behind a pending chip. */
data class PendingFloorRow(
    val id: String,
    val name: String,
    val branch: String?,
    val completedItems: Int,
    val totalItems: Int,
    val error: String?,              // copy failure keeps the chip until dismissed
) {
    companion object {
        fun parse(o: JSONObject): PendingFloorRow = PendingFloorRow(
            id = o.getString("id"),
            name = o.optString("name", ""),
            branch = if (o.has("branch") && !o.isNull("branch")) o.getString("branch") else null,
            completedItems = o.optInt("completedItems", 0),
            totalItems = o.optInt("totalItems", 0),
            error = o.optString("error").ifBlank { null },
        )
    }
}

/** WorkspaceSummary: the pieces the floors UI needs from GET …/{ws}/feed. */
data class FloorsSummary(
    val floors: List<FloorRow>,
    val pendingFloors: List<PendingFloorRow>,
    val groundStatusText: String?,
    val groundFloorBranch: String?,
) {
    companion object {
        fun parse(o: JSONObject): FloorsSummary {
            val floors = o.optJSONArray("floors")?.let { a -> (0 until a.length()).mapNotNull { runCatching { FloorRow.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
            val pending = o.optJSONArray("pendingFloors")?.let { a -> (0 until a.length()).mapNotNull { runCatching { PendingFloorRow.parse(a.getJSONObject(it)) }.getOrNull() } }
            return FloorsSummary(
                floors = floors,
                // Absent array = unavailable (capability), NOT an empty authoritative list (contract).
                pendingFloors = pending ?: emptyList(),
                groundStatusText = o.optJSONObject("groundStatus")?.optString("text")?.ifBlank { null },
                groundFloorBranch = o.optString("groundFloorBranch").ifBlank { null },
            )
        }
    }
}

// ------------------------------------------------------------------------ create/land

data class CreateFloorResult(
    val ok: Boolean,
    val floorId: String?,
    val branch: String?,
    val isGitIsolated: Boolean,
    val isPending: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): CreateFloorResult = CreateFloorResult(
            ok = o.optBoolean("ok", false),
            floorId = o.optString("floorId").ifBlank { null },
            branch = o.optString("branch").ifBlank { null },
            isGitIsolated = o.optBoolean("isGitIsolated", false),
            isPending = o.optBoolean("isPending", false),
        )
    }
}

data class BranchesResult(
    val isGitRepository: Boolean,
    val current: String?,
    val branches: List<String>,
    val canCopyIndependently: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): BranchesResult = BranchesResult(
            isGitRepository = o.optBoolean("isGitRepository", false),
            current = o.optString("current").ifBlank { null },
            branches = o.optJSONArray("branches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            canCopyIndependently = o.optBoolean("canCopyIndependently", true),
        )
    }
}

/** FloorLanding — the landing preview sheet's data (shape observed live, exact). */
data class FloorLanding(
    val branch: String?,
    val groundBranch: String?,
    val target: String?,             // previewed target (as requested, else the floor's branch)
    val isMerge: Boolean,
    val commitCount: Int,
    val files: List<LandingFile>,
    val conflicts: List<String>,
    val branches: List<String>,      // local branches of the project (picker)
) {
    data class LandingFile(val path: String, val additions: Int, val deletions: Int, val isBinary: Boolean) {
        val isAddition: Boolean get() = additions > 0 && additions >= deletions
        companion object {
            fun parse(o: JSONObject) = LandingFile(
                path = o.optString("path"),
                additions = o.optInt("additions", 0),
                deletions = o.optInt("deletions", 0),
                isBinary = o.optBoolean("isBinary", false),
            )
        }
    }
    companion object {
        fun parse(o: JSONObject): FloorLanding = FloorLanding(
            branch = o.optString("branch").ifBlank { null },
            groundBranch = o.optString("groundBranch").ifBlank { null },
            target = o.optString("target").ifBlank { null },
            isMerge = o.optBoolean("isMerge", false),
            commitCount = o.optInt("commitCount", 0),
            files = o.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { runCatching { LandingFile.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
            conflicts = o.optJSONArray("conflicts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            branches = o.optJSONArray("branches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
    }
}

// --------------------------------------------------------------------- pull requests

/** GET /api/workspaces/{ws}/pull-requests — list of the project's open pull requests. */
data class PrListResult(
    val repository: String?,
    val pullRequests: List<PrEntry>,
) {
    data class PrEntry(
        val number: Int,
        val title: String?,
        val author: String?,
        val branch: String?,
        val isDraft: Boolean,
        val isFork: Boolean,
        val url: String?,
        val floorName: String?,   // a floor already follows it
    ) {
        companion object {
            fun parse(o: JSONObject) = PrEntry(
                number = o.optInt("number", 0),
                title = o.optString("title").ifBlank { null },
                author = o.optString("author").ifBlank { null },
                branch = o.optString("branch").ifBlank { null },
                isDraft = o.optBoolean("isDraft", false),
                isFork = o.optBoolean("isFork", false),
                url = o.optString("url").ifBlank { null },
                floorName = o.optString("floorName").ifBlank { null },
            )
        }
    }
    companion object {
        fun parse(o: JSONObject): PrListResult = PrListResult(
            repository = o.optString("repository").ifBlank { null },
            pullRequests = o.optJSONArray("pullRequests")?.let { a -> (0 until a.length()).mapNotNull { runCatching { PrEntry.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
        )
    }
}

/** GET …/floors/{id}/pull-request/context — the prefill + provider state (floor variant). */
data class PrContext(
    val title: String?,
    val body: String?,
    val base: String?,
    val providerName: String?,
    val account: String?,
    val supportsDraft: Boolean,
    val unpushedCommits: Int?,
    val problem: String?,
    val agents: List<PrAgent>,
) {
    data class PrAgent(val id: String, val name: String, val isRunning: Boolean) {
        companion object {
            fun parse(o: JSONObject) = PrAgent(o.optString("id"), o.optString("name"), o.optBoolean("isRunning", false))
        }
    }
    companion object {
        fun parse(o: JSONObject): PrContext = PrContext(
            title = o.optString("title").ifBlank { null },
            body = o.optString("body").ifBlank { null },
            base = o.optString("base").ifBlank { null },
            providerName = o.optString("providerName").ifBlank { null },
            account = o.optString("account").ifBlank { null },
            supportsDraft = o.optBoolean("supportsDraft", false),
            unpushedCommits = if (o.has("unpushedCommits")) o.optInt("unpushedCommits") else null,
            problem = o.optString("problem").ifBlank { null },
            agents = o.optJSONArray("agents")?.let { a -> (0 until a.length()).mapNotNull { runCatching { PrAgent.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
        )
    }
}

// ------------------------------------------------------------------ file tree models

/** FileTreeEntry (dirs first, host's own order; gitStatus = porcelain letter). */
data class FileTreeEntry(
    val name: String,
    val path: String,                // relative to the listing's path root
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val size: Long,
    val modifiedAt: String?,
    val gitStatus: String?,
) {
    companion object {
        fun parse(o: JSONObject): FileTreeEntry = FileTreeEntry(
            name = o.optString("name", ""),
            path = o.optString("path", ""),
            isDirectory = o.optBoolean("isDirectory", false),
            isSymbolicLink = o.optBoolean("isSymbolicLink", false),
            size = o.optLong("size", 0),
            modifiedAt = o.optString("modifiedAt").ifBlank { null },
            gitStatus = o.optString("gitStatus").ifBlank { null },
        )
    }
}

data class FileTreeListing(
    val path: String,                // relative to the tree root
    val name: String,
    val entries: List<FileTreeEntry>,
    val isTruncated: Boolean,
    val gitBranch: String?,
    val changedCount: Int,
    val hasUpstream: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): FileTreeListing {
            val g = o.optJSONObject("git")
            return FileTreeListing(
                path = o.optString("path", ""),
                name = o.optString("name", ""),
                entries = o.optJSONArray("entries")?.let { a -> (0 until a.length()).mapNotNull { runCatching { FileTreeEntry.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
                isTruncated = o.optBoolean("isTruncated", false),
                gitBranch = g?.optString("branch")?.ifBlank { null },
                changedCount = g?.optInt("changedCount", 0) ?: 0,
                hasUpstream = g?.optBoolean("hasUpstream", false) ?: false,
            )
        }
    }
}

data class FileTreeChange(
    val path: String,
    val name: String,
    val directory: String,
    val status: String,              // porcelain letter: M A D R C ? ! U
    val isStaged: Boolean,
    val additions: Int?,
    val deletions: Int?,
) {
    companion object {
        fun parse(o: JSONObject): FileTreeChange = FileTreeChange(
            path = o.optString("path", ""),
            name = o.optString("name", ""),
            directory = o.optString("directory", ""),
            status = o.optString("status", ""),
            isStaged = o.optBoolean("isStaged", false),
            additions = if (o.has("additions")) o.optInt("additions") else null,
            deletions = if (o.has("deletions")) o.optInt("deletions") else null,
        )
    }
}

data class FileTreeChanges(
    val branch: String?,
    val staged: List<FileTreeChange>,
    val unstaged: List<FileTreeChange>,
) {
    companion object {
        fun parse(o: JSONObject): FileTreeChanges = FileTreeChanges(
            branch = o.optString("branch").ifBlank { null },
            staged = o.optJSONArray("staged")?.let { a -> (0 until a.length()).mapNotNull { runCatching { FileTreeChange.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
            unstaged = o.optJSONArray("unstaged")?.let { a -> (0 until a.length()).mapNotNull { runCatching { FileTreeChange.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
        )
    }
}

data class FileTreeDiff(val path: String, val scope: String, val patch: String) {
    companion object {
        fun parse(o: JSONObject): FileTreeDiff = FileTreeDiff(
            path = o.optString("path"),
            scope = o.optString("scope", "combined"),
            patch = o.optString("patch", ""),
        )
    }
}

data class FileTreeSearch(
    val query: String,
    val isTruncated: Boolean,
    val hits: List<FileTreeEntry>,   // entry rows without size/date (contract)
) {
    companion object {
        fun parse(o: JSONObject): FileTreeSearch = FileTreeSearch(
            query = o.optString("query", ""),
            isTruncated = o.optBoolean("isTruncated", false),
            hits = o.optJSONArray("hits")?.let { a -> (0 until a.length()).mapNotNull { runCatching { FileTreeEntry.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
        )
    }
}

/** FileTreeGitOverview — stashes: [{ref, message}], source for git-isolated clones. */
data class FileTreeGitOverview(
    val branch: String?,
    val hasUpstream: Boolean,
    val remotes: List<String>,
    val localBranches: List<String>,
    val remoteBranches: List<String>,   // origin/x form
    val stashes: List<StashRef>,
    val source: SourceRepo?,
) {
    data class StashRef(val ref: String, val message: String) {
        companion object {
            fun parse(o: JSONObject) = StashRef(o.optString("ref"), o.optString("message"))
        }
    }
    data class SourceRepo(val name: String, val branches: List<String>) {
        companion object {
            fun parse(o: JSONObject) = SourceRepo(o.optString("name"), o.optJSONArray("branches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
        }
    }
    companion object {
        fun parse(o: JSONObject): FileTreeGitOverview = FileTreeGitOverview(
            branch = o.optString("branch").ifBlank { null },
            hasUpstream = o.optBoolean("hasUpstream", false),
            remotes = o.optJSONArray("remotes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            localBranches = o.optJSONArray("localBranches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            remoteBranches = o.optJSONArray("remoteBranches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            stashes = o.optJSONArray("stashes")?.let { a -> (0 until a.length()).mapNotNull { runCatching { StashRef.parse(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
            source = o.optJSONObject("source")?.let { runCatching { SourceRepo.parse(it) }.getOrNull() },
        )
    }
}

// --------------------------------------------------------------------------- caching

/**
 * In-memory reuse of one fileTree node per absolute path (created lazily via the canvas
 * nodes route). Re-creating a node on every page open would litter the host canvas; the
 * cache is app-run only (behavior-CANDIDATE: iOS's node persistence across sessions is
 * host-side and unprobed — the host itself re-creates the canvas node per open folder).
 */
object FileTreeNodeCache {
    private val nodes = HashMap<String, String>()
    @Synchronized
    fun get(path: String): String? = nodes[path]
    @Synchronized
    fun put(path: String, nodeId: String) { nodes[path] = nodeId }
}

// ------------------------------------------------------------------------- the client

/** All floors/git file-tree writes + reads. Thin: errors surface as [ApiException]
 *  carrying the HOST's own error text (those texts are client-visible contract — the
 *  provider-less PR problem, the 409 uncommitted-work land refusal, the 409 branch clash: show verbatim). */
class FloorsApi(private val session: app.maestri.wire.session.WireSession) {

    private fun pair(): app.maestri.store.Pairing =
        session.pairing.value ?: throw ApiException(0, "unauthorized", "not paired")

    private suspend fun api(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val p = pair()
        return runCatching {
            session.client.api(p.host, p.port, path, p.token, method, body)
        }.recoverCatching { e ->
            if (e is ApiException && e.status == 401) session.onAuthLost()
            throw e
        }.getOrThrow()
    }

    /** GET feed and return only the floors summary (floors + pendingFloors + ground status). */
    suspend fun floorsSummary(wsId: String): FloorsSummary {
        val o = api("api/workspaces/$wsId/feed")
        return FloorsSummary.parse(o)
    }

    // ------------------------------------------------------------- floor lifecycle

    /** POST …/floors — the New Floor sheet's create. Branch options per contract:
     *  gitIsolation worktree: new branch = send branch (else host slugs the name);
     *  existing branch = existingBranch:true + branch:<picked>;
     *  independentCopy = APFS copy w/ own repo (canCopyIndependently gate).
     *  copyGround = start with Ground's layout (worktree or not). */
    suspend fun createFloor(
        wsId: String,
        name: String,
        gitIsolation: Boolean? = null,
        branch: String? = null,
        existingBranch: Boolean? = null,
        independentCopy: Boolean? = null,
        copyGround: Boolean? = null,
        pullRequest: String? = null,
    ): CreateFloorResult {
        val body = JSONObject().put("name", name)
        fun put(key: String, v: Any?) { if (v != null) body.put(key, v) }
        put("branch", branch)
        put("existingBranch", existingBranch)
        put("gitIsolation", gitIsolation)
        put("copyGround", copyGround)
        put("independentCopy", independentCopy)
        put("pullRequest", pullRequest)
        return CreateFloorResult.parse(api("api/workspaces/$wsId/floors", "POST", body))
    }

    /** DELETE …/floors/pending/{id} — dismiss a pending creation chip.
     *  409 while copying; 404 "No such pending floor" once it landed (benign: refresh). */
    suspend fun dismissPending(wsId: String, pendingId: String): JSONObject =
        api("api/workspaces/$wsId/floors/pending/$pendingId", "DELETE")

    suspend fun unloadFloor(wsId: String, floorId: String): JSONObject =
        api("api/workspaces/$wsId/floors/$floorId/unload", "POST", JSONObject())

    /** POST …/floors/{id}/delete {deleteBranch, force?} — the vendor contract's verb
     *  (HTTP-DELETE on the id 404s in every probe: the doc table is a bug, POST /delete
     *  confirmed 200 + floor gone + branch removed). Never unload first: an unloaded
     *  floor becomes un-routeable (OBSERVED-DELTA, twice) and the route 404s benignly.
     *  409 (clone refuses) → the host's reason; retry the SAME call with force. */
    suspend fun deleteFloor(wsId: String, floorId: String, deleteBranch: Boolean, force: Boolean = false): JSONObject {
        val body = JSONObject().put("deleteBranch", deleteBranch)
        if (force) body.put("force", true)
        return api("api/workspaces/$wsId/floors/$floorId/delete", "POST", body)
    }

    suspend fun renameFloor(wsId: String, floorId: String, name: String): JSONObject {
        val body = JSONObject().put("name", name.trim())
        if (body.optString("name").isBlank()) throw ApiException(400, "invalidRequest", "Empty name")
        return api("api/workspaces/$wsId/floors/$floorId/update", "POST", body)
    }

    /** POST …/floors/{id|ground}/status — replaces the whole {state,text,progress} or clears.
     *  The literal `ground` addresses the térreo. Key is required ({} = 400). */
    suspend fun setFloorStatus(wsId: String, floorIdOrGround: String, status: FloorStatusEdit?): JSONObject {
        val body = JSONObject().put("status", status?.toJson() ?: JSONObject.NULL)
        return api("api/workspaces/$wsId/floors/$floorIdOrGround/status", "POST", body)
    }

    data class FloorStatusEdit(val state: String, val text: String, val progress: Double?) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("state", state)
            put("text", text)
            if (progress != null && !progress.isNaN() && progress in 0.0..1.0) put("progress", progress)
        }
    }

    /** GET …/branches — the New Floor / landing target pickers' source. */
    suspend fun branches(wsId: String): BranchesResult =
        BranchesResult.parse(api("api/workspaces/$wsId/branches"))

    // --------------------------------------------------------------------- landing

    suspend fun landingPreview(wsId: String, floorId: String, target: String?): FloorLanding {
        val q = if (target.isNullOrBlank()) "" else "?target=${android.net.Uri.encode(target)}"
        return FloorLanding.parse(api("api/workspaces/$wsId/floors/$floorId/landing$q"))
    }

    /** POST …/land — 409 carries the host's own refusal words (uncommitted work, merge
     *  conflicts); the host auto-removes the floor on success unless keepFloor.
     *  mergedInto is ABSENT when the target branch was the floor's own (bring + check out,
     *  no merge — probed W3); the caller renders that case differently. */
    suspend fun land(wsId: String, floorId: String, targetBranch: String?, deleteBranch: Boolean, keepFloor: Boolean): LandResult {
        val body = JSONObject()
        if (!targetBranch.isNullOrBlank()) body.put("targetBranch", targetBranch)
        if (deleteBranch) body.put("deleteBranch", true)
        if (keepFloor) body.put("keepFloor", true)
        val o = api("api/workspaces/$wsId/floors/$floorId/land", "POST", body)
        return LandResult(
            ok = o.optBoolean("ok", false),
            branch = o.optString("branch").ifBlank { null },
            mergedInto = o.optString("mergedInto").ifBlank { null },
        )
    }

    data class LandResult(val ok: Boolean, val branch: String?, val mergedInto: String?)

    // -------------------------------------------------------------- pull requests

    /** GET …/pull-requests — provider-less raises 409 with the host's reason (observed). */
    suspend fun prList(wsId: String, query: String?): PrListResult {
        val q = if (query.isNullOrBlank()) "" else "?query=${android.net.Uri.encode(query)}"
        return PrListResult.parse(api("api/workspaces/$wsId/pull-requests$q"))
    }

    suspend fun prContext(wsId: String, floorId: String): PrContext =
        PrContext.parse(api("api/workspaces/$wsId/floors/$floorId/pull-request/context"))

    /** POST …/{floorId}/pull-request {base, title, body, draft?} → {url?, openedOnWebsite}.
     *  503 unavailable (provider-less): the host's text is what the user sees. */
    suspend fun prOpen(wsId: String, floorId: String, base: String, title: String, body: String, draft: Boolean?): PrOpenResult {
        val b = JSONObject().put("base", base).put("title", title).put("body", body)
        if (draft != null) b.put("draft", draft)
        val o = api("api/workspaces/$wsId/floors/$floorId/pull-request", "POST", b)
        return PrOpenResult(o.optString("url").ifBlank { null }, o.optBoolean("openedOnWebsite", false))
    }

    data class PrOpenResult(val url: String?, val openedOnWebsite: Boolean)

    suspend fun prMerge(wsId: String, floorId: String, method: String, recordId: String?): JSONObject {
        val b = JSONObject().put("method", method)
        if (recordId != null) b.put("recordId", recordId)
        return api("api/workspaces/$wsId/floors/$floorId/pull-request/merge", "POST", b)
    }

    suspend fun prPush(wsId: String, floorId: String, recordId: String?): JSONObject {
        val b = JSONObject()
        if (recordId != null) b.put("recordId", recordId)
        return api("api/workspaces/$wsId/floors/$floorId/pull-request/push", "POST", b)
    }

    /** 409 → confirmExtraWork (work the merged PR didn't have) → retry with confirm. */
    suspend fun prRemove(wsId: String, floorId: String, confirmExtraWork: Boolean, recordId: String?): JSONObject {
        val b = JSONObject()
        if (confirmExtraWork) b.put("confirmExtraWork", true)
        if (recordId != null) b.put("recordId", recordId)
        return api("api/workspaces/$wsId/floors/$floorId/pull-request/remove", "POST", b)
    }

    /** task: review|checks|conflicts (assess needs a review floor — not offered here). */
    suspend fun prHandOff(wsId: String, floorId: String, terminalId: String, task: String, note: String?, recordId: String?): JSONObject {
        val b = JSONObject().put("terminalId", terminalId).put("task", task)
        if (!note.isNullOrBlank()) b.put("note", note)
        if (recordId != null) b.put("recordId", recordId)
        return api("api/workspaces/$wsId/floors/$floorId/pull-request/hand-off", "POST", b)
    }

    // ------------------------------------------------------------------ file trees

    /** Lazily create (or reuse) the fileTree canvas node backing a browser session. */
    suspend fun fileTreeFor(wsId: String, absolutePath: String): String {
        FileTreeNodeCache.get(absolutePath)?.let { return it }
        val body = JSONObject().put("kind", "fileTree").put("path", absolutePath).put("mutationId", "p3-${System.nanoTime()}")
        val o = api("api/workspaces/$wsId/nodes", "POST", body)
        val id = o.optString("nodeId")
        if (id.isBlank()) throw ApiException(400, "invalidRequest", "node create returned no id")
        FileTreeNodeCache.put(absolutePath, id)
        return id
    }

    suspend fun treeList(wsId: String, nodeId: String, path: String, hidden: Boolean): FileTreeListing =
        FileTreeListing.parse(api("api/workspaces/$wsId/nodes/$nodeId/tree?path=${android.net.Uri.encode(path)}&hidden=$hidden"))

    suspend fun treeChanges(wsId: String, nodeId: String): FileTreeChanges =
        FileTreeChanges.parse(api("api/workspaces/$wsId/nodes/$nodeId/tree/changes"))

    suspend fun treeDiff(wsId: String, nodeId: String, path: String, scope: String): FileTreeDiff =
        FileTreeDiff.parse(api("api/workspaces/$wsId/nodes/$nodeId/tree/diff?path=${android.net.Uri.encode(path)}&scope=$scope"))

    suspend fun treeSearch(wsId: String, nodeId: String, query: String, hidden: Boolean): FileTreeSearch =
        FileTreeSearch.parse(api("api/workspaces/$wsId/nodes/$nodeId/tree/search?query=${android.net.Uri.encode(query)}&hidden=$hidden"))

    suspend fun treeGitOverview(wsId: String, nodeId: String): FileTreeGitOverview =
        FileTreeGitOverview.parse(api("api/workspaces/$wsId/nodes/$nodeId/tree/git"))

    /** The file's bytes. Text is decoded when the host says text/plain (source/config files). */
    sealed interface FileContent {
        val size: Int
        data class Text(override val size: Int, val content: String) : FileContent
        data class Binary(override val size: Int, val mime: String) : FileContent
    }

    suspend fun treeFile(wsId: String, nodeId: String, path: String): FileContent {
        val p = pair()
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(p.host).port(p.port)
            .addPathSegments("api/workspaces/$wsId/nodes/$nodeId/tree/file")
            .addQueryParameter("path", path).build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${p.token}").get().build()
        val client = session.client.pinned(p.serverKeyHashB64)
        val resp = client.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw parseWireError(r.code, r.body?.string() ?: "")
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val mime = r.header("Content-Type") ?: "application/octet-stream"
            val read = r.header("Content-Range")?.let { cr -> Regex("bytes \\d+-\\d+/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() } ?: bytes.size.toLong()
            // Cap rendered text; huge binaries never enter the composables.
            val limit = 512 * 1024
            if (bytes.size > limit) return FileContent.Binary(bytes.size, mime)
            return if (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" ||
                mime.startsWith("image/image") == false && mime.startsWith("audio/") == false && mime.startsWith("video/") == false &&
                mime.endsWith("/pdf") == false && looksLikeText(bytes)) {
                FileContent.Text(bytes.size, String(bytes, Charsets.UTF_8))
            } else {
                FileContent.Binary(bytes.size, mime)
            }
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var bad = 0
        val n = minOf(bytes.size, 2048)
        for (i in 0 until n) {
            val ub = bytes[i].toInt() and 0xFF
            if (ub == 0) bad += 5
            else if (ub < 0x09) bad += 1
        }
        return bad < n / 20
    }

    /** POST …/tree/git — one NAMED action. Result carries git's own words in `output`. */
    suspend fun gitAction(wsId: String, nodeId: String, action: String, args: JSONObject): GitResult {
        val body = JSONObject(args.toString()).put("action", action)
        val o = api("api/workspaces/$wsId/nodes/$nodeId/tree/git", "POST", body)
        return GitResult(o.optBoolean("ok", true), o.optString("output").ifBlank { null })
    }

    data class GitResult(val ok: Boolean, val output: String?)

    // ------------------------------------------------------------ git action bodies

    fun gitCommit(message: String, stageAll: Boolean) = JSONObject().put("message", message).put("stageAll", stageAll)
    fun gitStage(paths: List<String>) = JSONObject().put("paths", JSONArray(paths))
    fun gitUnstage(paths: List<String>) = JSONObject().put("paths", JSONArray(paths))
    fun gitCheckout(branch: String) = JSONObject().put("branch", branch)
    fun gitCheckoutRemote(branch: String) = JSONObject().put("branch", branch) // origin/x creates the local
    fun gitNewBranch(branch: String) = JSONObject().put("branch", branch)
    fun gitDeleteBranch(branch: String, force: Boolean): JSONObject =
        JSONObject().apply { put("branch", branch); if (force) put("force", true) }
    fun gitDeleteBranchForce(branch: String) = JSONObject().put("branch", branch).put("force", true)
    fun gitMerge(branch: String) = JSONObject().put("branch", branch)
    fun gitStashPush(message: String?) = JSONObject().apply { message?.takeIf { it.isNotBlank() }?.let { put("message", it) } }
    fun gitStashRef(stash: String) = JSONObject().put("stash", stash)
    fun gitPublish(remote: String) = JSONObject().put("remote", remote)
    fun gitFetchFromSource(branch: String) = JSONObject().put("branch", branch)
    fun gitNoArgs() = JSONObject()

    // ---------------------------------------------------------------- extras (P3)

    /** The workspace's absolute working directory — a ground/simple file tree's fallback
     *  root (git-isolated floors carry their own `folder`). */
    suspend fun workingDirectory(wsId: String): String? {
        val o = api("api/workspaces")
        val arr = o.optJSONArray("workspaces") ?: return null
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            if (w.optString("id") == wsId) return w.optString("workingDirectory").ifBlank { null }
        }
        return null
    }
}

