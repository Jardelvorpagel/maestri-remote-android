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
import kotlin.math.abs

private val Context.chatTextSizeDataStore by preferencesDataStore(name = "maestri_chat_text_size")

/**
 * Chat message text size: the display menu's 12 / 13 / 15 steps, default 13.
 *
 * House style mirrors [TerminalFontStore]; only an Int is stored, never anything
 * user-identifying. The flow and the setter both coerce through [nearestStep], so a
 * stored value from the terminal scale (10 or 20) migrates to the nearest chat step
 * (12 or 15) instead of crashing or silently resetting.
 */
class ChatTextSizeStore(private val context: Context) {

    private object Keys {
        val SIZE_SP = intPreferencesKey("chat_text_size_sp")
    }

    companion object {
        val STEPS = listOf(12, 13, 15)
        const val DEFAULT_SP = 13

        /**
         * Nearest valid step; an exact tie resolves to the lower step. Pure, so the
         * migration is unit-testable without a Context: nearestStep(10) == 12,
         * nearestStep(20) == 15, nearestStep(14) == 13.
         */
        fun nearestStep(raw: Int): Int =
            STEPS.minByOrNull { abs(it - raw) } ?: DEFAULT_SP
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(DEFAULT_SP)

    val sizeSp: StateFlow<Int> =
        state.map { nearestStep(it) }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_SP)

    init {
        scope.launch {
            context.chatTextSizeDataStore.data.collect { prefs ->
                state.value = prefs[Keys.SIZE_SP] ?: DEFAULT_SP
            }
        }
    }

    /** Coerce to the nearest step, then persist. */
    fun setSize(sp: Int) {
        val step = nearestStep(sp)
        state.value = step
        scope.launch { context.chatTextSizeDataStore.edit { it[Keys.SIZE_SP] = step } }
    }
}
