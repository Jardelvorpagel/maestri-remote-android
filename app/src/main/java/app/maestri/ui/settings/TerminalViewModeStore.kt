package app.maestri.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val Context.terminalViewModeDataStore by preferencesDataStore(name = "maestri_terminal_view")

/**
 * Terminal view-mode persistence (Terminal | Chat): the mode switch's value per
 * terminal id, default terminal. House style mirrors [TerminalFontStore]; only a
 * mode string per terminal id is stored, never anything user-identifying.
 */
class TerminalViewModeStore(private val context: Context) {

    companion object {
        const val TERMINAL = "terminal"
        const val CHAT = "chat"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = mutableMapOf<String, MutableStateFlow<String>>()
    private val lock = Any()

    private fun key(terminalId: String) = stringPreferencesKey("terminal_view_mode_" + terminalId)

    /** Mode for one terminal; survives rotation (StateFlow) and re-entry (DataStore). */
    fun mode(terminalId: String): StateFlow<String> = synchronized(lock) {
        flows.getOrPut(terminalId) {
            MutableStateFlow(TERMINAL).also { flow ->
                scope.launch {
                    context.terminalViewModeDataStore.data.collect { prefs ->
                        val stored = prefs[key(terminalId)]
                        flow.value = if (stored == CHAT) CHAT else TERMINAL
                    }
                }
            }
        }
    }

    /** Persist the mode; unknown values coerce to terminal. */
    fun setMode(terminalId: String, mode: String) {
        val next = if (mode == CHAT) CHAT else TERMINAL
        mode(terminalId).let { (it as MutableStateFlow<String>).value = next }
        scope.launch { context.terminalViewModeDataStore.edit { prefs -> prefs[key(terminalId)] = next } }
    }
}
