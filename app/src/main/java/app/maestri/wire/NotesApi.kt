package app.maestri.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notes lane API — editor + fichário writes over the pinned Wire channel.
 * Lane-owned file: the canvas lane owns WireSession.kt; this class wraps a [WireSession]
 * without editing it, and mirrors WireSession's apiGuard rules (persistent 401 → re-pair UX).
 *
 * Wire truth (schema doc `2026-10-16-maestri-wire-write-schemas.md` + P1b sandbox probe,
 * round-tripped on a disposable workspace, teardown 404-verified):
 *  - GET  …/nodes/{id}/note → NoteContent (the editor prefill comes ONLY from here —
 *    snapshot cards carry no content, just `note.preview` first lines).
 *  - PUT  …/nodes/{id}/note {text, ifRevision?} → full NoteContent.
 *      · revision = first 16 hex of SHA-256(UTF-8 text); empty note → sha256("")[:16].
 *      · stale ifRevision → **412 whose BODY is the current NoteContent** (not an error
 *        envelope) — the conflict dialog takes "the computer's version" from that body.
 *      · no ifRevision → unconditional overwrite = iOS "keeping yours" move.
 *      · writing locked content → 409 `conflict` "Note is locked" (local-edit mode).
 *      · naming: EVERY save renames from the first line while no custom name is set
 *        ("alpha docs" → alpha-docs.md, "zeta now" → zeta-now.md; spaces → dashes).
 *  - PUT  …/nodes/{id}/note/lock {locked} → {ok} (idempotent; state travels only via
 *    fetch/feed card — there is NO mutation event for it).
 *  - PUT  …/nodes/{id}/note/color {color} — VOCABULARY NAMES only (10 names); raw hex or
 *    unknown name → 400 "Invalid color"; CLEAR = {"color":""} → 200 (color becomes null);
 *    name+customColor accepted; customColor alone → 400; works while content-locked.
 *  - POST …/nodes/{id}/rename {name} → {ok}; {name:""} → 400.
 *  - Fichário (ALL under the stack NODE id — the docs' workspace-scoped paths 404; probed):
 *      · POST …/note-stacks {nodeIds, floorId?, mutationId?} → {ok, stackNodeId}
 *      · PUT  …/nodes/{stackId}/stack/front {frontNoteId, mutationId?} → {ok, memberNodeIds, frontNoteId}
 *      · PUT  …/nodes/{stackId}/stack/order {memberNodeIds, mutationId?} → permutation echo
 *      · POST …/nodes/{stackId}/stack/pages {afterNoteId?, mutationId?} → {ok, nodeId}
 *      · DELETE …/nodes/{stackId}/stack/pages/{noteId} — body {x, y?} optional LANDING —
 *        → {ok, memberNodeIds, frontNoteId}; the page RETURNS to the canvas; landing body accepted.
 *        Non-member noteId → 409 conflict "Page is not in this fichário" (stale guard).
 *      · PUT  …/nodes/{stackId}/stack/pages/{looseNoteId} {mutationId?} → file a loose note in
 *        ("Add to Fichário" / capability noteStackFiling) → {ok, memberNodeIds, frontNoteId};
 *        already-member → idempotent {ok}.
 *  - Snapshot truth: a stack node carries kind "noteStack" with payload
 *    {name, frontNoteId, front: <note card>, tabs: [{nodeId, title, color}], hasCustomName} —
 *    tabs ARE the rail; the snapshot never carries note TEXT.
 */

sealed interface NoteSaveResult {
    data class Saved(val content: NoteContent) : NoteSaveResult
    data class Conflict(val currentNoteContent: NoteContent) : NoteSaveResult
}

/**
 * NoteContent — exactly the host's shape; optional fields are omitted by the host, never null.
 * `color` is a vocabulary NAME (or null once cleared); `customColor` rides with a name.
 */
data class NoteContent(
    val nodeId: String,
    val fileName: String,
    val displayName: String,
    val text: String,
    val color: String?,
    val customColor: String?,
    val revision: String,
    val lastModifiedAt: String?,
    val isExternal: Boolean,
    val isContentLocked: Boolean,
    val hasCustomName: Boolean,
) {
    companion object {
        fun parse(o: JSONObject): NoteContent = NoteContent(
            nodeId = o.optString("nodeId", o.optString("id", "")),
            fileName = o.optString("fileName", ""),
            displayName = o.optString("displayName", "").ifBlank { o.optString("fileName", "untitled") },
            text = o.optString("text", ""),
            color = if (o.has("color") && !o.isNull("color")) o.optString("color") else null,
            customColor = if (o.has("customColor") && !o.isNull("customColor")) o.optString("customColor") else null,
            revision = o.optString("revision", ""),
            lastModifiedAt = if (o.has("lastModifiedAt")) o.optString("lastModifiedAt") else null,
            isExternal = o.optBoolean("isExternal", false),
            isContentLocked = o.optBoolean("isContentLocked", false),
            hasCustomName = o.optBoolean("hasCustomName", false),
        )
    }
}

/** Echo of the stack member writes: current tab order + (nullable) front note id. */
data class StackEcho(
    val memberNodeIds: List<String>,
    val frontNoteId: String?,
)

/**
 * Tab rail payload of a `noteStack` canvas node, parsed defensively. Snapshot truth (probed):
 * `noteStack.payload = {name, frontNoteId, front: <note card>, tabs: [{nodeId, title, color}]}` —
 * tabs ARE the rail; no per-tab fetch is needed (and the snapshot carries no note TEXT).
 */
data class FicharioState(
    val stackNodeId: String,
    val name: String,
    val frontNoteId: String?,
    val tabs: List<FicharioTab>,
) {
    data class FicharioTab(val nodeId: String, val title: String, val color: String?)

    /** Parse from the raw canvas-node JSON; null when the payload is absent/unparsable. */
    fun parse(nodeJson: JSONObject): FicharioState? {
        val s = nodeJson.optJSONObject("noteStack") ?: return null
        val id = nodeJson.optString("id", "")
        if (id.isBlank()) return null
        val tabs = s.optJSONArray("tabs")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                FicharioTab(
                    nodeId = t.optString("nodeId", ""),
                    title = t.optString("title", ""),
                    color = if (t.has("color") && !t.isNull("color")) t.optString("color") else null,
                )
            }
        } ?: emptyList()
        return FicharioState(
            stackNodeId = id,
            name = s.optString("name", "Fichário"),
            frontNoteId = if (s.has("frontNoteId") && !s.isNull("frontNoteId")) s.optString("frontNoteId") else null,
            tabs = tabs,
        )
    }
}

class NotesApi(private val session: app.maestri.wire.session.WireSession) {

    private fun guard(): app.maestri.store.Pairing {
        val p = session.pairing.value ?: throw ApiException(0, "unauthorized", "not paired")
        return p
    }

    // ------------------------------------------------------------------- raw channel

    /**
     * Raw HTTP over the pinned channel. Unlike WireClient.api this returns the status for
     * body-bearing error statuses, so the editor can render the conflict dialog from the
     * 412's NoteContent body, and 409 "Note is locked" can flip the editor into local mode.
     * 401 still surfaces to the session (persistent 401 → re-pair UX).
     */
    private suspend fun raw(
        pairing: app.maestri.store.Pairing,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): RawResponse = withContext(Dispatchers.IO) {
        val ok: OkHttpClient = session.client.pinned(pairing.serverKeyHashB64)
        val url: HttpUrl = HttpUrl.Builder()
            .scheme("https").host(pairing.host).port(pairing.port)
            .addPathSegments(path.trimStart('/'))
            .build()
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${pairing.token}")
        val media = "application/json; charset=utf-8".toMediaType()
        when (method.uppercase()) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(media))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(media))
            "DELETE" ->
                // Bodyless DELETE is the probe-verified shape (stack/pages/{id} no-landing);
                // OkHttp needs method("DELETE", null) to send nothing at all.
                if (body == null) builder.method("DELETE", null)
                else builder.delete(body.toString().toRequestBody(media))
            else -> builder.get()
        }
        ok.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val obj = runCatching { JSONObject(text) }.getOrNull()
            if (resp.code == 401) {
                session.onAuthLost()
                throw ApiException(401, obj?.optJSONObject("error")?.optString("code") ?: "unauthorized",
                    obj?.optJSONObject("error")?.optString("message") ?: "Unauthorized")
            }
            RawResponse(resp.code, obj, runCatching { JSONArray(text) }.getOrNull(), text)
        }
    }

    /** Lane error taxonomy (status + the host's code/message); thrown to UI mappers. */
    class NotesApiException(val status: Int, val code: String?, override val message: String) : Exception(message)

    class RawResponse(
        val status: Int,
        val jsonObject: JSONObject?,
        val jsonArray: JSONArray?,
        val raw: String,
    ) {
        /**
         * Body-bearing error taxonomy. Hosts emit either an error envelope
         * ({"error":{code,message}} — "Note is locked", "Page is not in this fichário",
         * "Invalid color") or a bare string ({"error":"conflict"}); surface both.
         */
        fun errorPayload(): NotesApiException? {
            if (status in 200..299) return null
            val err = jsonObject?.optJSONObject("error")
            val bare = jsonErrorString()
            val code = err?.optString("code") ?: bare ?: when (status) {
                412, 409 -> "conflict"
                else -> null
            }
            val message = err?.optString("message") ?: bare ?: "HTTP $status"
            return NotesApiException(status, code, message)
        }

        private fun jsonErrorString(): String? {
            val e = jsonObject?.opt("error") ?: return null
            return (e as? String)?.takeIf { it.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------- note content

    /** GET …/nodes/{id}/note — the ONLY source of note text (cards carry preview lines only). */
    suspend fun fetchNote(wsId: String, nodeId: String): NoteContent {
        val p = guard()
        val resp = raw(p, "api/workspaces/$wsId/nodes/$nodeId/note")
        resp.errorPayload()?.let { throw it }
        return NoteContent.parse(resp.jsonObject ?: JSONObject())
    }

    /**
     * PUT …/nodes/{id}/note {text, ifRevision?} — the 412 dance.
     * @param ifRevision the revision this editor state is based on (from GET or the last echo);
     *   null → unconditional overwrite = iOS "keeping yours replaces the version on your computer".
     */
    suspend fun saveNote(wsId: String, nodeId: String, text: String, ifRevision: String? = null): NoteSaveResult {
        val p = guard()
        val body = JSONObject().put("text", text)
        if (ifRevision != null) body.put("ifRevision", ifRevision)
        val rawResp = raw(p, "api/workspaces/$wsId/nodes/$nodeId/note", "PUT", body)
        if (rawResp.status == 412) {
            val current = rawResp.jsonObject?.let { runCatching { NoteContent.parse(it) }.getOrNull() }
            if (current != null) return NoteSaveResult.Conflict(current)
        }
        rawResp.errorPayload()?.let { throw it }
        return NoteSaveResult.Saved(NoteContent.parse(rawResp.jsonObject ?: JSONObject()))
    }

    /**
     * PUT …/nodes/{id}/note/color {color} — vocabulary NAME only (probe round-tripped).
     * Clear Color = name "" (host answers 200 and clears). Custom Color rides WITH a name —
     * customColor alone → 400 "Invalid body" on the host.
     */
    suspend fun setNoteColor(wsId: String, nodeId: String, colorName: String, customColor: String? = null): NoteContent {
        val p = guard()
        val body = JSONObject().put("color", colorName)
        if (!customColor.isNullOrBlank()) body.put("customColor", customColor)
        val resp = raw(p, "api/workspaces/$wsId/nodes/$nodeId/note/color", "PUT", body)
        resp.errorPayload()?.let { throw it }
        return NoteContent.parse(resp.jsonObject ?: JSONObject())
    }

    /** PUT …/nodes/{id}/note/lock {locked} — idempotent; the state arrives via the next fetch/card. */
    suspend fun setNoteContentLock(wsId: String, nodeId: String, locked: Boolean) {
        val p = guard()
        val resp = raw(p, "api/workspaces/$wsId/nodes/$nodeId/note/lock", "PUT", JSONObject().put("locked", locked))
        resp.errorPayload()?.let { throw it }
    }

    /** POST …/nodes/{id}/rename {name} — host 400s on an empty body; callers keep non-blank names. */
    suspend fun renameNote(wsId: String, nodeId: String, name: String) {
        val p = guard()
        val resp = raw(p, "api/workspaces/$wsId/nodes/$nodeId/rename", "POST", JSONObject().put("name", name))
        resp.errorPayload()?.let { throw it }
    }

    // ------------------------------------------------------------------- fichário (note stacks)

    private fun mutationId(): String =
        "android-note-" + java.util.UUID.randomUUID().toString().uppercase()

    /** POST …/note-stacks {nodeIds} → stackNodeId. (iOS guards "needs two notes…" client-side.) */
    suspend fun createStack(wsId: String, nodeIds: List<String>, floorId: String? = null): String {
        val p = guard()
        val body = JSONObject().put("nodeIds", JSONArray(nodeIds))
        if (floorId != null) body.put("floorId", floorId)
        body.put("mutationId", mutationId())
        val resp = raw(p, "api/workspaces/$wsId/note-stacks", "POST", body)
        resp.errorPayload()?.let { throw it }
        return (resp.jsonObject ?: JSONObject()).optString("stackNodeId", "")
    }

    /** PUT …/nodes/{stackId}/stack/front {frontNoteId} → current member order + front. */
    suspend fun stackFront(wsId: String, stackId: String, frontNoteId: String): StackEcho {
        val p = guard()
        val resp = raw(
            p,
            "api/workspaces/$wsId/nodes/$stackId/stack/front",
            "PUT",
            JSONObject().put("frontNoteId", frontNoteId).put("mutationId", mutationId()),
        )
        resp.errorPayload()?.let { throw it }
        return stackEcho(resp)
    }

    /** PUT …/nodes/{stackId}/stack/order {memberNodeIds} — a permutation of the CURRENT tabs. */
    suspend fun stackOrder(wsId: String, stackId: String, memberNodeIds: List<String>): StackEcho {
        val p = guard()
        val resp = raw(
            p,
            "api/workspaces/$wsId/nodes/$stackId/stack/order",
            "PUT",
            JSONObject().put("memberNodeIds", JSONArray(memberNodeIds)).put("mutationId", mutationId()),
        )
        resp.errorPayload()?.let { throw it }
        return stackEcho(resp)
    }

    /** POST …/nodes/{stackId}/stack/pages → new page nodeId (afterNoteId inserts after that tab). */
    suspend fun stackNewPage(wsId: String, stackId: String, afterNoteId: String? = null): String {
        val p = guard()
        val body = JSONObject()
        if (afterNoteId != null) body.put("afterNoteId", afterNoteId)
        body.put("mutationId", mutationId())
        val resp = raw(p, "api/workspaces/$wsId/nodes/$stackId/stack/pages", "POST", body)
        resp.errorPayload()?.let { throw it }
        return (resp.jsonObject ?: JSONObject()).optString("nodeId", "")
    }

    /**
     * DELETE …/nodes/{stackId}/stack/pages/{noteId} — body {x, y?} = optional LANDING point;
     * the page returns to the canvas there. Non-member → 409 "Page is not in this fichário".
     */
    suspend fun stackRemovePage(
        wsId: String,
        stackId: String,
        noteId: String,
        landing: CanvasPoint? = null,
    ): StackEcho {
        val p = guard()
        val body = JSONObject()
        if (landing != null) body.put("x", landing.x).put("y", landing.y)
        val resp = raw(
            p,
            "api/workspaces/$wsId/nodes/$stackId/stack/pages/$noteId",
            "DELETE",
            body,
        )
        resp.errorPayload()?.let { throw it }
        return stackEcho(resp)
    }

    /** PUT …/nodes/{stackId}/stack/pages/{looseNoteId} — file a loose note in ("Add to Fichário"). */
    suspend fun stackFilePage(wsId: String, stackId: String, noteId: String): StackEcho {
        val p = guard()
        val resp = raw(
            p,
            "api/workspaces/$wsId/nodes/$stackId/stack/pages/$noteId",
            "PUT",
            JSONObject().put("mutationId", mutationId()),
        )
        resp.errorPayload()?.let { throw it }
        return stackEcho(resp)
    }

    private fun stackEcho(resp: RawResponse): StackEcho {
        val o = resp.jsonObject ?: JSONObject()
        val members = o.optJSONArray("memberNodeIds")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        return StackEcho(
            memberNodeIds = members,
            frontNoteId = if (o.has("frontNoteId") && !o.isNull("frontNoteId")) o.optString("frontNoteId") else null,
        )
    }

    /** The revision the host would compute for [text] (first 16 hex of SHA-256, UTF-8) — informational. */
    fun localRevision(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }
}