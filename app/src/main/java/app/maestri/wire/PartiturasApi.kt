package app.maestri.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Partituras (docs §232; corpus §2/§5ter — list + preview + stamp battery CONFIRMED live,
 * round-tripped on the host's real 2-entry library).
 *
 * Route map:
 *  GET  api/partituras                           {partituras:[Partitura]} — host-level, any device
 *                                                (a guest may browse too)
 *  GET  api/partituras/{id}/preview?appearance=  200 image/jpeg + strong ETag; 304 on If-None-Match
 *                                                (SnapshotMedia carries the ETag discipline);
 *                                                unknown id → 404 "Partitura not found"
 *  POST api/workspaces/{ws}/partituras           {partituraId, x, y} → {ok} — the layout is
 *                                                STAMPED as ORDINARY nodes (terminals arrive
 *                                                filled but inert, isRunning:false; note/fileTree
 *                                                arrive as themselves; there is NO "partitura"
 *                                                node kind). Unknown id → 404 "Partitura not
 *                                                found"; missing x/y → 400; guest → 403.
 *
 * Honest residues (carried in code + surfaced in the UI, never a silent happy path):
 *  - stamp `floorId` / `mutationId` are docs-typed only (corpus §145 lists them as the residue) —
 *    the CONFIRMED live path is the no-floorId stamp that lands on the GROUND floor, so that is
 *    the only stamp this API sends;
 *  - Partitura.workspaceId (and any body workspaceId) are docs-typed (format-2 era) — parsed
 *    for display, never written back;
 *  - preview `appearance` is documented as light|dark; values beyond the two are unprobed and
 *    this app only ever sends "dark".
 */
data class Partitura(
    val id: String,
    val name: String,
    val summary: String,
    val icon: String?,
    val color: String?,
    val workspaceId: String?,
    val roles: List<PartituraRole>,
) {
    companion object {
        fun fromJson(o: JSONObject): Partitura {
            val roles = o.optJSONArray("roles")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val r = a.getJSONObject(i)
                        PartituraRole(
                            id = r.optString("id"),
                            name = r.optString("name"),
                            icon = r.optString("icon").takeIf { it.isNotBlank() },
                            color = r.optString("color").takeIf { it.isNotBlank() },
                        )
                    }.getOrNull()
                }
            } ?: emptyList()
            return Partitura(
                id = o.optString("id"),
                name = o.optString("name"),
                summary = o.optString("summary"),
                icon = o.optString("icon").takeIf { it.isNotBlank() },
                color = o.optString("color").takeIf { it.isNotBlank() },
                workspaceId = o.optString("workspaceId").takeIf { it.isNotBlank() },
                roles = roles,
            )
        }
    }
}

/** The docs-typed {id, name, icon, color} role reference — parsed, never edited from a client. */
data class PartituraRole(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
)

class PartiturasApi(private val session: app.maestri.wire.session.WireSession) {

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

    /** GET /api/partituras — the whole library, host order unchanged. */
    suspend fun list(): List<Partitura> = withContext(Dispatchers.IO) {
        val arr = api("api/partituras").optJSONArray("partituras")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            runCatching { Partitura.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /**
     * POST /api/workspaces/{ws}/partituras — the CONFIRMED body only: {partituraId, x, y},
     * no floorId (the confirmed landing is the ground floor; floorId is the docs-typed
     * residue above). A stamp is a write: owner-role only (403 for a guest), and the UI
     * gates it behind its own explicit confirm dialog.
     */
    suspend fun stamp(wsId: String, partituraId: String, x: Double, y: Double): JSONObject =
        api(
            "api/workspaces/$wsId/partituras",
            "POST",
            JSONObject()
                .put("partituraId", partituraId)
                .put("x", x)
                .put("y", y),
        )

    /**
     * The preview bytes via SnapshotMedia (strong ETag + revalidate). appearance rides as a
     * real query parameter — a '?' inside the path string would arrive percent-encoded.
     */
    suspend fun preview(
        partituraId: String,
        appearance: String = "dark",
    ): SnapshotMedia.Result {
        pair()
        return SnapshotMedia.fetch(
            session = session,
            path = "api/partituras/$partituraId/preview",
            cacheKey = "partitura:$partituraId:$appearance",
            query = mapOf("appearance" to appearance),
        )
    }
}