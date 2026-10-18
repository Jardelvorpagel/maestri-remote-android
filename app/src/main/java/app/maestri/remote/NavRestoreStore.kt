package app.maestri.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.navRestoreDataStore by preferencesDataStore(name = "maestri_nav_restore")

/**
 * Last-nav-destination persistence (todo #55) — mirrors the AppLockStore house style:
 * its OWN DataStore file, IO scope, read-only callers.
 *
 * A minimized app loses its screen two ways: a launcher tap on the default
 * `standard` launchMode stacks a FRESH MainActivity (new composition, new
 * NavController at the start destination), and a background process death drops
 * the in-memory back stack entirely. SavedStateHandle alone cannot cover either
 * (fresh instance / dead process), so the last RESOLVED route string
 * ("feed/<wsId>/<wsName>", "terminal/<wsId>/<tid>/<name>", …) is persisted here
 * on every destination change and re-validated before any restore. Only the
 * route string is stored — no tokens, no content.
 */
class NavRestoreStore(private val context: Context) {

    private object Keys {
        val LAST_ROUTE = stringPreferencesKey("last_route")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Write-behind guard: skip the DataStore edit when nothing changed. */
    @Volatile private var cached: String? = null

    /** Fire-and-forget persist of the current resolved route (blank = ignore). */
    fun saveRoute(route: String) {
        if (route.isBlank() || route == cached) return
        cached = route
        scope.launch {
            runCatching {
                context.navRestoreDataStore.edit { it[Keys.LAST_ROUTE] = route }
            }
        }
    }

    /** Cold-start read: the last persisted route, or null when none was ever saved. */
    suspend fun awaitRoute(): String? =
        runCatching {
            context.navRestoreDataStore.data.map { it[Keys.LAST_ROUTE] }.first()
        }.getOrNull()
}
