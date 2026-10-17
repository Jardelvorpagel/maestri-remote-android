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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriRadii
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.warmRing
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
     * allowed = BIOMETRIC_STRONG | DEVICE_CREDENTIAL — the API contract FORBIDS
     * setNegativeButtonText when DEVICE_CREDENTIAL is allowed (the credential
     * screen owns its own cancel). The previous build crashed prompt-prepare on
     * device with IllegalArgumentException "Negative text must not be set if
     * device credential authentication is allowed" (3 tablet crashes
     * 17:54/17:56/21:21), so the negative branch is dropped entirely; no
     * biometric-only alternative is retained.
     *
     * Spec 4.5-companion hardening: the whole prompt prepare/launch is wrapped in
     * [runCatching] — a prepare error leaves the gate LOCKED on the cover (Unlock
     * re-taps re-attempt, each attempt contained) and can never kill the process
     * while the lock is enabled. Outcomes are never logged beyond the state
     * transition.
     */
    fun launchPrompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        runCatching {
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
                // NO setNegativeButtonText here: illegal whenever DEVICE_CREDENTIAL
                // is allowed (API contract); the credential screen owns its cancel.
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(info)
        }
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

/** Lock cover: full-bleed canvas chrome, headline + Unlock (verbatim strings only).
 *  [interactive] false during the cold-start loading window — locked-looking, no
 *  prompt is launched for a lock that may still resolve to disabled. */
@Composable
private fun AppLockCover(store: AppLockStore, activity: FragmentActivity?, interactive: Boolean) {
    val colors = LocalMaestriColors.current
    // design-v2 09 .lockov px-perfect: scrim NearBlack @ .85 — translucency over live
    // content, NO backdrop blur (Compose 1.7; artifact blur noted, ruling: skip).
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}, // absorb taps: nothing behind stays interactive
        color = Palette.NearBlack.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, alignment = Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // .lk: 68dp circle, ring-depth (warm ring + whisper shadow)
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .warmRing(colors.hairline, radius = 68.dp)
                    .clip(CircleShape)
                    .background(colors.canvasSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("🔒", fontSize = 24.sp)
            }
            Text(
                SettingsStrings.lockHeadline,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ivory,
            )
            Text(
                SettingsStrings.explainer,
                fontSize = 12.sp,
                color = Palette.WarmSilver,
            )
            val grace by store.graceSeconds.collectAsState()
            Button(
                onClick = if (interactive) {
                    { activity?.let { AppLockGate.launchPrompt(it) { store.unlock() } } }
                } else {
                    { }
                },
                shape = RoundedCornerShape(MaestriRadii.Card),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp) // .unlock-btn min-height 52
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(MaestriRadii.Card),
                        ambientColor = Palette.Terracotta.copy(alpha = 0.32f),
                        spotColor = Palette.Terracotta.copy(alpha = 0.32f),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(MaestriRadii.Card)),
            ) {
                Text(
                    SettingsStrings.unlock,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Ivory,
                )
            }
            // .lockchip: grace + fallback, mono 11
            Text(
                "grace: " + when (grace) {
                    0 -> SettingsStrings.graceImmediately
                    300 -> SettingsStrings.graceAfter5Minutes
                    else -> SettingsStrings.graceAfter1Minute
                } + " · fallback = device PIN", // CANDIDATE fallback literal
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = Palette.WarmSilver,
            )
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
    val enabledOrNull by store.enabledOrNull.collectAsState()
    // Spec 4.5 cold-start race: while the initial DataStore read is pending the
    // enabled state is true-or-unknown, so the gate presents locked-looking until
    // the read resolves (then either releases, or locks + prompts immediately).
    val loading = enabledOrNull == null
    val activity = LocalContext.current as? FragmentActivity

    // FLAG_SECURE recents blanking while locked (spec 4.6, ADAPTED-CANDIDATE, one
    // bool). During the loading window the cover itself already looks like the
    // lock screen, so a mid-load recents snapshot is visually covered.
    LaunchedEffect(locked) {
        if (activity != null) applyRecentsBlanking(activity, locked)
    }

    // Auto-shown prompt on the frame where the gate engages (spec 3 mapping table)
    // — including the frozen moment the cold read resolves to enabled: flipping
    // locked = true re-runs this effect, forcing the prompt immediately.
    LaunchedEffect(locked) {
        if (locked && activity != null) {
            AppLockGate.launchPrompt(activity) { store.unlock() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        if (locked || loading) {
            AppLockCover(store, activity, interactive = locked)
        }
    }
}