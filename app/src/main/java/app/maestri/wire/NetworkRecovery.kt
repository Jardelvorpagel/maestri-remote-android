package app.maestri.wire

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches the default network so a dropped socket can reconnect the MOMENT connectivity
 * comes back, instead of waiting out its backoff (1s → 16s per attempt).
 *
 * A terminal whose byte stream is dead is a frozen terminal: nothing repaints, and on a
 * phone that reads as "the scroll is slow" rather than "the connection is down". The
 * backoff exists for a host that is down; a phone that just came back from a tunnel or a
 * network flap is a different case, and it should not cost the user a quarter of a
 * minute of frozen screen.
 */
class NetworkRecovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAvailable: () -> Unit,
) {
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d("QAStream", "network available")
            scope.launch { onAvailable() }
        }
    }

    fun start() {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.d("QAStream", "network recovery: no ConnectivityManager")
            return
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
            Log.d("QAStream", "network recovery: registered")
        } catch (e: Exception) {
            Log.d("QAStream", "network recovery: register failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
    }
}
