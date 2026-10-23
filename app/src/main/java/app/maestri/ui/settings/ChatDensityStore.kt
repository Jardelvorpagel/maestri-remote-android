package app.maestri.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.chatDensityDataStore by preferencesDataStore(name = "maestri_chat_density")

/**
 * Chat list density: Roomy breathes, Compact is the board default.
 *
 * House style mirrors [TerminalFontStore]; only a String is stored, never anything
 * user-identifying. Anything unrecognized (including null) normalizes to Compact,
 * so a foreign or hand-edited value migrates instead of crashing.
 *
 * Consumed by the chat thread itself: list item spacing and message/tool bubble
 * padding both read [compact] (see LiveFeedContent), so the control is real.
 */
class ChatDensityStore(private val context: Context) {

    private object Keys {
        val DENSITY = stringPreferencesKey("chat_density")
    }

    companion object {
        const val COMPACT = "compact"
        const val ROOMY = "roomy"
        const val DEFAULT = COMPACT

        /** Pure, unit-testable: only "roomy" survives, everything else is Compact. */
        fun normalize(raw: String?): String = if (raw == ROOMY) ROOMY else COMPACT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(DEFAULT)

    val compact: StateFlow<Boolean> =
        state.map { normalize(it) == COMPACT }
            .stateIn(scope, SharingStarted.Eagerly, true)

    init {
        scope.launch {
            context.chatDensityDataStore.data.collect { prefs ->
                state.value = prefs[Keys.DENSITY] ?: DEFAULT
            }
        }
    }

    /** Persist the chosen density. */
    fun setCompact(compact: Boolean) {
        val value = if (compact) COMPACT else ROOMY
        state.value = value
        scope.launch { context.chatDensityDataStore.edit { it[Keys.DENSITY] = value } }
    }
}
