package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Overline
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.RootTitle
import app.maestri.ui.Style.monoHud
import app.maestri.ui.Style.warmRing
import app.maestri.wire.ApiException
import app.maestri.wire.FireRecord
import app.maestri.wire.Routine
import app.maestri.wire.RoutineEndRepeat
import app.maestri.wire.RoutineSchedule
import app.maestri.wire.RoutineTarget
import app.maestri.wire.RoutinesApi
import app.maestri.wire.RoutinesList
import app.maestri.wire.session.WireSession
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RoutinesNav {
    const val pattern = "routines"
}

/** Registers the Routines root; reachable via the Settings APP group. */
fun NavGraphBuilder.routinesDestination(session: WireSession, pop: () -> Unit) {
    composable(RoutinesNav.pattern) {
        RoutinesScreen(api = RoutinesApi(session), session = session, onBack = pop)
    }
}

/**
 * Routines — the host's scheduled prompts/reminders (corpus §5ter "Routines", CONFIRMED
 * battery). List with the enable switch + Run now + fire history; the create/edit sheet
 * picks a target from the host's own `targets`. The confirmed schedule kinds
 * once/daily/interval and endRepeat never/afterCount compose freely; weekly/onDate are
 * docs-typed residues: they render a "awaiting confirmation" state instead of a happy
 * path (a wrong-shaped write would live on the host until deleted).
 */
@Composable
fun RoutinesScreen(
    api: RoutinesApi,
    session: WireSession,
    onBack: () -> Unit,
) {
    var data by remember { mutableStateOf<RoutinesList?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<Routine?>(null) }
    var sheetNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Routine?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // S4-consistent gate: every routines route is a WRITE (owner-only per docs) except the
    // global GET. A guest still SEES the host's routines and history, but every affordance
    // sits disabled-with-reason; the host answers 403 regardless (defense in depth).
    val caps = session.serverInfo
    val canWrite = caps?.has("routines") == true && caps?.role == "owner"
    val writeReason = if (canWrite) null else app.maestri.ui.CanvasStrings.guestReadsOnly

    fun load() {
        scope.launch {
            loading = true
            try {
                data = api.list()
                err = null
            } catch (e: Exception) {
                err = errLine(e)
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun runAction(r: Routine, action: String) {
        if (!canWrite) return
        scope.launch {
            busyId = r.id
            try {
                api.action(r.id, action)
                data = api.list()
                err = null
            } catch (e: Exception) {
                err = errLine(e)
            } finally {
                busyId = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Routines — scheduled prompts and reminders" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            RootTitle("Routines", modifier = Modifier.weight(1f))
            app.maestri.ui.Style.IconPill(
                glyph = "＋",
                label = "New routine",
                active = !canWrite,   // visible-disabled affordance when a guest looks at this list
                enabled = canWrite,
                onClick = { if (canWrite) { sheetNew = true; sheetFor = null } },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "every routine a terminal can run, or a reminder on fire — global list",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        writeReason?.let {
            Text(it, style = monoHud(11, color = Palette.WarmSilver))
        }
        err?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        when {
            loading && data == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val d = data
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (d != null) {
                        item {
                            Overline("Routines · ${d.routines.size}", Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        if (d.routines.isEmpty()) {
                            item {
                                Text(
                                    "no routines yet ＋",
                                    style = monoHud(11, color = Palette.StoneGray),
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            }
                        }
                        items(d.routines, key = { it.id }) { r ->
                            RoutineCard(
                                routine = r,
                                targets = d.targets,
                                busy = busyId == r.id,
                                canWrite = canWrite,
                                onToggle = { runAction(r, if (r.isEnabled) "disable" else "enable") },
                                onRun = { runAction(r, "run") },
                                onEdit = { sheetFor = r; sheetNew = false },
                            )
                        }
                        item {
                            Overline("History · fires oldest to newest", Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        if (d.history.isEmpty()) {
                            item {
                                Text(
                                    "no recorded fires",
                                    style = monoHud(11, color = Palette.StoneGray),
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            }
                        }
                        items(d.history, key = { it.id }) { f ->
                            FireRecordRow(f)
                        }
                        item {
                            TextButton(
                                onClick = { if (canWrite) confirmClearHistory = true },
                                enabled = canWrite,
                                modifier = Modifier.padding(top = 6.dp),
                            ) { Text("Clear history", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (sheetFor != null || sheetNew) {
        RoutineSheet(
            api = api,
            editing = sheetFor,
            targetsRaw = data?.targets ?: emptyList(),
            canWrite = canWrite,
            onDone = {
                sheetFor = null
                sheetNew = false
                load()
            },
            onDismiss = {
                sheetFor = null
                sheetNew = false
            },
            onDelete = { r -> confirmDelete = r },
        )
    }

    confirmDelete?.let { r ->
        app.maestri.ui.screens.ConfirmDialog(
            title = "Delete “${r.name}”?",
            body = "The host removes the routine; its history rows stay until history is cleared.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    busyId = r.id
                    try {
                        api.delete(r.id)
                        sheetFor = null
                        sheetNew = false
                        data = api.list()
                        err = null
                    } catch (e: Exception) {
                        err = errLine(e)
                    } finally {
                        busyId = null
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }

    if (confirmClearHistory) {
        app.maestri.ui.screens.ConfirmDialog(
            title = "Clear the fire history?",
            body = "The host wipes the history of executions; routines are untouched.",
            confirmLabel = "Clear",
            onConfirm = {
                if (!canWrite) return@ConfirmDialog
                confirmClearHistory = false
                scope.launch {
                    try {
                        api.clearHistory()
                        data = api.list()
                        err = null
                    } catch (e: Exception) {
                        err = errLine(e)
                    }
                }
            },
            onDismiss = { confirmClearHistory = false },
        )
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    targets: List<RoutineTarget>,
    busy: Boolean,
    canWrite: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val target = targets.firstOrNull { it.id == routine.terminalId }
    Box(
        Modifier
            .fillMaxWidth()
            .warmRing(c.hairline, radius = MaestriRadii.Card)
            .background(c.canvasSurface, RoundedCornerShape(MaestriRadii.Card))
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    routine.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = routine.isEnabled,
                    enabled = canWrite && !busy,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics {
                        contentDescription = if (routine.isEnabled) "Disable ${routine.name}" else "Enable ${routine.name}"
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                scheduleLine(routine),
                style = monoHud(11, color = Palette.StoneGray),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                targetLine(routine, target) + " · fired ×${routine.fireCount}" +
                    (routine.lastFiredAt?.let { " · last ${timeLine(it)}" } ?: ""),
                style = monoHud(11, color = Palette.StoneGray),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = canWrite && !busy, onClick = onRun) { Text("Run now") }
                TextButton(enabled = canWrite && !busy, onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun FireRecordRow(f: FireRecord) {
    val c = LocalMaestriColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.routineName.ifBlank { "(removed routine)" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                f.outcome,
                style = monoHud(11, color = outcomeColor(f.outcome, c)),
            )
        }
        Row {
            Text(
                (f.firedAt?.let { timeLine(it) } ?: "") +
                    (f.terminalName?.let { " · → $it" } ?: ""),
                style = monoHud(11, color = Palette.StoneGray),
            )
        }
        if (f.prompt.isNotBlank()) {
            Text(
                f.prompt,
                fontSize = 11.5.sp,
                color = c.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun outcomeColor(outcome: String, c: app.maestri.ui.Style.MaestriColors) = when (outcome) {
    "sentToTerminal" -> c.positive
    "terminalNotRunning" -> c.warning
    "skippedByScript", "skippedWhileBusy" -> Palette.StoneGray
    "reminder" -> c.accent
    else -> c.secondaryLabel
}

private fun scheduleLine(r: Routine): String {
    val s = r.schedule
    val core = when (s.kind) {
        "once" -> "once · at ${s.at?.let { timeLine(it) } ?: "?"}"
        "daily" -> "daily · ${"%02d".format(s.hour ?: 0)}:${"%02d".format(s.minute ?: 0)}"
        "interval" -> "interval · every ${s.seconds ?: "?"}s"
        "weekly" -> "weekly · weekdays ${s.weekdays.joinToString(",")} ${"%02d".format(s.hour ?: 0)}:${"%02d".format(s.minute ?: 0)}"
        else -> s.kind.ifBlank { "(no schedule)" }
    }
    val end = when (r.endRepeat.kind) {
        "never" -> ""
        "afterCount" -> " · end after ${r.endRepeat.count ?: "?"} fires"
        "onDate" -> " · end ${r.endRepeat.date ?: "?"}"
        else -> " · end ${r.endRepeat.kind}"
    }
    val candidate = when {
        !s.confirmedWrite -> " · schedule kind awaiting confirmation"
        !r.endRepeat.confirmedWrite -> " · end-repeat kind awaiting confirmation"
        else -> ""
    }
    return core + end + candidate
}

private fun targetLine(r: Routine, target: RoutineTarget?): String = when {
    target != null -> "→ ${target.name}" +
        (target.floorName?.let { " · $it" } ?: "") +
        " (${target.workspaceId.take(8)}…)"
    r.isReminder -> "→ reminder (notifies, runs no command)"
    else -> "→ terminal not in targets"
}

private val fireTime = DateTimeFormatter.ofPattern("MMM d HH:mm")
private fun timeLine(iso: String): String =
    (runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(fireTime) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).format(fireTime) }.getOrNull())
        ?: iso.take(24)

private fun errLine(e: Exception): String = when (e) {
    is ApiException -> e.message.ifBlank { "HTTP ${e.status}" }
    else -> e.message ?: "network error"
}

/**
 * Create/edit sheet. POST builds a fresh UPPERCASE-UUID id with isEnabled=false — enabling
 * stays the user's explicit act on the list (an enabled `once` that is already due fires at
 * once per the corpus OBSERVED-DELTA). PUT is a full substitution and sends back what the
 * host sent, with id equal to the path id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineSheet(
    api: RoutinesApi,
    editing: Routine?,
    targetsRaw: List<RoutineTarget>,
    canWrite: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (Routine) -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var prompt by remember { mutableStateOf(editing?.prompt ?: "") }
    var preRunScript by remember { mutableStateOf(editing?.preRunScript ?: "") }
    var skipIfBusy by remember { mutableStateOf(editing?.skipIfBusy ?: false) }
    var notifyOnFire by remember { mutableStateOf(editing?.notifyOnFire ?: false) }
    var reminderMode by remember { mutableStateOf(editing?.terminalId == null) }
    var targetId by remember {
        val etid = editing?.terminalId
        mutableStateOf(
            if (etid != null && targetsRaw.any { it.id == etid }) etid
            else targetsRaw.firstOrNull()?.id
        )
    }
    var kindKey by remember { mutableStateOf(editing?.schedule?.kind ?: "once") }
    var atText by remember {
        mutableStateOf(
            editing?.schedule?.at ?: Instant.now().plusSeconds(3600).toString(),
        )
    }
    var hourText by remember { mutableStateOf(editing?.schedule?.hour?.toString() ?: "9") }
    var minuteText by remember { mutableStateOf(editing?.schedule?.minute?.toString() ?: "30") }
    var secondsText by remember { mutableStateOf(editing?.schedule?.seconds?.toString() ?: "900") }
    var endKind by remember { mutableStateOf(editing?.endRepeat?.kind ?: "never") }
    var endCountText by remember { mutableStateOf(editing?.endRepeat?.count?.toString() ?: "5") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val targets = targetsRaw.sortedWith(compareBy({ it.workspaceId }, { it.floorName ?: "" }, { it.name }))

    fun scheduleFor(): RoutineSchedule? = when (kindKey) {
        "once" -> {
            val parsed = parseInstant(atText.trim()) ?: run { error = "unparseable 'at' time — use ISO-8601 (2026-10-18T09:30:00Z)"; null }
            parsed?.let { RoutineSchedule.once(it) }
        }
        "daily" -> {
            val h = hourText.toIntOrNull()?.takeIf { it in 0..23 } ?: run { error = "hour 0…23"; null }
            val m = minuteText.toIntOrNull()?.takeIf { it in 0..59 } ?: run { error = "minute 0…59"; null }
            if (h != null && m != null && kindKey == "daily") RoutineSchedule.daily(h, m) else null
        }
        "interval" -> {
            val s = secondsText.toLongOrNull()?.takeIf { it > 0 } ?: run { error = "seconds must be a positive number"; null }
            s?.let { RoutineSchedule.interval(it) }
        }
        else -> {
            error = "schedule kind '$kindKey' is awaiting confirmation — not writable here"
            null
        }
    }

    fun endRepeatFor(): RoutineEndRepeat? = when (endKind) {
        "never" -> RoutineEndRepeat.never
        "afterCount" -> RoutineEndRepeat.afterCount(endCountText.toIntOrNull()?.takeIf { it > 0 } ?: run { error = "end after N fires needs a positive count"; return null })
        else -> {
            error = "end repeat '$endKind' is awaiting confirmation — not writable here"
            null
        }
    }

    fun save() {
        error = null
        val editingLocal = editing
        val schedule = scheduleFor() ?: return
        val endRepeat = endRepeatFor() ?: return
        if (name.isBlank()) { error = "name is required (the host 400s an empty name)"; return }
        val terminalId = if (reminderMode) null else {
            targetId ?: run { error = "pick a target terminal"; return }
        }
        if (terminalId == null && prompt.isBlank()) {
            error = "a reminder without notes is rejected by the host (400) — write the reminder text"
            return
        }
        val routine = Routine(
            id = editingLocal?.id ?: RoutinesApi.newRoutineId(),
            name = name.trim(),
            workspaceId = if (editingLocal != null) editingLocal.workspaceId
            else targetsRaw.firstOrNull { it.id == terminalId }?.workspaceId ?: "",
            terminalId = terminalId,
            prompt = prompt.trim(),
            preRunScript = preRunScript.trim().takeIf { it.isNotBlank() },
            skipIfBusy = skipIfBusy,
            schedule = schedule,
            endRepeat = endRepeat,
            notifyOnFire = notifyOnFire,
            isEnabled = if (editingLocal != null) editingLocal.isEnabled else false,
            fireCount = 0,
            lastFiredAt = null,
            createdAt = null,
        )
        if (!canWrite) return
        scope.launch {
            saving = true
            try {
                if (editingLocal != null) api.replace(routine) else api.create(routine)
                onDone()
            } catch (e: Exception) {
                error = errLine(e)
            } finally {
                saving = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (editing != null) "Edit routine" else "New routine",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (editing != null) {
                    TextButton(onClick = { onDelete(editing) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            }
            Text(
                if (editing != null) "PUT replaces the routine whole; its owned counters reset when the time changes"
                else "POST — the routine is created under a fresh UUID, disabled; enable it from the list",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(if (reminderMode) "Reminder notes (required)" else "Prompt") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = preRunScript,
                onValueChange = { preRunScript = it },
                label = { Text("Pre-run script (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text("TARGET", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RadioRow(
                title = "Reminder — notify only, no command",
                desc = "a routine without a terminal notifies and runs nothing",
                selected = reminderMode,
                onClick = { reminderMode = true },
            )
            targets.forEach { t ->
                RadioRow(
                    title = t.name,
                    desc = (t.floorName ?: "no floor name") + " · " + t.workspaceId.take(8) + "…",
                    selected = !reminderMode && targetId == t.id,
                    onClick = {
                        reminderMode = false
                        targetId = t.id
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("SCHEDULE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScheduleKindRow(
                title = "Once at…", selected = kindKey == "once", confirmed = true,
                onClick = { kindKey = "once" },
            ) {
                OutlinedTextField(
                    value = atText,
                    onValueChange = { atText = it },
                    label = { Text("at (ISO-8601)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ScheduleKindRow(
                title = "Daily (wall clock on the host)", selected = kindKey == "daily", confirmed = true,
                onClick = { kindKey = "daily" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("hour 0…23") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("minute 0…59") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
            ScheduleKindRow(
                title = "Interval (every N seconds)", selected = kindKey == "interval", confirmed = true,
                onClick = { kindKey = "interval" },
            ) {
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it.filter { c -> c.isDigit() }.take(9) },
                    label = { Text("seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(160.dp),
                )
            }
            ScheduleKindRow(
                title = "Weekly · weekdays + time",
                selected = kindKey == "weekly" && editing != null,
                confirmed = false,
                onClick = {},
                pendingNote = "kind confirmed by docs only — awaiting live confirmation, not writable here",
            )
            if (editing != null && editing.schedule.kind == "weekly") {
                Text(
                    "host sent: ${scheduleLine(editing)}",
                    style = monoHud(11, color = Palette.StoneGray),
                    modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("END REPEAT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScheduleKindRow(
                title = "Never", selected = endKind == "never", confirmed = true,
                onClick = { endKind = "never" }, compact = true,
            )
            ScheduleKindRow(
                title = "After N fires", selected = endKind == "afterCount", confirmed = true,
                onClick = { endKind = "afterCount" }, compact = true,
            ) {
                OutlinedTextField(
                    value = endCountText,
                    onValueChange = { endCountText = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("fires") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
            }
            ScheduleKindRow(
                title = "On date",
                selected = endKind == "onDate" && editing != null,
                confirmed = false,
                onClick = {},
                pendingNote = "docs-typed residue — awaiting live confirmation, not writable here",
                compact = true,
            )

            Spacer(Modifier.height(12.dp))
            ToggleRow("Notify on fire", notifyOnFire) { notifyOnFire = it }
            ToggleRow("Skip while the terminal is busy", skipIfBusy) { skipIfBusy = it }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { save() },
                enabled = canWrite && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (editing != null) "Save (full substitution)" else "Create (disabled)") }
        }
    }
}

@Composable
private fun RadioRow(title: String, desc: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!desc.isNullOrBlank()) {
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Confirmed kinds compose; the residue row is visible but inert with its reason. */
@Composable
private fun ScheduleKindRow(
    title: String,
    selected: Boolean,
    confirmed: Boolean,
    compact: Boolean = false,
    pendingNote: String? = null,
    onClick: () -> Unit,
    fields: (@Composable () -> Unit)? = null,
) {
    val c = LocalMaestriColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = confirmed) { onClick() }
            .padding(vertical = if (compact) 2.dp else 6.dp),
    ) {
        RadioButton(selected = selected, onClick = if (confirmed) onClick else null, enabled = confirmed)
        Column(Modifier.padding(start = 4.dp).weight(1f)) {
            Text(
                title,
                fontSize = 13.5.sp,
                color = if (confirmed) MaterialTheme.colorScheme.onSurface else c.secondaryLabel.copy(alpha = 0.7f),
            )
            if (!confirmed && pendingNote != null) {
                Text(pendingNote, fontSize = 10.5.sp, color = c.warning, fontFamily = FontFamily.Monospace)
            }
        }
    }
    if (selected && confirmed && fields != null) {
        Box(Modifier.padding(start = 34.dp)) { fields() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** ISO-8601 instants and local date-times both parse; anything else fails closed. */
private fun parseInstant(raw: String): String? =
    runCatching { Instant.parse(raw).toString() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toString()
        }.getOrNull()

/** Generic confirm for destructive-ish host writes (delete routine / clear history). */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = { Text(body, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}