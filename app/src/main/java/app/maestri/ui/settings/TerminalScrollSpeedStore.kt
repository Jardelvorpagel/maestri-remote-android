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

private val Context.terminalScrollSpeedDataStore by preferencesDataStore(name = "maestri_terminal_scroll_speed")

/**
 * Terminal scroll speed: how many rows the content moves per row of finger travel.
 *
 * 1× is the terminal-native feel — the content tracks the finger one row at a time. A phone
 * asks for more than that: a flick on any native list carries the content far past the finger,
 * so 1× reads as sluggish on a long agent transcript. The multiplier is applied to the drag
 * delta before it reaches the page, so it scales both scroll paths at once (the mouse-aware
 * TUI path, which turns the drag into wheel events, and the plain scrollback path).
 *
 * House style mirrors [TerminalFontStore]; only an Int is stored, never anything
 * user-identifying.
 */
class TerminalScrollSpeedStore(private val context: Context) {

    private object Keys {
        val SPEED_X = intPreferencesKey("terminal_scroll_speed_x")
    }

    companion object {
        const val MIN_X = 1
        const val MAX_X = 8
        const val DEFAULT_X = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(DEFAULT_X)

    val speedX: StateFlow<Int> =
        state.map { it.coerceIn(MIN_X, MAX_X) }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_X)

    init {
        scope.launch {
            context.terminalScrollSpeedDataStore.data.collect { prefs ->
                state.value = prefs[Keys.SPEED_X] ?: DEFAULT_X
            }
        }
    }

    /** Clamp 1-8, step 1, persisted. */
    fun setSpeed(x: Int) {
        val clamped = x.coerceIn(MIN_X, MAX_X)
        state.value = clamped
        scope.launch { context.terminalScrollSpeedDataStore.edit { it[Keys.SPEED_X] = clamped } }
    }
}
