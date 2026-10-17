package app.maestri.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

private val Context.terminalFontDataStore by preferencesDataStore(name = "maestri_terminal_font")

/**
 * Terminal type scale persistence (design v2 §5.4): the Aa stepper's value, clamped
 * 10–20, default 13 ("Terminal font · JetBrains Mono NF · 13"). House style mirrors
 * [AppLockStore]; only an Int is stored, never anything user-identifying.
 */
class TerminalFontStore(private val context: Context) {

    private object Keys {
        val SIZE_SP = intPreferencesKey("terminal_font_size_sp")
    }

    companion object {
        const val MIN_SP = 10
        const val MAX_SP = 20
        const val DEFAULT_SP = 13
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(DEFAULT_SP)

    val sizeSp: StateFlow<Int> =
        state.map { it.coerceIn(MIN_SP, MAX_SP) }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_SP)

    init {
        scope.launch {
            context.terminalFontDataStore.data.collect { prefs ->
                state.value = prefs[Keys.SIZE_SP] ?: DEFAULT_SP
            }
        }
    }

    /** Clamp 10–20, step 1, persisted. */
    fun setSize(sp: Int) {
        val clamped = sp.coerceIn(MIN_SP, MAX_SP)
        state.value = clamped
        scope.launch { context.terminalFontDataStore.edit { it[Keys.SIZE_SP] = clamped } }
    }
}
