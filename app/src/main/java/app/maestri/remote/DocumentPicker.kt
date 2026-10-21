package app.maestri.remote

import android.net.Uri

/**
 * A host that can open the system document picker and hand the chosen files back.
 *
 * The app does not route this through androidx.activity's result API: that registry hands
 * the framework a random request code above 0xFFFF, which FragmentActivity refuses with
 * "Can only use lower 16 bits for requestCode" — so the picker never opens. This path owns
 * a 16-bit request code instead.
 */
interface DocumentPicker {
    fun pickDocuments(onPicked: (List<Uri>) -> Unit)
}
