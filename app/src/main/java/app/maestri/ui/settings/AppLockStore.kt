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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    val enabled: StateFlow<Boolean> =
        context.appLockDataStore.data
            .map { it[Keys.ENABLED] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Seconds; default 60 = "After 1 Minute" (spec default, CANDIDATE). */
    val graceSeconds: StateFlow<Int> =
        context.appLockDataStore.data
            .map { it[Keys.GRACE] ?: 60 }
            .stateIn(scope, SharingStarted.Eagerly, 60)

    suspend fun setEnabled(value: Boolean) {
        context.appLockDataStore.edit { it[Keys.ENABLED] = value }
    }

    suspend fun setGraceSeconds(value: Int) {
        context.appLockDataStore.edit { it[Keys.GRACE] = value }
    }

    // ── In-memory lock state. App-scoped so rotation never drops the gate (spec 4.8);
    // deliberately NOT persisted: a killed process re-locks on relaunch (spec 4.5).
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