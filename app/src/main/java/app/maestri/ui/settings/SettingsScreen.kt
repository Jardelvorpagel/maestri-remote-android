package app.maestri.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.MaestriRadii
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.launch

/**
 * Settings v1 — the Privacy section ONLY (the fuller surface folds in when another
 * lane seeds one; flagged W-2/W-4 in the report). Reachable from an unlocked session
 * only (the gear affordance sits behind the lock gate by construction).
 *
 * Rulings honored: (A) neutral toggle label; (C) no custom keypad — the toggle engages
 * BiometricPrompt(STRONG | DEVICE_CREDENTIAL), never an in-app code entry.
 */
@Composable
fun SettingsScreen(
    session: WireSession,
    appLock: AppLockStore,
    onBack: () -> Unit,
    /** P4-A owns the real row; navigate via a existence-checked callback (W-4). */
    openNotificationsRoute: ((String) -> Unit)? = null,
) {
    val colors = app.maestri.ui.Style.LocalMaestriColors.current
    val scope = rememberCoroutineScope()
    val enabled by appLock.enabled.collectAsState()
    val grace by appLock.graceSeconds.collectAsState()
    val context = LocalContext.current as? ComponentActivity
    var pickerOpen by remember { mutableStateOf(false) }
    val canBiometrics = remember { context != null && AppLockGate.hasStrongBiometry(context) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            Text(
                SettingsStrings.settings,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(MaestriRadii.Card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(SettingsStrings.privacy, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))

                if (canBiometrics) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(SettingsStrings.requireBiometrics, fontSize = 16.sp) // ADAPTED per ruling A
                            Text(
                                SettingsStrings.explainer,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v -> scope.launch { appLock.setEnabled(v) } },
                        )
                    }
                    if (enabled) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pickerOpen = true },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (grace) {
                                        0 -> SettingsStrings.graceImmediately
                                        300 -> SettingsStrings.graceAfter5Minutes
                                        else -> SettingsStrings.graceAfter1Minute
                                    },
                                    fontSize = 16.sp,
                                )
                            }
                            Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(
                        SettingsStrings.requireBiometrics,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // No Class-3 biometry: credential-only lock stays available on relaunch
                    // (BIOMETRIC_STRONG|DEVICE_CREDENTIAL falls back to the OS credential
                    // screen — spec decision B default). The toggle row still works.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v -> scope.launch { appLock.setEnabled(v) } },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Link row to the P4-A notifications settings (route name is the sibling lane's
        // constant; navigates only when that lane's route is installed — flagged W-4).
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(MaestriRadii.Card),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openNotificationsRoute?.invoke("notifications-settings") },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Text(SettingsStrings.notifications, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (pickerOpen) {
        GracePickerDialog(
            current = grace,
            onPick = { v ->
                pickerOpen = false
                scope.launch { appLock.setGraceSeconds(v) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Grace picker: iOS order, verbatim labels → 0/60/300 s (spec 2.2; default 60 CANDIDATE). */
@Composable
private fun GracePickerDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SettingsStrings.explainer, fontSize = 16.sp) },
        text = {
            Column {
                GraceRow(SettingsStrings.graceImmediately, 0, current, onPick)
                GraceRow(SettingsStrings.graceAfter1Minute, 60, current, onPick)
                GraceRow(SettingsStrings.graceAfter5Minutes, 300, current, onPick)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GraceRow(label: String, seconds: Int, current: Int, onPick: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(seconds) }
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = current == seconds, onClick = { onPick(seconds) })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}