package app.maestri.ui.notes

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Note drafts — iOS keeps editor drafts in prefs under `remote.note-drafts` and restores them
 * when the note opens ("Unsaved edits from a previous session were restored."). We keep the
 * same semantics in our own DataStore file (separate name space; the pairing DataStore belongs
 * to other lanes), one JSON entry per (server, node):
 *
 *   `{text, revision: <revision the draft was based on>, savedAt: <epoch millis>}`
 *
 * Cleared on a successful save; kept verbatim through every failure; read on editor open
 * BEFORE any save attempt so a draft never reaches the server unannounced.
 */

private val Context.notesDraftStore by preferencesDataStore(name = "maestri_note_drafts")

data class NoteDraft(
    val text: String,
    val basedOnRevision: String?,
    val savedAtMillis: Long,
) {
    companion object {
        fun fromJson(raw: String): NoteDraft? = runCatching {
            val o = JSONObject(raw)
            NoteDraft(
                text = o.optString("text", ""),
                basedOnRevision = if (o.has("revision") && !o.isNull("revision")) o.optString("revision") else null,
                savedAtMillis = o.optLong("savedAt", 0L),
            )
        }.getOrNull()
    }

    fun toJson(): String = JSONObject()
        .put("text", text)
        .also { o -> basedOnRevision?.let { o.put("revision", it) } }
        .put("savedAt", savedAtMillis)
        .toString()
}

class NotesDraftStore(private val context: Context) {

    /** `notedraft.<serverKey>.<nodeId>` — the shape the camera/followmac keys use, per lane. */
    private fun key(serverKey: String, nodeId: String) =
        stringPreferencesKey("notedraft.$serverKey.$nodeId")

    suspend fun draft(serverKey: String, nodeId: String): NoteDraft? {
        val prefs = context.notesDraftStore.data.first()
        return prefs[key(serverKey, nodeId)]?.let(NoteDraft::fromJson)
    }

    /** Throttled by the caller (editor debounces); stores the CURRENT text + its base revision. */
    suspend fun save(serverKey: String, nodeId: String, text: String, basedOnRevision: String?) {
        context.notesDraftStore.edit { prefs ->
            prefs[key(serverKey, nodeId)] =
                NoteDraft(text, basedOnRevision, System.currentTimeMillis()).toJson()
        }
    }

    suspend fun clear(serverKey: String, nodeId: String) {
        context.notesDraftStore.edit { prefs ->
            prefs.remove(key(serverKey, nodeId))
        }
    }
}