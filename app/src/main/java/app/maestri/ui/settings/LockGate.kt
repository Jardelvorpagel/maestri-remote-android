package app.maestri.ui.settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.concurrent.Executor

/**
 * The app-lock gate. Wraps the NavHost branch ONLY (MainActivity wires it there) — the
 * pairing flow is NEVER gated so recovery always stays reachable (spec 4.3).
 *
 * Ruling C (user mandate): no custom keypad; [BiometricPrompt] with
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL — biometry, else the OS credential screen
 * (system-owned strings, exactly how Apple owns the iPhone passcode sheet).
 */
object AppLockGate {

    /** Class-3 biometry present? (toggle row availability, spec 2.1.) */
    fun hasStrongBiometry(activity: androidx.activity.ComponentActivity): Boolean =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Launch one prompt per attempt (spec 4.7). Title = the corpus reason string;
     * negative = "Unlock"; fallback = DEVICE_CREDENTIAL. Errors keep the gate locked
     * (no retry loop); hardware lockout surfaces the system's own text. Biometric
     * outcomes are never logged beyond the boolean state transition.
     */
    fun launchPrompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                // onAuthenticationError: stay locked (cover + Unlock button) — spec 4.7.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(SettingsStrings.unlockPromptTitle)
            .setNegativeButtonText(SettingsStrings.unlock)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }
}

/** Recents blanking (spec 4.6, ADAPTED-CANDIDATE): FLAG_SECURE while locked. */
internal fun applyRecentsBlanking(activity: FragmentActivity, locked: Boolean) {
    if (locked) {
        activity.window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
    } else {
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Lock cover: full-bleed canvas chrome, headline + Unlock (verbatim strings only). */
@Composable
private fun AppLockCover(store: AppLockStore, activity: FragmentActivity?) {
    val colors = LocalMaestriColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = colors.canvasChrome) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SettingsStrings.lockHeadline,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { activity?.let { AppLockGate.launchPrompt(it) { store.unlock() } } },
                shape = RoundedCornerShape(MaestriRadii.Control),
            ) {
                Text(SettingsStrings.unlock)
            }
        }
    }
}

/** The gate itself: renders the cover when locked, otherwise the app content. */
@Composable
fun AppLockGate(
    store: AppLockStore,
    content: @Composable () -> Unit,
) {
    val locked = store.locked
    val activity = LocalContext.current as? FragmentActivity

    // FLAG_SECURE recents blanking while locked (spec 4.6, ADAPTED-CANDIDATE, one bool).
    LaunchedEffect(locked) {
        if (activity != null) applyRecentsBlanking(activity, locked)
    }

    // Auto-shown prompt on the frame where the gate engages (spec 3 mapping table).
    LaunchedEffect(locked) {
        if (locked && activity != null) {
            AppLockGate.launchPrompt(activity) { store.unlock() }
        }
    }

    if (locked) AppLockCover(store, activity) else content()
}