package app.maestri.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private val Context.appLockDataStore by preferencesDataStore(name = "maestri_app_lock")

/**
 * App-lock persistence — mirrors the iOS prefs `remote.lock.enabled` (bool) and
 * `remote.lock.grace` (seconds: 0 / 60 / 300) under its OWN DataStore keys, per the
 * PairingStore house style. Nothing code-like is ever stored: there IS no passcode
 * (the secret is Android hardware biometry + the OS credential screen); the only
 * persisted state is [enabled] and [graceSeconds], never logged.
 */
class AppLockStore(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("lock_enabled")
        val GRACE = intPreferencesKey("lock_grace_seconds")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cold-start truth (spec 4.5 conservative design): null while the initial
     *  DataStore read is pending, then true/false. The gate presents locked-looking
     *  for the whole null window, so the first frames after a process death can
     *  never render the app while the enabled state is still unknown. */
    private val _enabledOrNull = MutableStateFlow<Boolean?>(null)
    private val _graceOrNull = MutableStateFlow<Int?>(null)

    /** Read-resolution state: null = loading, else resolved enabled. */
    val enabledOrNull: StateFlow<Boolean?> = _enabledOrNull

    val enabled: StateFlow<Boolean> =
        _enabledOrNull
            .map { it ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Seconds; default 60 = "After 1 Minute" (spec default, CANDIDATE). */
    val graceSeconds: StateFlow<Int> =
        _graceOrNull
            .map { it ?: 60 }
            .stateIn(scope, SharingStarted.Eagerly, 60)

    init {
        scope.launch {
            var first = true
            context.appLockDataStore.data.collect { prefs ->
                _enabledOrNull.value = prefs[Keys.ENABLED] ?: false
                _graceOrNull.value = prefs[Keys.GRACE] ?: 60
                if (first) {
                    first = false
                    onColdReadResolved()
                }
            }
        }
    }

    /**
     * Spec 4.5: a killed process re-locks on relaunch (leftAt is never persisted,
     * so it reads 0 and the away-math arms). If the re-entry's onStart beat the
     * first DataStore read, the arm decision had to wait for this moment —
     * resolve by engaging the lock now when enabled; the gate's
     * LaunchedEffect(locked) then launches the biometric prompt immediately on
     * the next frame. Resolving to disabled just releases the loading cover.
     */
    private fun onColdReadResolved() {
        if (_enabledOrNull.value == true && !locked) locked = true
    }

    suspend fun setEnabled(value: Boolean) {
        context.appLockDataStore.edit { it[Keys.ENABLED] = value }
    }

    suspend fun setGraceSeconds(value: Int) {
        context.appLockDataStore.edit { it[Keys.GRACE] = value }
    }

    // ── In-memory lock state. App-scoped so rotation never drops the gate (spec 4.8);
    // deliberately NOT persisted: a killed process re-locks on relaunch (spec 4.5) —
    // the cold-start loading window above covers the frames until the read lands.
    /** armed = whether the lock test on (re)entry should engage. */
    @Volatile var rearmArmed: Boolean = false
        private set

    /** The live gate state (compose-observable). */
    var locked by androidx.compose.runtime.mutableStateOf(false)
        private set

    /**
     * Spec 4.1/4.2: on onStop stamp `leftAt` UNLESS rotation (isChangingConfigurations);
     * on onStart re-arm = enabled && (grace == 0 ? always : away > grace × 1000).
     */
    @Volatile private var leftAt: Long = 0L

    fun stampLeave(now: Long, isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) leftAt = now
    }

    /** Returns true when the gate must engage. */
    fun rearmOnStart(now: Long, enabled: Boolean, graceSeconds: Int): Boolean {
        rearmArmed = enabled
        if (!enabled || locked) {
            if (!enabled) locked = false
            return locked
        }
        val away = now - leftAt
        val armed = graceSeconds == 0 || away > graceSeconds * 1000L
        if (armed) locked = true
        return locked
    }

    /** In-memory unlock only (spec 4.5); no persisted "already-authenticated" flag. */
    fun unlock() {
        locked = false
    }
}