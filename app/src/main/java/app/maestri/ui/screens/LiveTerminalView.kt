package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.wire.Geometry
import app.maestri.wire.SocketState
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.controlShape

/**
 * Live terminal renderer on top of the tiny ANSI emulator ([app.maestri.wire.MiniTerm]).
 *
 * The emulator renders plain text (colors consumed, not drawn) — the documented degrade
 * path for an MVP without a full xterm engine. Input goes out as JSON frames; resize is
 * clamped client-side to 20..250 × 5..120.
 */
@Composable
fun LiveTerminalView(
    state: SocketState,
    error: String?,
    text: String,
    cursor: Pair<Int, Int>?,
    geometry: Geometry?,
    canResize: Boolean,
    isOwner: Boolean,
    fontSizeSp: Int = 13,
    onInput: (String) -> Unit,
    onResizeRequest: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val screenScroll = rememberScrollState()

    LaunchedEffect(text) {
        screenScroll.animateScrollTo(screenScroll.maxValue)
    }

    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.width(60.dp)) { ConnChip(state, error) }
            Text(
                "${geometry?.cols ?: "?"}×${geometry?.rows ?: "?"}" + (cursor?.let { "  r${it.second + 1}c${it.first + 1}" } ?: ""),
                fontFamily = MaestriType.Mono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canResize) {
                OutlinedButton(onClick = onResizeRequest) { Text("resize", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Type into the terminal${if (isOwner) "" else " (discarded: guest role)"}") },
            singleLine = true,
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Button(onClick = {
                    if (input.isNotEmpty()) {
                        onInput(input)
                        input = ""
                    }
                }) { Text("\u23CE") }
            },
        )
        Spacer(Modifier.height(4.dp))
        // quick keys — raw sequences sent as UTF-8 input per contract
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            QuickKey("↵") { onInput("\r") }
            QuickKey("⌃C") { onInput("\u0003") }
            QuickKey("⌃D") { onInput("\u0004") }
            QuickKey("↑") { onInput("\u001B[A") }
            QuickKey("↓") { onInput("\u001B[B") }
            QuickKey("⇥") { onInput("\t") }
            QuickKey("⎋") { onInput("\u001B") }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color(0xFF07080A), controlShape())
                .verticalScroll(screenScroll)
        ) {
            Text(
                text.ifBlank { if (state == SocketState.OPEN) "(empty screen)" else "…waiting for PTY bytes" },
                fontFamily = MaestriType.Mono,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.55f).sp,
                color = Color(0xFFC9D1C9),
                modifier = Modifier.padding(8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        val warn = when {
            state == SocketState.CLOSED && error != null -> "stream down: $error — reconnecting…"
            state == SocketState.CONNECTING -> "connecting…"
            state == SocketState.IDLE -> "stream off"
            else -> null
        }
        warn?.let { Text(it, color = if (state == SocketState.CLOSED) MaterialTheme.colorScheme.error else LocalMaestriColors.current.warning, fontSize = 11.sp) }
    }
}

@Composable
private fun QuickKey(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 6.dp)
            .height(30.dp),
    ) {
        Text(label, fontFamily = MaestriType.Mono, fontSize = 12.sp)
    }
}