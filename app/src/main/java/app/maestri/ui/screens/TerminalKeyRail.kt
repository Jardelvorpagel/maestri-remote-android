package app.maestri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.ui.Style.MaestriType
import app.maestri.ui.Style.Palette
import app.maestri.ui.Style.TerminalThemes
import app.maestri.ui.Style.controlShape
import app.maestri.ui.Style.pillShape

/**
 * Byte-level keys a phone keyboard cannot produce (Ctrl, Esc, arrows) plus the chat jump.
 * Sends raw bytes through the same input frame the xterm page uses.
 */
@Composable
fun TerminalKeyRail(onSend: (String) -> Unit, onChat: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.Paper, controlShape())
                .padding(6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickKey("\u23CE", "Enter key", TerminalThemes.TokyoNight.blue) { onSend("\r") }
            QuickKey("\u2303C", "Control C — interrupt", TerminalThemes.TokyoNight.blue) { onSend("\u0003") }
            QuickKey("\u2303D", "Control D — end of file", TerminalThemes.TokyoNight.blue) { onSend("\u0004") }
            QuickKey("\u2303L", "Control L — clear screen", TerminalThemes.TokyoNight.blue) { onSend("\u000C") }
            QuickKey("\u2303R", "Control R — reverse search", TerminalThemes.TokyoNight.blue) { onSend("\u0012") }
            // ⌘ has no PTY encoding; the rail maps ⌘K to Ctrl-K (kill-line), the nearest byte.
            QuickKey("\u2318K", "Command K — kill line", TerminalThemes.TokyoNight.blue) { onSend("\u000B") }
            QuickKey("\u21E5", "Tab key", TerminalThemes.TokyoNight.blue) { onSend("\t") }
            QuickKey("esc", "Escape key", TerminalThemes.TokyoNight.blue) { onSend("\u001B") }
            QuickKey("\u2191", "Arrow up", TerminalThemes.TokyoNight.blue) { onSend("\u001B[A") }
            QuickKey("\u2193", "Arrow down", TerminalThemes.TokyoNight.blue) { onSend("\u001B[B") }
            QuickKey("\u2190", "Arrow left", TerminalThemes.TokyoNight.blue) { onSend("\u001B[D") }
            QuickKey("\u2192", "Arrow right", TerminalThemes.TokyoNight.blue) { onSend("\u001B[C") }
            // Pi's legacy key parser only recognises ctrl+arrows in the SS3 form
            // (ESC O a), and rejects ctrl+shift outright, so the plain CSI ctrl+up
            // never reaches the agent's prompt history.
            QuickKey("\u2303\u2191", "Control Up — previous prompt", TerminalThemes.TokyoNight.blue) { onSend("\u001bOa") }
            QuickKey("\u2303\u2193", "Control Down — next prompt", TerminalThemes.TokyoNight.blue) { onSend("\u001bOb") }
            QuickKey("\u2303End", "Control End — jump to the latest message", TerminalThemes.TokyoNight.blue) { onSend("\u001b[8^") }
            onChat?.let {
                QuickKey("Chat \u21E2", "Switch to chat mode", TerminalThemes.TokyoNight.green, onClick = it)
            }
            Spacer(Modifier.width(28.dp))
        }
        // pointer-transparent fade: the rail scrolls, the last key must not read as clipped
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(36.dp)
                .height(56.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Palette.Paper.copy(alpha = 0f),
                            Palette.Paper.copy(alpha = 0.6f),
                            Palette.Paper,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun QuickKey(label: String, desc: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 44.dp)
            .height(44.dp)
            .background(tint.copy(alpha = 0.16f), pillShape())
            .border(1.dp, tint.copy(alpha = 0.38f), pillShape())
            .semantics { contentDescription = desc }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // label glyphs carry no text semantics — [desc] is the accessible/automation name.
        Text(label, fontFamily = MaestriType.Mono, fontSize = 13.sp, color = tint)
    }
}
