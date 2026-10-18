package app.maestri.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.maestri.ui.Style.LocalMaestriColors

/**
 * Pairing screen.
 *
 * Flow: host[:port] → probe (GET /api/info, capture cert) → confirm the SPKI SHA-256
 * ("security key", hex colon-separated like the Manual tab) → POST /pair with a 6-digit
 * code → store {token, deviceId, serverKeyHash, hosts[]}.
 *
 * Advanced path reuses an existing token (no pairing code burned) — verified via
 * GET /api/info with the token before anything is stored.
 */
sealed interface PairingMode {
    object AddMachine : PairingMode
    object AuthLost : PairingMode

    companion object {
        fun idOf(mode: PairingMode?): String? = when (mode) {
            AddMachine -> "add-machine"
            AuthLost -> "auth-lost"
            null -> null
        }

        fun fromId(id: String?): PairingMode? = when (id) {
            "add-machine" -> AddMachine
            "auth-lost" -> AuthLost
            else -> null
        }

        val saver = androidx.compose.runtime.saveable.Saver<PairingMode?, String>(
            save = { idOf(it) },
            restore = { fromId(it) },
        )
    }
}

@Composable
fun PairingScreen(
    session: app.maestri.wire.session.WireSession,
    authNotice: String?,
    onNoticeShown: () -> Unit,
    mode: PairingMode = PairingMode.AuthLost,
    prefillHost: String? = null,
    prefillPort: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var storedNotice by remember { mutableStateOf(authNotice) }
    if (authNotice != null && storedNotice != authNotice) storedNotice = authNotice

    var host by remember { mutableStateOf(prefillHost ?: "") }
    var port by remember { mutableStateOf(prefillPort ?: "7434") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Android remote") }
    var expectedKey by remember { mutableStateOf("") }   // optional: from Manual tab / QR
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var tokenReuse by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("form") } // form | confirm | pairing | done
    var computedKeyHex by remember { mutableStateOf("") }
    var computedKeyB64 by remember { mutableStateOf("") }
    var probedName by remember { mutableStateOf("") }
    var probedCaps by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }

    val cancelNow = onCancel
    if (cancelNow != null) {
        BackHandler {
            when (step) {
                "confirm" -> step = "form"
                else -> if (!busy) cancelNow()
            }
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (cancelNow != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (!busy) cancelNow() }, enabled = !busy) { Text("Close") }
                }
                Spacer(Modifier.height(18.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("Maestri Wire", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "Pair this device once with your Maestri host",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            if (storedNotice != null) {
                NoticeCard(text = storedNotice!!, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = {
                        storedNotice = null
                        onNoticeShown()
                    }) { Text("Dismiss") }
                }
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = {
                    scope.launch {
                        session.forgetEverything()
                        onNoticeShown()
                        storedNotice = null
                    }
                }) { Text("Forget this server", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(6.dp))
            }

            when (step) {
                "form", "pairing" -> {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        label = { Text("Host (IP from Settings → Wire)") },
                        placeholder = { Text("192.168.1.10") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(80) },
                            label = { Text("Device name") },
                            singleLine = true,
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (usePassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Manual-tab password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                            label = { Text("Pairing code (6 digits)") },
                            placeholder = { Text("Settings → Wire → Pairing") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = { usePassword = !usePassword }) {
                        Text(if (usePassword) "Use 6-digit code instead" else "Pair by password instead")
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "– Advanced" else "+ Advanced (reuse existing token)", fontSize = 12.sp)
                    }
                    if (showAdvanced) {
                        OutlinedTextField(
                            value = tokenReuse,
                            onValueChange = { tokenReuse = it.trim() },
                            label = { Text("Existing token (device already paired)") },
                            placeholder = { Text("64 hex characters") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    Button(
                        enabled = !busy && host.isNotBlank() && (code.length == 6 || password.isNotBlank() || tokenReuse.isNotBlank()),
                        onClick = {
                            busy = true
                            err = null
                            val h = host.removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
                            val split = h.split(':')
                            val realHost = split[0]
                            val realPort = (split.getOrNull(1)?.toIntOrNull() ?: port.toIntOrNull() ?: 7434)
                            scope.launch {
                                try {
                                    val (info, pinB64) = session.probe(realHost, realPort)
                                    computedKeyB64 = pinB64
                                    computedKeyHex = app.maestri.wire.Pinning.pinHexFromB64(pinB64)
                                    probedName = info.name
                                    probedCaps = info.capabilities.size
                                    step = "confirm"
                                } catch (e: Exception) {
                                    err = "Probe failed: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Probing host…")
                        } else {
                            Text("Connect & verify key")
                        }
                    }
                    err?.let {
                        Spacer(Modifier.height(10.dp))
                        NoticeCard(text = it, tint = MaterialTheme.colorScheme.error)
                    }
                }

                "confirm" -> {
                    val expected = expectedKey.trim()
                    // Both sides are normalized (hex-colon / hex-plain / padded / unpadded base64)
                    // before the compare, so every equivalent spelling of the same digest matches.
                    val enteredNorm = expected
                        .takeIf { it.isNotBlank() }
                        ?.let { app.maestri.wire.Pinning.normalizeKey(it) }
                    val computedNorm = app.maestri.wire.Pinning.normalizeKey(computedKeyB64)
                    val keyUnchecked = expected.isBlank()
                    val keyGarbled = !keyUnchecked && enteredNorm == null
                    val matchOk = keyUnchecked || keyGarbled || enteredNorm == computedNorm
                    LaunchedEffect(expected, computedKeyB64) {
                        android.util.Log.d(
                            "QAAuth",
                            "key gate: enteredLen=${expected.length} computedLen=${computedKeyB64.length}" +
                                " format=" + when {
                                    keyUnchecked -> "empty"
                                    keyGarbled -> "unrecognized"
                                    else -> "ok"
                                } + " match=$matchOk (check skipped=${keyUnchecked || keyGarbled})",
                        )
                    }

                    Text("Security key", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Compare with the key in Maestri → Settings → Wire → Manual",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    KeyCard("Key on THIS host:", computedKeyHex)
                    KeyCard("Key on THIS host (base64):", computedKeyB64)
                    if (enteredNorm != null) {
                        KeyCard("Key you entered (normalized):", enteredNorm)
                    }
                    if (keyGarbled) {
                        NoticeCard("Key format not recognized — optional check skipped", LocalMaestriColors.current.warning)
                    } else if (enteredNorm != null && matchOk) {
                        NoticeCard("Keys match ✓", MaterialTheme.colorScheme.primary)
                    } else if (enteredNorm != null) {
                        NoticeCard("KEY MISMATCH — do not trust this host", MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = expectedKey,
                        onValueChange = { expectedKey = it },
                        label = { Text("Optional: paste the key you see on the host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Host: $probedName  ·  $probedCaps capabilities  ·  protocol v1",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = matchOk,
                        onClick = {
                            busy = true
                            err = null
                            step = "pairing"
                            val h = host.removePrefix("https://").removePrefix("http://").trim().trimEnd('/')
                            val split = h.split(':')
                            val realHost = split[0]
                            val realPort = (split.getOrNull(1)?.toIntOrNull() ?: port.toIntOrNull() ?: 7434)
                            scope.launch {
                                try {
                                    if (tokenReuse.isNotBlank()) {
                                        session.adopt(
                                            host = realHost,
                                            port = realPort,
                                            deviceName = deviceName.ifBlank { "Android remote" },
                                            token = tokenReuse.trim(),
                                            deviceId = "",
                                        )
                                    } else {
                                        session.pair(
                                            host = realHost,
                                            port = realPort,
                                            deviceName = deviceName.ifBlank { "Android remote" },
                                            code = if (code.length == 6 && !usePassword) code else null,
                                            password = if (usePassword && password.isNotBlank()) password else null,
                                        )
                                    }
                                    // paired == _pairing.value != null; recomposition switches to NavHost
                                } catch (e: app.maestri.wire.ApiException) {
                                    err = "Pairing refused (HTTP ${e.status}" + (e.code?.let { ", $it" } ?: "") + "): ${e.message}"
                                    step = "form"
                                } catch (e: Exception) {
                                    err = "Pairing failed: ${e.message}"
                                    step = "form"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(if (tokenReuse.isNotBlank()) "Verifying token…" else "Pairing…")
                        } else {
                            Text(if (tokenReuse.isNotBlank()) "Trust & use this token" else "Trust & pair")
                        }
                    }
                    OutlinedButton(
                        onClick = { step = "form" },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Back") }
                    err?.let {
                        Spacer(Modifier.height(10.dp))
                        NoticeCard(text = it, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCard(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .padding(12.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun NoticeCard(text: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = tint,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}