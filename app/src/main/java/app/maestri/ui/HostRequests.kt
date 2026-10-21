package app.maestri.ui

import android.content.Intent
import android.net.Uri

/**
 * Host-owned activities the app cannot reach through the Activity Result API.
 *
 * androidx.activity's registry hands the framework a random 32-bit request code above
 * 0xFFFF, and FragmentActivity refuses anything wider ("Can only use lower 16 bits for
 * requestCode"), so every `rememberLauncherForActivityResult` in this app throws before it
 * opens anything. The host therefore owns the request codes: they are 16 bits, and the
 * result comes back to the callback the caller left with it.
 */
interface HostRequests {
    /** Opens the system document picker; the chosen files come back as content URIs. */
    fun pickDocuments(onPicked: (List<Uri>) -> Unit)

    /** Launches [intent] for a result and hands the outcome back to [onResult]. */
    fun launchForResult(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit)

    /** Asks for the notification permission; the answer comes back as a boolean. */
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
}
