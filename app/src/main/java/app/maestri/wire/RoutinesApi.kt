package app.maestri.wire

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Routines + fire history (corpus 2026-10-16-maestri-wire-write-schemas.md §5ter "Routines",
 * full battery CONFIRMED; field list per the vendor contract's Rotinas section, cited by the
 * corpus as CONFIRMED).
 *
 * Route map (all owner, Bearer; GET is GLOBAL, not workspace-scoped):
 *  GET    api/routines                         {routines:[Routine], history:[FireRecord], targets:[{id,name,icon,workspaceId,floorId?,floorName?}]}
 *  POST   api/routines                         Routine body, `id` MUST be a UUID → {ok}
 *              400 empty name; 404 unknown workspaceId ("Workspace not found") / terminal not in that
 *              workspace ("Terminal not found in that workspace"); 409 duplicate id
 *  PUT    api/routines/{id}                    FULL substitution, body id must equal path id → {ok}
 *              400 "The routine's id does not match the route"; 404 unknown id ("Routine not found")
 *  POST   api/routines/{id}/action             {action: enable|disable|run} → {ok}
 *  DELETE api/routines/{id}                    {ok}; 404 unknown
 *  DELETE api/routines/history                 {ok} — routines untouched
 *
 * Contract rules carried in code: optional fields OMITTED on write, never null; fireCount /
 * lastFiredAt (and createdAt on POST) are host-owned and never sent; `id` is sent UPPERCASE
 * (house UUID convention). Schedule `once`/`daily`/`interval` + endRepeat `never`/`afterCount`
 * are round-tripped CONFIRMED; `weekly` schedule and `onDate` endRepeat are docs-typed
 * residues — parsed honestly, flagged CANDIDATE at the UI so nothing ships a happy path.
 */
class RoutinesApi(private val session: app.maestri.wire.session.WireSession) {

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

    suspend fun list(): RoutinesList = RoutinesList.parse(api("api/routines"))

    /** POST — the create route. The caller supplies an id that is already a real UUID. */
    suspend fun create(routine: Routine): JSONObject =
        api("api/routines", "POST", routine.toJson())

    /** PUT — full substitution; the host keeps its own counters (they are never sent). */
    suspend fun replace(routine: Routine): JSONObject =
        api("api/routines/${routine.id}", "PUT", routine.toJson())

    suspend fun action(routineId: String, action: String): JSONObject =
        api("api/routines/$routineId/action", "POST", JSONObject().put("action", action))

    suspend fun delete(routineId: String): JSONObject =
        api("api/routines/$routineId", "DELETE")

    suspend fun clearHistory(): JSONObject = api("api/routines/history", "DELETE")

    companion object {
        /** A UPPERCASE UUID for the create route (the wire demands a real UUID; the house keeps them uppercase). */
        fun newRoutineId(): String = UUID.randomUUID().toString().uppercase()
    }
}

/**
 * One schedulable target: a terminal a routine can run on, by workspace and floor as the
 * host's selector lists them. A routine WITHOUT a terminalId is a reminder instead.
 */
data class RoutineTarget(
    val id: String,
    val name: String,
    val icon: String?,
    val workspaceId: String,
    val floorId: String?,
    val floorName: String?,
) {
    companion object {
        fun parse(o: JSONObject): RoutineTarget = RoutineTarget(
            id = o.getString("id"),
            name = o.optString("name", ""),
            icon = if (o.has("icon") && !o.isNull("icon")) o.getString("icon") else null,
            workspaceId = o.optString("workspaceId", ""),
            floorId = if (o.has("floorId") && !o.isNull("floorId")) o.getString("floorId") else null,
            floorName = if (o.has("floorName") && !o.isNull("floorName")) o.getString("floorName") else null,
        )
    }
}

/**
 * schedule — open-shaped on purpose: {kind:"once",at} · {kind:"interval",seconds} ·
 * {kind:"daily",hour,minute} are CONFIRMED; {kind:"weekly",weekdays:[1…7 dom=1],hour,minute}
 * is docs-typed CANDIDATE. Anything else parses with its raw kind ("some other kind" rule).
 */
data class RoutineSchedule(
    val kind: String,
    val at: String?,
    val seconds: Long?,
    val hour: Int?,
    val minute: Int?,
    val weekdays: List<Int>,
) {
    /** CONFIRMED kinds the UI may compose freely; everything else stays awaiting confirmation. */
    val confirmedWrite: Boolean get() = kind == "once" || kind == "daily" || kind == "interval"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        at?.let { put("at", it) }
        seconds?.let { put("seconds", it) }
        hour?.let { put("hour", it) }
        minute?.let { put("minute", it) }
        if (weekdays.isNotEmpty()) put("weekdays", JSONArray().also { a -> weekdays.forEach { a.put(it) } })
    }

    companion object {
        fun parse(o: JSONObject): RoutineSchedule = RoutineSchedule(
            kind = o.optString("kind", ""),
            at = if (o.has("at") && !o.isNull("at")) o.getString("at") else null,
            seconds = o.optLong("seconds", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
            hour = if (o.has("hour") && !o.isNull("hour")) o.optInt("hour") else null,
            minute = if (o.has("minute") && !o.isNull("minute")) o.optInt("minute") else null,
            weekdays = o.optJSONArray("weekdays")?.let { a ->
                (0 until a.length()).mapNotNull { idx ->
                    runCatching { a.getInt(idx) }.getOrNull()
                }
            } ?: emptyList(),
        )

        fun once(at: String) = RoutineSchedule("once", at, null, null, null, emptyList())
        fun daily(hour: Int, minute: Int) = RoutineSchedule("daily", null, null, hour, minute, emptyList())
        fun interval(seconds: Long) = RoutineSchedule("interval", null, seconds, null, null, emptyList())
    }
}

/**
 * endRepeat — {kind:"never"} · {kind:"afterCount",count} CONFIRMED; {kind:"onDate",date}
 * docs-typed CANDIDATE (the accepted date format was never exercised).
 */
data class RoutineEndRepeat(
    val kind: String,
    val count: Int?,
    val date: String?,
) {
    val confirmedWrite: Boolean get() = kind == "never" || kind == "afterCount"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        count?.let { put("count", it) }
        date?.let { put("date", it) }
    }

    companion object {
        fun parse(o: JSONObject): RoutineEndRepeat = RoutineEndRepeat(
            kind = o.optString("kind", ""),
            count = if (o.has("count") && !o.isNull("count")) o.optInt("count") else null,
            date = if (o.has("date") && !o.isNull("date")) o.getString("date") else null,
        )

        val never = RoutineEndRepeat("never", null, null)
        fun afterCount(count: Int) = RoutineEndRepeat("afterCount", count, null)
    }
}

/**
 * Routine — per the vendor Rotinas contract: id, name, workspaceId, terminalId?, prompt,
 * preRunScript?, skipIfBusy, schedule, endRepeat, notifyOnFire, isEnabled, fireCount,
 * lastFiredAt?, createdAt. fireCount/lastFiredAt are host-owned; createdAt is host-stamped.
 * A routine with no terminalId is a reminder: it notifies and runs no command (its prompt
 * is the reminder's notes — the host 400s an empty name or a reminder without notes).
 */
data class Routine(
    val id: String,
    val name: String,
    val workspaceId: String,
    val terminalId: String?,
    val prompt: String,
    val preRunScript: String?,
    val skipIfBusy: Boolean,
    val schedule: RoutineSchedule,
    val endRepeat: RoutineEndRepeat,
    val notifyOnFire: Boolean,
    val isEnabled: Boolean,
    val fireCount: Int,
    val lastFiredAt: String?,
    val createdAt: String?,
) {
    val isReminder: Boolean get() = terminalId == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("workspaceId", workspaceId)
        terminalId?.let { put("terminalId", it) }
        put("prompt", prompt)
        preRunScript?.takeIf { it.isNotBlank() }?.let { put("preRunScript", it) }
        put("skipIfBusy", skipIfBusy)
        put("schedule", schedule.toJson())
        put("endRepeat", endRepeat.toJson())
        put("notifyOnFire", notifyOnFire)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun parse(o: JSONObject): Routine = Routine(
            id = o.getString("id"),
            name = o.optString("name", ""),
            workspaceId = o.optString("workspaceId", ""),
            terminalId = if (o.has("terminalId") && !o.isNull("terminalId")) o.getString("terminalId") else null,
            prompt = o.optString("prompt", ""),
            preRunScript = if (o.has("preRunScript") && !o.isNull("preRunScript")) o.getString("preRunScript") else null,
            skipIfBusy = o.optBoolean("skipIfBusy", false),
            schedule = o.optJSONObject("schedule")?.let { runCatching { RoutineSchedule.parse(it) }.getOrNull() }
                ?: RoutineSchedule("", null, null, null, null, emptyList()),
            endRepeat = o.optJSONObject("endRepeat")?.let { runCatching { RoutineEndRepeat.parse(it) }.getOrNull() }
                ?: RoutineEndRepeat.never,
            notifyOnFire = o.optBoolean("notifyOnFire", false),
            isEnabled = o.optBoolean("isEnabled", false),
            fireCount = o.optInt("fireCount", 0),
            lastFiredAt = if (o.has("lastFiredAt") && !o.isNull("lastFiredAt")) o.getString("lastFiredAt") else null,
            createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) o.getString("createdAt") else null,
        )
    }
}

/** One recorded fire: outcome is the docs' open enum (sentToTerminal | terminalNotRunning |
 * reminder | skippedByScript | skippedWhileBusy); unknown values keep their raw string. */
data class FireRecord(
    val id: String,
    val routineId: String,
    val routineName: String,
    val prompt: String,
    val firedAt: String?,
    val outcome: String,
    val terminalName: String?,
) {
    companion object {
        fun parse(o: JSONObject): FireRecord = FireRecord(
            id = o.getString("id"),
            routineId = if (o.has("routineId") && !o.isNull("routineId")) o.getString("routineId") else "",
            routineName = o.optString("routineName", ""),
            prompt = o.optString("prompt", ""),
            firedAt = if (o.has("firedAt") && !o.isNull("firedAt")) o.getString("firedAt") else null,
            outcome = o.optString("outcome", "").ifBlank { "unknown" },
            terminalName = if (o.has("terminalName") && !o.isNull("terminalName")) o.getString("terminalName") else null,
        )
    }
}

data class RoutinesList(
    val routines: List<Routine>,
    val history: List<FireRecord>,
    val targets: List<RoutineTarget>,
) {
    companion object {
        fun parse(o: JSONObject): RoutinesList {
            fun <T : Any> arr(name: String, f: (JSONObject) -> T): List<T> =
                o.optJSONArray(name)?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        runCatching { f(a.getJSONObject(idx)) }.getOrNull()
                    }
                } ?: emptyList()
            return RoutinesList(
                routines = arr("routines") { Routine.parse(it) },
                history = arr("history") { FireRecord.parse(it) },
                targets = arr("targets") { RoutineTarget.parse(it) },
            )
        }
    }
}