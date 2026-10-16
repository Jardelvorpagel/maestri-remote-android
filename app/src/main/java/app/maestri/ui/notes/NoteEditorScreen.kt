package app.maestri.ui.notes

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Amber
import app.maestri.ui.NotesStrings as S
import app.maestri.ui.Red
import app.maestri.ui.Surface0
import app.maestri.ui.Surface1
import app.maestri.ui.Surface2
import app.maestri.wire.NotesApi
import app.maestri.wire.NoteContent
import app.maestri.wire.NoteSaveResult
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun NoteEditorScreen(
    session: WireSession,
    wsId: String,
    nodeId: String,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val toastContext = LocalContext.current
    val toast: (String) -> Unit = remember {
        { message: String ->
            if (message.isNotBlank()) Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }
    val model = remember(session, wsId, nodeId) { NoteEditorModel(session, wsId, nodeId, appContext) }
    val state by model.state.collectAsState()

    // Fetch-on-open (+ draft restore).
    LaunchedEffect(model) { model.open { toast(it) } }

    // Keystroke debounces — LaunchedEffect keyed on the text restarts per keystroke:
    // the draft write-back at ~250 ms, the autosave PUT at ~1.2 s.
    LaunchedEffect(state.textState, state.dirty, state.conflict, state.locked) {
        if (state.ready?.canWrite == true && state.dirty) {
            delay(DRAFT_DEBOUNCE_MS)
            model.persistDraft()
        }
    }
    LaunchedEffect(state.textState, state.dirty, state.conflict, state.locked) {
        if (state.ready?.canWrite == true && state.dirty) {
            delay(AUTOSAVE_DEBOUNCE_MS)
            model.autosave { toast(it) }
        }
    }

    // Content-lock watch: only while a draft sits blocked behind the lock (no mutation event
    // exists — iOS reads `isContentLocked` off the card; our parsed snapshot drops payloads,
    // so this bounded GET poll is the standing substitute). Unlock → autosave resumes.
    LaunchedEffect(state.locked, state.dirty, state.conflict) {
        if (state.ready?.locked == true && state.dirty && state.conflict == null) {
            model.watchLockUntilUnlocked()
        }
    }

    DisposableEffect(model) {
        onDispose { model.saveOnExit() }
    }

    Column(Modifier.fillMaxSize().background(Surface0)) {
        when (val s = state) {
            is EditorState.Loading -> {
                EditorTopBar(model, s, onBack, toast)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(S.LOADING_NOTE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is EditorState.Unavailable -> {
                EditorTopBar(model, s, onBack, toast)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (s.detail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(s.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            is EditorState.Ready -> {
                EditorTopBar(model, s, onBack, toast)
                ReadyEditor(model, s, toast)
            }
        }
    }
    BackHandler { onBack() }
}

// --------------------------------------------------------------------------- state + model

sealed interface EditorState {
    val textState: String
    val dirty: Boolean
    val locked: Boolean
    val conflict: Conflict?
    val ready: Ready?
        get() = this as? Ready

    /** Editable: not read-only, not locked, not mid-conflict. */
    val canWrite: Boolean
        get() = false

    data object Loading : EditorState {
        override val textState get() = ""
        override val dirty get() = false
        override val locked get() = false
        override val conflict get() = null
    }

    data class Unavailable(
        val headline: String,
        val detail: String,
    ) : EditorState {
        override val textState get() = ""
        override val dirty get() = false
        override val locked get() = false
        override val conflict get() = null
    }

    data class Ready(
        val content: NoteContent,
        val text: String,
        override val dirty: Boolean,
        val saving: Boolean,
        override val locked: Boolean,
        val readOnly: Boolean,
        val status: SaveStatus,
        override val conflict: Conflict?,
        val infoBanner: String?,
    ) : EditorState {
        override val textState get() = text
        override val canWrite get() = !readOnly && !locked && conflict == null
    }
}

sealed interface SaveStatus {
    data object Quiet : SaveStatus                       // "All changes saved"
    data object Dirty : SaveStatus                       // "This note has unsaved changes"…
    data object SavedButNewer : SaveStatus               // "Saved. You have newer edits…"
    data class Failed(val message: String) : SaveStatus  // … + "Retry saving note"
}

/** 412 dialog state: the wording variant + the current NoteContent carried by the 412 body. */
data class Conflict(
    val bodyMessage: String,
    val computerVersion: NoteContent,
)

class NoteEditorModel(
    private val session: WireSession,
    private val wsId: String,
    private val nodeId: String,
    appContext: Context,
) {
    private val api = NotesApi(session)
    private val drafts = NotesDraftStore(appContext)
    private val _state = MutableStateFlow<EditorState>(EditorState.Loading)
    val state: StateFlow<EditorState> = _state

    val serverKey: String get() = session.serverKey

    private fun updateReady(transform: (EditorState.Ready) -> EditorState.Ready) {
        val s = _state.value as? EditorState.Ready ?: return
        _state.value = transform(s)
    }

    // ------------------------------------------------------------------- open

    /** Fetch-on-open + draft restore. Wording follows iOS exactly. */
    suspend fun open(onToast: (String) -> Unit) {
        _state.value = EditorState.Loading
        val content = try {
            api.fetchNote(wsId, nodeId)
        } catch (e: NotesApi.NotesApiException) {
            _state.value = if (e.status == 404) {
                EditorState.Unavailable(S.CANT_OPEN_THIS_NOTE, S.THIS_NOTE_IS_NO_LONGER_ON_THE_CANVAS)
            } else {
                EditorState.Unavailable(S.NOTE_UNAVAILABLE, if (e.status == 401) "" else e.message)
            }
            return
        } catch (e: Exception) {
            _state.value = EditorState.Unavailable(S.NOTE_UNAVAILABLE, S.YOUR_COMPUTER_IS_OUT_OF_REACH)
            return
        }
        // Read-only devices read but cannot change (capability-gated on the host).
        val readOnly = session.serverInfo?.has("nativeNotes") == false
        val draft = if (readOnly) null else runCatching { drafts.draft(serverKey, nodeId) }.getOrNull()
        val restored = draft != null && draft.text != content.text
        if (draft != null && !restored) runCatching { drafts.clear(serverKey, nodeId) }
        _state.value = EditorState.Ready(
            content = content,
            text = draft?.text?.takeIf { restored } ?: content.text,
            dirty = restored,
            saving = false,
            locked = content.isContentLocked,
            readOnly = readOnly,
            status = if (restored) SaveStatus.Dirty else SaveStatus.Quiet,
            conflict = null,
            infoBanner = if (restored) S.UNSAVED_EDITS_FROM_A_PREVIOUS_SESSION else null,
        )
        if (restored) onToast(S.UNSAVED_EDITS_FROM_A_PREVIOUS_SESSION)
    }

    // ------------------------------------------------------------------- keystrokes + autosave

    /** Typing: mark dirty; lock makes the edits stay LOCAL on purpose (banner, no error). */
    fun onText(text: String) {
        val s = _state.value as? EditorState.Ready ?: return
        if (s.readOnly || s.conflict != null) return
        _state.value = s.copy(text = text, dirty = true, saving = false, status = SaveStatus.Dirty)
    }

    /** Draft write-back (UI debounces); carries the revision it is based on. */
    suspend fun persistDraft() {
        val s = _state.value as? EditorState.Ready ?: return
        runCatching { drafts.save(serverKey, nodeId, s.text, s.content.revision) }
    }

    /**
     * Autosave: text identical to what the host already holds does NOT hit the wire;
     * otherwise PUT {text, ifRevision: <the revision this editor state is based on>}.
     */
    suspend fun autosave(onToast: (String) -> Unit) {
        val s = _state.value as? EditorState.Ready ?: return
        if (!s.canWrite || s.saving || !s.dirty) return
        if (s.text == s.content.text) {
            _state.value = s.copy(dirty = false, status = SaveStatus.Quiet)
            runCatching { drafts.clear(serverKey, nodeId) }
            return
        }
        performSave(s, useIfRevision = true, onToast = onToast)
    }

    /** "Retry saving note" button and the conflict dialog's keep-mine land here:
     *  re-PUT WITHOUT ifRevision = "Keeping yours replaces the version on your computer." */
    suspend fun retrySave(onToast: (String) -> Unit) {
        val s = _state.value as? EditorState.Ready ?: return
        updateReady { it.copy(conflict = null) }
        if (s.locked || s.readOnly) return
        performSave(s, useIfRevision = false, onToast = onToast)
    }

    /** 412 → "Use the Computer's Version": adopt the body's NoteContent into the editor. */
    suspend fun takeComputerVersion() {
        val s = _state.value as? EditorState.Ready ?: return
        val conflict = s.conflict ?: return
        _state.value = s.copy(
            content = conflict.computerVersion,
            text = conflict.computerVersion.text,
            dirty = false,
            saving = false,
            locked = conflict.computerVersion.isContentLocked,
            status = SaveStatus.Quiet,
            conflict = null,
        )
        runCatching { drafts.clear(serverKey, nodeId) }
    }

    private suspend fun performSave(s: EditorState.Ready, useIfRevision: Boolean, onToast: (String) -> Unit) {
        _state.value = s.copy(saving = true)
        try {
            val result = api.saveNote(wsId, nodeId, s.text, if (useIfRevision) s.content.revision else null)
            when (result) {
                is NoteSaveResult.Saved -> {
                    updateReady { latest ->
                        val keptTyping = latest.text != s.text
                        latest.copy(
                            content = result.content,
                            dirty = keptTyping,
                            saving = false,
                            locked = result.content.isContentLocked,
                            status = if (keptTyping) SaveStatus.SavedButNewer else SaveStatus.Quiet,
                        )
                    }
                    if ((_state.value as? EditorState.Ready)?.dirty != true) {
                        runCatching { drafts.clear(serverKey, nodeId) }
                    }
                }
                is NoteSaveResult.Conflict -> updateReady { latest ->
                    val hadUnsaved = latest.dirty
                    latest.copy(
                        saving = false,
                        conflict = Conflict(
                            bodyMessage = if (hadUnsaved) S.CONFLICT_BODY_WHILE_DIRTY else S.CONFLICT_BODY_BEFORE_EDITS,
                            computerVersion = result.currentNoteContent,
                        ),
                    )
                }
            }
        } catch (e: NotesApi.NotesApiException) {
            when {
                e.status == 409 || e.code == "conflict" -> {
                    // Host rejects the write: the content is locked on the computer.
                    // Local-edit mode — the banner explains it; nothing errors in the user's face.
                    updateReady { it.copy(saving = false, locked = true, status = SaveStatus.Dirty) }
                }
                e.status == 413 -> {
                    updateReady { it.copy(saving = false, status = SaveStatus.Failed(S.THIS_NOTE_IS_TOO_LARGE_TO_SEND)) }
                    onToast(S.THIS_NOTE_IS_TOO_LARGE_TO_SEND)
                }
                else -> saveFailed(onToast, S.YOUR_COMPUTER_COULDNT_SAVE_THIS_NOTE)
            }
        } catch (e: java.net.SocketTimeoutException) {
            saveFailed(onToast, S.YOUR_COMPUTER_DIDNT_RESPOND_IN_TIME)
        } catch (e: java.io.IOException) {
            saveFailed(onToast, S.YOUR_COMPUTER_IS_OUT_OF_REACH_KEPT)
        } catch (e: Exception) {
            saveFailed(onToast, S.YOUR_COMPUTER_COULDNT_SAVE_THIS_NOTE)
        }
    }

    private fun saveFailed(onToast: (String) -> Unit, message: String) {
        updateReady { it.copy(saving = false, status = SaveStatus.Failed(message)) }
        onToast(message)
    }

    /**
     * While locked+dirty: watch for the unlock. On unlock WITHOUT a newer host text → adopt
     * the fresh state and let autosave resume; if the host text moved meanwhile, KEEP the
     * editor's old revision basis so the next save 412s into iOS's conflict dialog.
     * @return true = still locked (keep watching), false = unlocked.
     */
    suspend fun watchLockUntilUnlocked(): Boolean {
        while (true) {
            delay(LOCK_WATCH_POLL_MS)
            val s = _state.value as? EditorState.Ready ?: return false
            if (!s.locked || !s.dirty || s.conflict != null) return false
            try {
                val content = api.fetchNote(wsId, nodeId)
                if (!content.isContentLocked) {
                    val sameText = content.text == s.text
                    val adopted = if (sameText) content
                    else content.copy(revision = s.content.revision, text = s.text,
                        lastModifiedAt = content.lastModifiedAt)
                    if (sameText) {
                        updateReady { it.copy(content = content, locked = false, dirty = false, status = SaveStatus.Quiet) }
                        runCatching { drafts.clear(serverKey, nodeId) }
                    } else {
                        updateReady { it.copy(content = adopted, locked = false) }
                    }
                    return false
                }
                updateReady { it.copy(content = content) } // refresh color/metadata while waiting
            } catch (_: Exception) {
                // unreachable mid-poll: keep the watch alive — the warm draft holds the edits
            }
        }
    }

    // ------------------------------------------------------------------- rename / color / lock

    /** "Rename Note" — external notes refuse with the iOS wording; blank is a no-op (host 400s {} bodies). */
    suspend fun rename(name: String, onToast: (String) -> Unit) {
        val s = _state.value as? EditorState.Ready ?: return
        if (s.content.isExternal) {
            onToast(S.EXTERNAL_NOTES_CANT_BE_RENAMED)
            return
        }
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        try {
            api.renameNote(wsId, nodeId, trimmed)
            updateReady { it.copy(content = it.content.copy(displayName = trimmed, hasCustomName = true)) }
        } catch (e: Exception) {
            onToast(S.MAESTRI_COULDNT_RENAME)
        }
    }

    /** One of the 10 vocabulary names, or "" = Clear Color; customColor rides WITH a name. */
    suspend fun setColor(name: String, customColor: String? = null, onToast: (String) -> Unit) {
        try {
            val echo = api.setNoteColor(wsId, nodeId, name, customColor)
            updateReady { it.copy(content = echo) }
        } catch (e: Exception) {
            onToast(S.COULDNT_CHANGE_COLOR)
        }
    }

    /** "Lock Contents" / "Unlock Contents" — idempotent; the state arrives in the echo/fetch. */
    suspend fun setContentLock(locked: Boolean, onToast: (String) -> Unit) {
        try {
            api.setNoteContentLock(wsId, nodeId, locked)
            updateReady { it.copy(content = it.content.copy(isContentLocked = locked)) }
            onToast(if (locked) S.NOTE_LOCKED else S.NOTE_UNLOCKED)
        } catch (e: Exception) {
            onToast(S.SOMETHING_WENT_WRONG_ON_YOUR_COMPUTER)
        }
    }

    /** Back/out: the last save rides the app-level scope; the draft is warm either way. */
    fun saveOnExit() {
        val s = _state.value as? EditorState.Ready ?: return
        if (!s.dirty || s.locked || s.readOnly || s.conflict != null || s.saving) return
        if (s.text == s.content.text) {
            _state.value = s.copy(dirty = false, status = SaveStatus.Quiet)
            return
        }
        val ifRevision = s.content.revision
        val text = s.text
        session.scope.launch {
            try {
                val result = api.saveNote(wsId, nodeId, text, ifRevision)
                if (result is NoteSaveResult.Saved) runCatching { drafts.clear(serverKey, nodeId) }
            } catch (_: Exception) {
                // iOS "Your edits are kept on this device": the warm draft already holds them.
            }
        }
    }
}

// ------------------------------------------------------------------------------- compose UI

private const val DRAFT_DEBOUNCE_MS = 250L
private const val AUTOSAVE_DEBOUNCE_MS = 1_200L
private const val LOCK_WATCH_POLL_MS = 8_000L

@Composable
private fun EditorTopBar(
    model: NoteEditorModel,
    state: EditorState,
    onBack: () -> Unit,
    toast: (String) -> Unit,
) {
    val ready = state.ready
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹", fontSize = 22.sp, maxLines = 1) }
        Column(Modifier.weight(1f)) {
            Text(
                ready?.content?.displayName ?: "",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ready?.content?.fileName?.takeIf { it.isNotBlank() }?.let { fileName ->
                Text(fileName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ready != null && !ready.readOnly) {
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯", fontSize = 22.sp, maxLines = 1) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(S.RENAME_NOTE) }, onClick = { menuOpen = false; showRename = true })
                    DropdownMenuItem(
                        text = { Text(S.CHANGE_NOTE_COLOR) },
                        onClick = { menuOpen = false; showColorSheet = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (ready.locked) S.UNLOCK_CONTENTS else S.LOCK_CONTENTS) },
                        onClick = {
                            menuOpen = false
                            scope.launch { model.setContentLock(!ready.locked, toast) }
                        },
                    )
                }
            }
        }
    }
    if (showRename && ready != null) {
        RenameDialog(
            initial = ready.content.displayName,
            isExternal = ready.content.isExternal,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                scope.launch { model.rename(name, toast) }
            },
        )
    }
    if (showColorSheet && ready != null) {
        ColorSheet(
            current = ready.content.color,
            onColor = { name -> scope.launch { model.setColor(name, null, toast) } },
            onCustom = { hex -> scope.launch { model.setColor(ready.content.color ?: S.COLOR_YELLOW, hex, toast) } },
            onDismiss = { showColorSheet = false },
        )
    }
}

@Composable
private fun ReadyEditor(
    model: NoteEditorModel,
    state: EditorState.Ready,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showConflict by remember(state.conflict) { mutableStateOf(state.conflict != null) }
    Column(Modifier.fillMaxSize()) {
        // Banners, top-down: read-only gate, content lock, restored draft.
        if (state.readOnly) {
            EditorBanner(
                title = S.READ_ONLY_ACCESS,
                body = "This device can read notes but not change them. An owner can change that under Devices in Maestri.",
            )
        }
        if (state.locked) {
            EditorBanner(title = S.LOCKED_IN_MAESTRI, body = S.LOCKED_BANNER_BODY)
        }
        state.infoBanner?.let { banner ->
            EditorBanner(title = S.UNSAVED_EDITS_RESTORED, body = banner)
        }

        BasicTextField(
            value = state.text,
            onValueChange = model::onText,
            readOnly = state.readOnly,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
        )

        StatusFooter(model, state, scope, toast)
    }

    if (state.conflict != null && showConflict) {
        ConflictDialog(
            body = state.conflict.bodyMessage,
            onUseComputerVersion = {
                showConflict = false
                scope.launch { model.takeComputerVersion() }
            },
            onRetrySaving = {
                showConflict = false
                scope.launch { model.retrySave(toast) }
            },
        )
    }
}

@Composable
private fun StatusFooter(
    model: NoteEditorModel,
    state: EditorState.Ready,
    scope: CoroutineScope,
    toast: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        when (val st = state.status) {
            SaveStatus.Quiet ->
                Text(S.ALL_CHANGES_SAVED, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveStatus.Dirty -> {
                Text(
                    S.THIS_NOTE_HAS_UNSAVED_CHANGES,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (state.locked) S.YOUR_UNSAVED_EDITS_STAY_ON_THIS_DEVICE
                    else S.SAVING_CONTINUES_IN_THE_BACKGROUND,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SaveStatus.SavedButNewer ->
                Text(S.SAVED_YOU_HAVE_NEWER_EDITS, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SaveStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.message,
                    fontSize = 12.sp,
                    color = Red,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { scope.launch { model.retrySave(toast) } }) {
                    Text(S.RETRY_SAVING_NOTE, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorBanner(title: String, body: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Amber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, Amber.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Amber)
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ConflictDialog(
    body: String,
    onUseComputerVersion: () -> Unit,
    onRetrySaving: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* iOS keeps the dialog until a choice is made */ },
        title = { Text(S.CHANGED_ON_YOUR_COMPUTER) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onRetrySaving) { Text(S.RETRY_SAVING_NOTE) } },
        dismissButton = { TextButton(onClick = onUseComputerVersion) { Text(S.USE_THE_COMPUTERS_VERSION) } },
        containerColor = Surface1,
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    isExternal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.RENAME_NOTE) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                enabled = !isExternal,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Surface1,
    )
}

/** The iOS 10-name chip row + Clear Color + Custom Color. NAMES travel on the wire; only swatches are local. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ColorSheet(
    current: String?,
    onColor: (String) -> Unit,
    onCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCustom by remember { mutableStateOf(false) }
    var customHex by remember { mutableStateOf("#") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(S.CHANGE_NOTE_COLOR, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            S.COLOR_VOCABULARY.chunked(5).forEach { rowNames ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    rowNames.forEach { name ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val selected = current == name
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(swatchColor(name), CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface else Surface2,
                                        shape = CircleShape,
                                    )
                                    .clickable { onColor(name) },
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { onColor("") }) { Text(S.CLEAR_COLOR) }
                TextButton(onClick = { showCustom = true }) { Text(S.CUSTOM_COLOR) }
            }
        }
    }
    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text(S.CUSTOM_COLOR) },
            text = {
                OutlinedTextField(
                    value = customHex,
                    onValueChange = { customHex = it },
                    singleLine = true,
                    placeholder = { Text("#RRGGBB") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hex = customHex.trim()
                    if (Regex("^#([0-9A-Fa-f]{6})$").matches(hex)) {
                        showCustom = false
                        onCustom(hex)
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } },
            containerColor = Surface1,
        )
    }
}

/** Swatch rendering of the host's named palette — UI approximation; the NAME travels on the wire. */
fun swatchColor(name: String?): Color = when (name) {
    S.COLOR_YELLOW -> Color(0xFFF2CE4B)
    S.COLOR_PINK -> Color(0xFFEFA3C7)
    S.COLOR_BLUE -> Color(0xFF7FC1EA)
    S.COLOR_GREEN -> Color(0xFF8FD694)
    S.COLOR_ORANGE -> Color(0xFFF2A25C)
    S.COLOR_PURPLE -> Color(0xFFB49BE0)
    S.COLOR_WHITE -> Color(0xFFE8E8E6)
    S.COLOR_CHARCOAL -> Color(0xFF3C3C40)
    S.COLOR_SLATE -> Color(0xFF6E7076)
    S.COLOR_MIDNIGHT -> Color(0xFF23232B)
    else -> Color(0xFFF2CE4B) // null/cleared → host default
}